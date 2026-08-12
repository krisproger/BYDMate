package com.bydmate.app.ha

import android.content.Context
import android.location.Location
import com.bydmate.app.data.loop.SharedAdaptiveLoop
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.remote.DiParsData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Публикует телеметрию [DiParsData] в Home Assistant.
 *
 * Подписка на уже идущий [SharedAdaptiveLoop.flow] (каденция 1s/5s/30s),
 * каждый снапшот [HaSignalMapper]'ом превращается в HA-словарь `s`.
 * Локальный буфер [ConcurrentLinkedDeque] накапливает снапшоты между
 * отправками (rate-limit HA 100 req/min → пачками, не каждым тиком).
 * При недоступности HA батч остаётся в буфере и уходит при восстановлении
 * связи (cap [MAX_BUFFER] — старые снапшоты вытесняются, свежие ценнее).
 *
 * GPS отдаётся [latestLocation] (ставится из TrackingService) и уходит в
 * блок `g` снапшота только при валидном фиксе.
 *
 * Жизненный цикл: [start]/[stop] дергаются из TrackingService; внутри —
 * собственная CoroutineScope (как у [com.bydmate.app.data.remote.AlicePollingManager]).
 */
@Singleton
class HaPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedAdaptiveLoop: SharedAdaptiveLoop,
    private val transport: HaTransport,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        /** Интервал флаша буфера: реже, чем rate-limit HA (1 req/s), есть запас. */
        private const val FLUSH_INTERVAL_MS = 10_000L
        /** Максимум снапшотов в одном батче запроса. */
        private const val MAX_BATCH = 200
        /** Потолок локального буфера (свежие вытесняют старые). */
        private const val MAX_BUFFER = 500
        /** Фикс старше этого не считается валидным GPS для снапшота. */
        private const val GPS_MAX_AGE_MS = 60_000L
    }

    private val buffer = ConcurrentLinkedDeque<HaSnapshot>()

    private var scope: CoroutineScope? = null
    private var flowJob: Job? = null
    private var flushJob: Job? = null

    /** Последний валидный GPS-фикс; ставится из TrackingService.onLocationChanged. */
    @Volatile var latestLocation: Location? = null

    val isRunning: Boolean get() = flowJob?.isActive == true

    /** Количество накопленных, но ещё не отправленных снапшотов (для UI/диагностики). */
    val bufferedCount: Int get() = buffer.size

    fun start() {
        if (flowJob?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        flowJob = s.launch {
            HaLog.append(context, "Publisher: подписка на SharedAdaptiveLoop.flow (${sharedAdaptiveLoop.flow.hashCode()})")
            sharedAdaptiveLoop.flow.collect { data -> onSnapshot(data) }
        }
        flushJob = s.launch {
            refreshEnabled()
            HaLog.append(context, "Publisher: started, enabled=${isEnabledCache == true}, буфер=${buffer.size}")
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                try {
                    flush()
                } catch (e: Exception) {
                    HaLog.append(context, "flush error: ${e.message}")
                }
            }
        }
        HaLog.append(context, "Publisher: started")
    }

    fun stop() {
        flowJob?.cancel()
        flowJob = null
        flushJob?.cancel()
        flushJob = null
        scope?.cancel()
        scope = null
        HaLog.append(context, "Publisher: stopped (buffered=${buffer.size})")
        isEnabledCache = null
    }

    private fun onSnapshot(data: DiParsData) {
        val signals = HaSignalMapper.map(data)
        if (!HaSignalMapper.hasAnySignal(signals)) return

        val nowSec = System.currentTimeMillis() / 1000L
        val loc = latestLocation
        val gps = loc?.takeIf { System.currentTimeMillis() - it.time <= GPS_MAX_AGE_MS }
        val snap = HaSnapshot(
            timestampSec = nowSec,
            signals = signals,
            lat = gps?.latitude,
            lon = gps?.longitude,
            accuracy = gps?.accuracy ?: 0f,
            fixTimeSec = if (gps != null) gps.time / 1000L else 0L,
        )
        // Cap: свежие ценнее. Сдвигаем хвост (новые снапшоты имеют больший t).
        if (buffer.size >= MAX_BUFFER) {
            buffer.pollLast()
        }
        buffer.offerFirst(snap)

        if (firstSampleLogged) {
            HaLog.append(context, "первый снапшот: ${signals.keys().asSequence().joinToString(",") { it }}")
            firstSampleLogged = false
        }
    }

    private var firstSampleLogged = true

    private suspend fun flush() {
        refreshEnabled()
        if (isEnabledCache != true) {
            HaLog.append(context, "flush: выключено или конфиг неполный — пропуск")
            return
        }
        if (buffer.isEmpty()) return

        val batch = mutableListOf<HaSnapshot>()
        repeat(MAX_BATCH) {
            buffer.pollLast()?.let { batch.add(it) }
        }
        if (batch.isEmpty()) return

        val carName = settingsRepository.getString(SettingsRepository.KEY_HA_CAR_NAME, "").trim()
        transport.push(context, batch.sortedBy { it.timestampSec }, carName)
            .onFailure { e ->
                // Не терять данные: вернуть батч обратно в буфер (спереди).
                batch.sortedByDescending { it.timestampSec }.forEach { buffer.offerFirst(it) }
                HaLog.append(context, "flush failed (${e.message}), ${batch.size} сн. возвращены в буфер")
            }
    }

    /** Локальный кэш "включено и сконфигурировано" — обновляется из flush-цикла. */
    @Volatile private var isEnabledCache: Boolean? = null

    private suspend fun refreshEnabled(): Boolean {
        val enabled = settingsRepository.getString(SettingsRepository.KEY_HA_ENABLED, "false") == "true"
        val url = settingsRepository.getString(SettingsRepository.KEY_HA_URL, "").isNotBlank()
        val token = settingsRepository.getString(SettingsRepository.KEY_HA_TOKEN, "").isNotBlank()
        val carName = settingsRepository.getString(SettingsRepository.KEY_HA_CAR_NAME, "").isNotBlank()
        val newValue = enabled && url && token && carName
        if (newValue != isEnabledCache) {
            HaLog.append(context, "HA-телеметрия: " + if (newValue) "включена (enabled=$enabled, конфиг полный)" else "без отправки (enabled=$enabled)")
        }
        isEnabledCache = newValue
        return newValue
    }
}