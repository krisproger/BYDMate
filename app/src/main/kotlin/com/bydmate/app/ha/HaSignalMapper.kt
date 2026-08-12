package com.bydmate.app.ha

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject

/**
 * Маппит [DiParsData] → HA-Словарь `s` для снапшота `{t, g, s}`.
 *
 * Набор ключей и их значения должны соответствовать `custom_components/
 * diplus2hass/const.py` (NUMERIC_SENSORS / ENUM_SENSORS / BINARY_SENSORS).
 * Сверено с `info/BYDMate_HA_telemetry_map.md` (Фаза 0): здесь базовый
 * поднабор из полей, которые маппятся 1:1 и уже имеют HA-сущности.
 *
 * Числовые поля уходят числами; enum-поля — строками-метками в тех же
 * регистрах, что шлёт наш DiPlus-to-hass APK (совместимость с state_map
 * командных select-сущностей). Значение null просто пропускается — HA
 * хранит последнее известное значение сигнала.
 */
object HaSignalMapper {
    /**
     * Собрать словарь `s`. Ключи всегда строки, значения — числа или строки.
     * Возвращает пустой JSONObject, если ни одно поле не удалось замапить
     * (такой снапшот не имеет смысла слать, вызывающий пропустит его).
     */
    fun map(data: DiParsData): JSONObject {
        val s = JSONObject()

        // ── Numeric (pass-through, единицы совпадают с const.py) ───────────
        data.soc?.let { s.put("soc", it) }
        data.speed?.let { s.put("speed", it) }
        data.mileage?.let { s.put("range", it) }                 // «range / mileage», km
        data.power?.let { s.put("engine_power", it) }            // kW
        data.maxBatTemp?.let { s.put("battery_temp_max", it) }
        data.avgBatTemp?.let { s.put("battery_temp_avg", it) }
        data.minBatTemp?.let { s.put("battery_temp_min", it) }
        data.batteryCapacityKwh?.let { s.put("battery_capacity", it) }
        data.totalElecConsumption?.let { s.put("total_energy", it) }
        data.voltage12v?.let { s.put("battery_voltage", it) }
        data.maxCellVoltage?.let { s.put("cell_voltage_max", it) }
        data.minCellVoltage?.let { s.put("cell_voltage_min", it) }
        data.exteriorTemp?.let { s.put("outside_temp", it) }
        data.insideTemp?.let { s.put("cabin_temp", it) }
        data.acTemp?.let { s.put("ac_set_temp", it) }
        data.fanLevel?.let { s.put("fan_speed", it) }
        data.windowFL?.let { s.put("window_fl", it) }            // %
        data.windowFR?.let { s.put("window_fr", it) }
        data.windowRL?.let { s.put("window_rl", it) }
        data.windowRR?.let { s.put("window_rr", it) }
        data.sunroof?.let { s.put("sunroof", it) }               // %
        data.tirePressFL?.let { s.put("tyre_pressure_fl", it) }  // kPa
        data.tirePressFR?.let { s.put("tyre_pressure_fr", it) }
        data.tirePressRL?.let { s.put("tyre_pressure_rl", it) }
        data.tirePressRR?.let { s.put("tyre_pressure_rr", it) }

        // ── Enums → string labels (совместимы с BINARY_ON_MAP / state_map) ──
        gearLabel(data.gear)?.let { s.put("gear", it) }          // 1=P,2=R,3=N,4=D
        powerStateLabel(data.powerState)?.let { s.put("power_state", it) }  // 0=off,1=on,2=driving
        chargingStateLabel(data.chargingStatus)?.let { s.put("charging_state", it) }
        driveModeLabel(data.driveMode)?.let { s.put("drive_mode", it) }     // 1=eco,2=sport,3=normal
        workModeLabel(data.workMode)?.let { s.put("powertrain_mode", it) }  // 0=stop,1=ev,2=forced ev,3=hev
        data.acStatus?.let { s.put("ac_state", onOffLabel(it)) }            // 0=off,1=on
        data.acCirc?.let { s.put("ac_recirculation", recircLabel(it)) }     // 0=fresh,1=recirc
        data.doorFL?.let { s.put("driver_door", openClosedLabel(it)) }
        data.doorFR?.let { s.put("passenger_door", openClosedLabel(it)) }
        data.doorRL?.let { s.put("rear_left_door", openClosedLabel(it)) }
        data.doorRR?.let { s.put("rear_right_door", openClosedLabel(it)) }
        data.trunk?.let { s.put("trunk", openClosedLabel(it)) }
        data.hood?.let { s.put("bonnet", openClosedLabel(it)) }
        seatbeltLabel(data.seatbeltFL)?.let { s.put("driver_seatbelt", it) }
        lockLabel(data.lockFL)?.let { s.put("driver_door_lock", it) }       // 1=unlocked,2=locked
        data.lightLow?.let { s.put("low_beam", onOffLabel(it)) }
        data.lightSide?.let { s.put("sidelights", onOffLabel(it)) }
        data.lightHigh?.let { s.put("high_beam", onOffLabel(it)) }
        drlLabel(data.drl)?.let { s.put("drl", it) }                        // 1=on,2=off
        turnSignalLabel(data.turnSignal)?.let { s.put("turn_signal", it) }  // mask 2=left,4=right,6=hazard

        return s
    }

    /** true если в словаре есть хоть одно значение — снапшот полезен для HA. */
    fun hasAnySignal(s: JSONObject): Boolean = s.length() > 0

    // ── label helpers (регистр совпадает с приложением DiPlus-to-hass) ─────

    private fun onOffLabel(v: Int): String? = when (v) {
        0 -> "off"
        1 -> "on"
        else -> null
    }

    private fun openClosedLabel(v: Int): String? = when (v) {
        0 -> "closed"
        1 -> "open"
        else -> null
    }

    internal fun gearLabel(v: Int?): String? = when (v) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        4 -> "D"
        5 -> "M"
        6 -> "S"
        else -> null
    }

    internal fun powerStateLabel(v: Int?): String? = when (v) {
        0 -> "off"
        1 -> "on"
        2 -> "driving"
        else -> null
    }

    // chargingStatus (BYDMate derived): 0=none, 1=connected, 2=charging.
    // HA BINARY_ON_MAP["charging_state"] truthy: Ready/started/charging/full.
    internal fun chargingStateLabel(v: Int?): String? = when (v) {
        1 -> "started"
        2 -> "charging"
        else -> null
    }

    internal fun driveModeLabel(v: Int?): String? = when (v) {
        1 -> "eco"
        2 -> "sport"
        3 -> "normal"
        else -> null
    }

    internal fun workModeLabel(v: Int?): String? = when (v) {
        0 -> "stop"
        1 -> "ev"
        2 -> "forced ev"
        3 -> "hev"
        else -> null
    }

    private fun recircLabel(v: Int): String? = when (v) {
        0 -> "fresh"
        1 -> "recirc"
        else -> null
    }

    internal fun seatbeltLabel(v: Int?): String? = when (v) {
        0 -> "unbuckled"
        1 -> "buckled"
        else -> null
    }

    internal fun lockLabel(v: Int?): String? = when (v) {
        1 -> "unlocked"
        2 -> "locked"
        else -> null
    }

    private fun drlLabel(v: Int?): String? = when (v) {
        1 -> "on"
        2 -> "off"
        else -> null
    }

    private fun turnSignalLabel(v: Int?): String? = when (v) {
        1 -> "off"
        2 -> "left"
        4 -> "right"
        6 -> "hazard"
        else -> null
    }
}