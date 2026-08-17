package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurfaceElevated
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

/**
 * Раздел «Умный дом»: карточка HA-телеметрия (Home Assistant) — отправка
 * состояния авто в diplus2hass и приём команд из HA. Вынесен из
 * SettingsScreen.kt, чтобы не раздувать файл.
 */
@Composable
internal fun SmartHomeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionHeader(text = "Умный дом")
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = "HA-телеметрия",
                description = "Отправка состояния авто в diplus2hass и приём команд",
                checked = state.haEnabled,
                onCheckedChange = { viewModel.toggleHa(it) },
            )
            SettingChipRow(
                title = "Схема",
                description = "http — локальный, https — за reverse-proxy",
                options = listOf("http", "https"),
                selectedIndex = if (state.haHttps) 1 else 0,
                onSelect = { viewModel.toggleHaHttps(it == 1) },
            )
            SettingsTextField(
                label = "Адрес (host)",
                value = state.haHost,
                onValueChange = { viewModel.updateHaHost(it) },
                keyboardType = KeyboardType.Uri
            )
            SettingsTextField(
                label = "Порт",
                value = state.haPort,
                onValueChange = { viewModel.updateHaPort(it) },
                keyboardType = KeyboardType.Number
            )
            SettingsTextField(
                label = "Long-Lived Access Token",
                value = state.haToken,
                onValueChange = { viewModel.updateHaToken(it) },
                keyboardType = KeyboardType.Password,
                secret = true
            )
            SettingsTextField(
                label = "Имя автомобиля (car_name)",
                value = state.haCarName,
                onValueChange = { viewModel.updateHaCarName(it) },
                keyboardType = KeyboardType.Text
            )
            SettingActionRow(
                title = "Сохранить",
                buttonLabel = "Сохранить",
                onClick = { viewModel.saveHaSettings() },
                style = SettingButtonStyle.Primary,
                enabled = state.haHost.isNotBlank() && state.haToken.isNotBlank() && state.haCarName.isNotBlank(),
            )
            state.haSaveStatus?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
            SettingHint("Снапшоты — в /api/byd_diplus, команды — из /api/byd_diplus/commands")
        }
    }
}