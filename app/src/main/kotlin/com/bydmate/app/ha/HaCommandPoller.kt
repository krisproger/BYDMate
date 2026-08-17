package com.bydmate.app.ha

import android.content.Context
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.loop.SharedAdaptiveLoop
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Опрашивает HA на предмет команд (`GET /api/byd_diplus/commands`), выполняет
 * их через [ActionDispatcher] и подтверждает результат (`POST .../commands`).
 * Жизненный цикл (start/stop) управляется из TrackingService по `KEY_HA_ENABLED`.
 *
 * Логика: цикл ~10s; при ошибке/5xx пауза ~30s; single-flight и backoff —
 * как в [HaTransport]. Неизвестный command_id → ack `unsupported`.
 * Правило конкуренции с DiPlus-to-hass: кто первый забрал — тот и прав.
 */
@Singleton
class HaCommandPoller @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val actionDispatcher: ActionDispatcher,
    private val sharedAdaptiveLoop: SharedAdaptiveLoop,
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "HaCommands"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val POLL_INTERVAL_ERROR_MS = 30_000L
        private const val MIN_BACKOFF_MS = 12_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var flowJob: Job? = null

    /** Последний снапшот из [SharedAdaptiveLoop] — для скоростных гейтов ActionDispatcher. */
    @Volatile var latestData: DiParsData? = null

    @Volatile private var backoffMs: Long = MIN_BACKOFF_MS
    @Volatile private var cooldownUntilMs: Long = 0L

    private val inFlight = AtomicBoolean(false)

    val isRunning: Boolean get() = pollJob?.isActive == true

    fun start() {
        if (pollJob?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        flowJob = s.launch {
            sharedAdaptiveLoop.flow.collect { data -> latestData = data }
        }
        pollJob = s.launch {
            while (true) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    HaLog.append(context, "commands: ${e.message}")
                }
                delay(if (isCoolingDown) POLL_INTERVAL_ERROR_MS else POLL_INTERVAL_MS)
            }
        }
        HaLog.append(context, "Commands: started")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        flowJob?.cancel()
        flowJob = null
        scope?.cancel()
        scope = null
        HaLog.append(context, "Commands: stopped")
    }

    private val isCoolingDown: Boolean get() = System.currentTimeMillis() < cooldownUntilMs

    private suspend fun pollOnce() {
        val host = settingsRepository.getHaHost()
        val port = settingsRepository.getHaPort()
        val https = settingsRepository.isHaHttps()
        val token = settingsRepository.getString(SettingsRepository.KEY_HA_TOKEN, "").trim()
        val carName = settingsRepository.getString(SettingsRepository.KEY_HA_CAR_NAME, "").trim()
        val baseUrl = HaEndpoint.buildBaseUrl(if (https) "https" else "http", host, port)
        if (baseUrl == null || token.isBlank() || carName.isBlank() || isCoolingDown) return

        if (!inFlight.compareAndSet(false, true)) return
        try {
            val pollUrl = "$baseUrl/api/byd_diplus/commands?car_name=${java.net.URLEncoder.encode(carName, "UTF-8")}"
            val response = httpClient.newCall(Request.Builder()
                .url(pollUrl)
                .header("Authorization", "Bearer $token")
                .build())
                .execute()
            val body = response.use { it.body?.string() ?: "" }
            if (!response.isSuccessful) {
                cooldownUntilMs = System.currentTimeMillis() + backoffMs
                backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                HaLog.append(context, "commands: HTTP ${response.code}, backoff→${backoffMs}ms")
                return
            }
            backoffMs = MIN_BACKOFF_MS
            val outcomes = processPollBody(body)
            for (o in outcomes) {
                ack(host, port, https, token, o)
            }
        } catch (e: Exception) {
            HaLog.append(context, "commands: сеть — ${e.message}")
        } finally {
            inFlight.set(false)
        }
    }

    internal data class PollerOutcome(val commandId: String, val status: String, val message: String)

    internal suspend fun processPollBody(body: String): List<PollerOutcome> {
        val outcomes = mutableListOf<PollerOutcome>()
        val commands: JSONArray
        try {
            val obj = JSONObject(body)
            commands = obj.optJSONArray("commands") ?: return outcomes
        } catch (e: Exception) {
            HaLog.append(context, "commands: невалидный ответ — ${e.message}")
            return outcomes
        }
        for (i in 0 until commands.length()) {
            val cmd = commands.optJSONObject(i) ?: continue
            val id = cmd.optString("id")
            val commandId = cmd.optString("command")
            val value = cmd.optJSONObject("params")?.optString("value")
            val action = HaCommandMapper.resolve(commandId, value)
            if (action == null) {
                outcomes += PollerOutcome(id, "unsupported", "неизвестная команда: $commandId")
                continue
            }
            HaLog.append(context, "commands: выполняю $commandId (id=$id)")
            val result = runCatching { actionDispatcher.dispatch(action, latestData) }.getOrNull()
            val success = result?.success == true
            val status = if (success) "ok" else "error"
            val message = result?.reason ?: "успешно"
            HaLog.append(context, "commands: $commandId → $status ($message)")
            outcomes += PollerOutcome(id, status, message)
        }
        return outcomes
    }

    private suspend fun ack(host: String, port: Int, https: Boolean, token: String, o: PollerOutcome) {
        val baseUrl = HaEndpoint.buildBaseUrl(if (https) "https" else "http", host, port) ?: return
        val body = buildAckJson(o.commandId, o.status, o.message)
        try {
            httpClient.newCall(Request.Builder()
                .url("$baseUrl/api/byd_diplus/commands")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON))
                .build())
                .execute().close()
            HaLog.append(context, "commands: ack ${o.commandId} → ${o.status}")
        } catch (e: Exception) {
            HaLog.append(context, "commands: ack ${o.commandId} не отправлен — ${e.message}")
        }
    }

    internal fun buildAckJson(commandId: String, status: String, message: String): String =
        JSONObject().apply {
            put("command_id", commandId)
            put("status", status)
            put("message", message)
        }.toString()
}