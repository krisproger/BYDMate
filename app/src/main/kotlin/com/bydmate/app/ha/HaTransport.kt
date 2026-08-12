package com.bydmate.app.ha

import android.content.Context
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Транспорт телеметрии в Home Assistant: OkHttp POST на
 * `{base_url}/api/byd_diplus` (custom component diplus2hass).
 *
 * Payload совпадает с тем, что шлёт наш DiPlus-to-hass APK
 * (см. `DiPlus-to-hass/.../HassClient.java`): `{car_name, app_version, ts, batch}`.
 * Bearer-токен из настроек; на 429/5xx — экспоненциальный backoff
 * 12s→300s (аналогично HassClient/HassClient статике); single-flight
 * на уровне транспортного вызова. Словарь `s` строится
 * [HaSignalMapper]'ом в [HaPublisher].
 *
 * Чтение URL/токена/car_name выполняется на каждом push — настройки можно
 * менять на лету без рестарта сервиса.
 */
@Singleton
class HaTransport @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "HaTelemetry/Transport"
        private const val MIN_BACKOFF_MS = 12_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** Пока backoff активен (после 429/5xx) — true: push временно недоступен. */
    @Volatile private var backoffMs: Long = MIN_BACKOFF_MS

    @Volatile private var cooldownUntilMs: Long = 0L
    val isCoolingDown: Boolean get() = System.currentTimeMillis() < cooldownUntilMs

    private val inFlight = AtomicBoolean(false)

    /**
     * Отправить батч снапшотов в HA.
     * @param batch уже собранные [HaSnapshot]'ы (t/g/s)
     * @param carName имя автомобиля (car_name в HA); blank → fail
     * @return success, либо причина (включая backoff-отказ / misconfig).
     */
    suspend fun push(
        context: Context,
        batch: List<HaSnapshot>,
        carName: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext Result.success(Unit)
        val baseUrl = settingsRepository.getString(SettingsRepository.KEY_HA_URL, "").trim()
        val token = settingsRepository.getString(SettingsRepository.KEY_HA_TOKEN, "").trim()
        if (baseUrl.isBlank() || token.isBlank() || carName.isBlank()) {
            HaLog.append(context, "skip: неполная конфигурация (url/token/car_name)")
            return@withContext Result.failure(IllegalStateException("неполная конфигурация HA"))
        }
        if (isCoolingDown) {
            HaLog.append(context, "skip: backoff активен (до ${cooldownUntilMs}ms)")
            return@withContext Result.failure(IllegalStateException("backoff активен"))
        }
        if (!inFlight.compareAndSet(false, true)) {
            HaLog.append(context, "skip: передача уже идёт (single-flight)")
            return@withContext Result.failure(IllegalStateException("передача уже идёт"))
        }

        try {
            val json = JSONArray()
            for (snap in batch) json.put(snap.toJson())

            val payload = JSONObject().apply {
                put("car_name", carName)
                put("app_version", BuildConfig.VERSION_NAME)
                val maxTs = batch.maxOf { it.timestampSec }
                put("ts", maxTs)
                put("batch", json)
            }

            val url = baseUrl.trimEnd('/') + "/api/byd_diplus"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            val t0 = System.currentTimeMillis()
            httpClient.newCall(request).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                if (response.isSuccessful) {
                    backoffMs = MIN_BACKOFF_MS
                    HaLog.append(context, "OK ${batch.size} сн., ${ms}ms (${response.code})")
                    Result.success(Unit)
                } else {
                    cooldownUntilMs = System.currentTimeMillis() + backoffMs
                    HaLog.append(context, "HTTP ${response.code} за ${ms}ms, backoff→${backoffMs}ms (429/5xx)")
                    backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                    Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            HaLog.append(context, "сеть: ${e.message}")
            Result.failure(e)
        } finally {
            inFlight.set(false)
        }
    }
}

/** Готовый снапшот для батча: `{t, g?, s}`. */
data class HaSnapshot(
    val timestampSec: Long,
    val signals: JSONObject,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float = 0f,
    val fixTimeSec: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", timestampSec)
        if (lat != null && lon != null && !lat.isNaN() && !lon.isNaN()) {
            val g = JSONObject()
            g.put("lat", lat)
            g.put("lon", lon)
            g.put("a", accuracy)
            if (fixTimeSec > 0) g.put("t", fixTimeSec)
            put("g", g)
        }
        put("s", signals)
    }

    val isUseful: Boolean get() = signals.length() > 0
}