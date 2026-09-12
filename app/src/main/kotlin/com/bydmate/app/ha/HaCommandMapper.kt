package com.bydmate.app.ha

import com.bydmate.app.data.local.entity.ActionDef

/**
 * Маппит `command_id` из HA (`/api/cartelemetry/commands`) в [ActionDef] для
 * `ActionDispatcher`. Подмножество команд на старт; полный перенос
 * `sensor_command_map` — отдельной задачей. Значение параметра из
 * `params.value` подставляется в команду (температура °C, проценты окон).
 * Неизвестный `command_id` → null (fail-soft: ack `unsupported`).
 */
object HaCommandMapper {

    /** Команды без параметра: command_id → китайская команда. */
    private val fixed: Map<String, String> = mapOf(
        "ac_on" to "自动空调",
        "ac_off" to "关闭空调",
        "doors_lock" to "车门上锁",
        "doors_unlock" to "车门解锁",
    )

    /** Команды с параметром: command_id → шаблон, `%s` заменяется на value. */
    private val templated: Map<String, String> = mapOf(
        "ac_temp" to "设置温度%s",
        "window_driver" to "主驾打开%s",
        "window_passenger" to "副驾打开%s",
        "window_rear_left" to "后左打开%s",
        "window_rear_right" to "后右打开%s",
        "sunroof" to "天窗打开%s",
    )

    /**
     * Значение параметра из HA приводится к токену, валидному для
     * CommandTranslator: окна понимают только 0 и 100 (любой промежуточный
     * процент зажимается в 100), солнцелюк — 0/50/100 (прочее → 100).
     * Температура передаётся как есть — CommandTranslator зажимает 16..30.
     */
    private fun windowToken(template: String, value: String?): String {
        val pct = value?.trim()?.toIntOrNull() ?: 100
        val clamped = if (pct == 0) 0 else 100
        return template.replace("%s", clamped.toString())
    }

    private fun sunroofToken(value: String?): String {
        val pct = value?.trim()?.toIntOrNull() ?: 100
        val clamped = when {
            pct == 0 -> 0
            pct <= 50 -> 50
            else -> 100
        }
        return "天窗打开$clamped"
    }

    /** Специальная обработка: sunshade по значению (1=открыть, 2=закрыть). */
    private fun sunshadeCommand(value: String?): String? = when (value?.trim()) {
        "1" -> "遮阳帘打开"
        "2" -> "遮阳帘关闭"
        else -> null
    }

    /** Разрешить команду в ActionDef или null, если command_id не поддерживается. */
    fun resolve(commandId: String, value: String?): ActionDef? {
        val command: String? = when {
            commandId == "sunshade" -> sunshadeCommand(value)
            commandId in fixed -> fixed[commandId]
            commandId == "sunroof" -> sunroofToken(value)
            commandId == "ac_temp" -> templated["ac_temp"]?.replace("%s", value?.trim().orEmpty())
            commandId in templated -> windowToken(templated[commandId] ?: return null, value)
            else -> null
        }
        if (command.isNullOrBlank()) return null
        return ActionDef(command = command, displayName = commandId, kind = "param")
    }

    /** Множество поддерживаемых command_id (для тестов и будущего расширения). */
    fun supportedCommands(): Set<String> =
        fixed.keys + templated.keys + setOf("sunshade")
}