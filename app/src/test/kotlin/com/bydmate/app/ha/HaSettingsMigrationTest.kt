package com.bydmate.app.ha

import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Миграция legacy `ha_url` в раздельные ключи (host/port/https). */
class HaSettingsMigrationTest {

    private val dao = InMemorySettingsDao()
    private val repo = SettingsRepository(dao, testLocalePreferences)

    @Test
    fun `migrate legacy http url`() = runTest {
        dao.set(SettingEntity(SettingsRepository.KEY_HA_URL, "http://192.168.1.10:8123"))
        repo.migrateLegacyHaUrlIfNeeded()
        assertEquals("192.168.1.10", repo.getHaHost())
        assertEquals(8123, repo.getHaPort())
        assertFalse(repo.isHaHttps())
        assertTrue(dao.get(SettingsRepository.KEY_HA_URL) == null)
    }

    @Test
    fun `migrate legacy https custom port`() = runTest {
        dao.set(SettingEntity(SettingsRepository.KEY_HA_URL, "https://host:8443"))
        repo.migrateLegacyHaUrlIfNeeded()
        assertEquals("host", repo.getHaHost())
        assertEquals(8443, repo.getHaPort())
        assertTrue(repo.isHaHttps())
    }

    @Test
    fun `migrate no scheme defaults http 8123`() = runTest {
        dao.set(SettingEntity(SettingsRepository.KEY_HA_URL, "mwhome.local"))
        repo.migrateLegacyHaUrlIfNeeded()
        assertEquals("mwhome.local", repo.getHaHost())
        assertEquals(8123, repo.getHaPort())
        assertFalse(repo.isHaHttps())
    }

    @Test
    fun `no legacy url is no-op`() = runTest {
        repo.migrateLegacyHaUrlIfNeeded()
        assertEquals("", repo.getHaHost())
        assertTrue(dao.get(SettingsRepository.KEY_HA_URL) == null)
    }

    @Test
    fun `already migrated does not overwrite`() = runTest {
        dao.set(SettingEntity(SettingsRepository.KEY_HA_URL, "http://old:1111"))
        dao.set(SettingEntity(SettingsRepository.KEY_HA_HOST, "newhost"))
        dao.set(SettingEntity(SettingsRepository.KEY_HA_PORT, "8443"))
        repo.migrateLegacyHaUrlIfNeeded()
        assertEquals("newhost", repo.getHaHost())
        assertEquals(8443, repo.getHaPort())
        assertTrue(dao.get(SettingsRepository.KEY_HA_URL) != null) // legacy остаётся — host уже был задан
    }
}