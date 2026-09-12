package com.bydmate.app.ha

import android.content.Context
import com.bydmate.app.data.repository.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Версия-хендшейк с интеграцией CARTelemetry: `GET /api/cartelemetry/info`
 * возвращает `{integration_version, api_version, capabilities}`. Сравнивает
 * `api_version` с требуемым минимумом и пишет в лог понятное сообщение вместо
 * догадок по HTTP-коду.
 */
@Singleton
class HaInfo @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val MIN_API_VERSION = 3
    }

    /** Одноразовая проверка совместимости; вызывается при старте публикации. */
    suspend fun check(context: Context) {
        val host = settingsRepository.getHaHost()
        val port = settingsRepository.getHaPort()
        val https = settingsRepository.isHaHttps()
        val token = settingsRepository.getString(SettingsRepository.KEY_HA_TOKEN, "").trim()
        val baseUrl = HaEndpoint.buildBaseUrl(if (https) "https" else "http", host, port)
        if (baseUrl == null || token.isBlank()) return

        try {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/cartelemetry/info")
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    HaLog.append(context, "info: HTTP ${resp.code} — интеграция устарела (нет /api/cartelemetry/info)")
                    return
                }
                val obj = JSONObject(body)
                val version = obj.optString("integration_version", "?")
                val api = obj.optInt("api_version", -1)
                if (api < MIN_API_VERSION) {
                    HaLog.append(context, "интеграция CARTelemetry $version (api $api) устарела — нужно api ≥ $MIN_API_VERSION")
                } else {
                    HaLog.append(context, "интеграция CARTelemetry $version (api $api) — совместима")
                }
            }
        } catch (e: Exception) {
            HaLog.append(context, "info: ${e.message}")
        }
    }
}
