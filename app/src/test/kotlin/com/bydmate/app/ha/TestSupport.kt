package com.bydmate.app.ha

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory SettingsDao для тестов без Room. */
class InMemorySettingsDao : SettingsDao {
    private val store = MutableStateFlow<Map<String, String?>>(emptyMap())
    override suspend fun get(key: String): String? = store.value[key]
    override fun observe(key: String): Flow<String?> = store.map { it[key] }
    override suspend fun set(setting: SettingEntity) {
        store.value = store.value + (setting.key to setting.value)
    }
    override suspend fun setAll(settings: List<SettingEntity>) {
        store.value = store.value + settings.associate { it.key to it.value }
    }
    override fun getAll(): Flow<List<SettingEntity>> =
        store.map { m -> m.map { (k, v) -> SettingEntity(k, v) } }
    override suspend fun delete(key: String) {
        store.value = store.value - key
    }
}

/** Заглушка LocalePreferences: тесты не трогают реальные prefs. */
val testLocalePreferences: LocalePreferences = mockk()