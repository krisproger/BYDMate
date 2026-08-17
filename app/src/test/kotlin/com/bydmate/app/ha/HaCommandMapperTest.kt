package com.bydmate.app.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaCommandMapperTest {

    @Test
    fun `ac_on maps to auto ac`() {
        val a = HaCommandMapper.resolve("ac_on", null)
        assertEquals("自动空调", a?.command)
        assertEquals("param", a?.kind)
    }

    @Test
    fun `ac_off maps to ac off`() {
        assertEquals("关闭空调", HaCommandMapper.resolve("ac_off", null)?.command)
    }

    @Test
    fun `ac_temp interpolates celsius`() {
        assertEquals("设置温度24", HaCommandMapper.resolve("ac_temp", "24")?.command)
    }

    @Test
    fun `doors_lock and unlock`() {
        assertEquals("车门上锁", HaCommandMapper.resolve("doors_lock", null)?.command)
        assertEquals("车门解锁", HaCommandMapper.resolve("doors_unlock", null)?.command)
    }

    @Test
    fun `window commands clamp to valid tokens`() {
        assertEquals("主驾打开100", HaCommandMapper.resolve("window_driver", "100")?.command)
        assertEquals("主驾打开100", HaCommandMapper.resolve("window_driver", "50")?.command)
        assertEquals("主驾打开0", HaCommandMapper.resolve("window_driver", "0")?.command)
        assertEquals("副驾打开100", HaCommandMapper.resolve("window_passenger", "80")?.command)
        assertEquals("后左打开0", HaCommandMapper.resolve("window_rear_left", "0")?.command)
        assertEquals("后右打开100", HaCommandMapper.resolve("window_rear_right", "100")?.command)
    }

    @Test
    fun `sunroof interpolates position`() {
        assertEquals("天窗打开100", HaCommandMapper.resolve("sunroof", "100")?.command)
        assertEquals("天窗打开50", HaCommandMapper.resolve("sunroof", "50")?.command)
        assertEquals("天窗打开0", HaCommandMapper.resolve("sunroof", "0")?.command)
    }

    @Test
    fun `sunshade open and close`() {
        assertEquals("遮阳帘打开", HaCommandMapper.resolve("sunshade", "1")?.command)
        assertEquals("遮阳帘关闭", HaCommandMapper.resolve("sunshade", "2")?.command)
    }

    @Test
    fun `unknown command is null`() {
        assertNull(HaCommandMapper.resolve("sentry", null))
        assertNull(HaCommandMapper.resolve("charge_soc", "50"))
        assertNull(HaCommandMapper.resolve("", null))
        assertNull(HaCommandMapper.resolve("sunshade", "5"))
    }

    @Test
    fun `supported commands set`() {
        val supported = HaCommandMapper.supportedCommands()
        assertTrue("ac_on" in supported)
        assertTrue("sunshade" in supported)
        assertTrue("sentry" !in supported)
    }
}