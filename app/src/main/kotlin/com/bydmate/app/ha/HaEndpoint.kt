package com.bydmate.app.ha

/**
 * Построение базового URL HA из структурных полей настроек и парсинг legacy
 * значения `ha_url` (одна строка со схемой). Исключает класс ошибки
 * "Expected URL scheme ... but no scheme was found", т.к. схема задаётся
 * отдельным полем, а не вводится вручную.
 */
data class HaEndpointParts(val scheme: String, val host: String, val port: Int)

object HaEndpoint {
    const val DEFAULT_PORT = 8123

    /** `scheme://host:port`; null при пустом host. Scheme приводится к нижнему регистру. */
    fun buildBaseUrl(scheme: String, host: String, port: Int): String? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val s = scheme.lowercase()
        return "$s://$h:$port"
    }

    /**
     * Разобрать legacy `ha_url` из настроек. Поддержка:
     *  - `http://host:port`, `https://host:port` — схема из строки;
     *  - `host:port`, `host` — схема по умолчанию `http`, порт по умолчанию [DEFAULT_PORT].
     * Возвращает null для пустой/некорректной строки.
     */
    fun parseLegacyUrl(raw: String): HaEndpointParts? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        var scheme = "http"
        var rest = trimmed
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx > 0) {
            scheme = trimmed.substring(0, schemeIdx).lowercase()
            if (scheme != "http" && scheme != "https") return null
            rest = trimmed.substring(schemeIdx + 3)
        } else if (schemeIdx == 0) {
            return null  // строка начинается с "://" — невалидная
        }
        if (rest.isEmpty()) return null
        val hostPort = rest.split("/", limit = 2).first()  // отрезать path/query
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0) {
            val host = hostPort.substring(0, colon)
            val port = hostPort.substring(colon + 1).toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return HaEndpointParts(scheme, host, port)
        }
        if (hostPort.isEmpty()) return null
        return HaEndpointParts(scheme, hostPort, DEFAULT_PORT)
    }
}