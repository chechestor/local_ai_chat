package com.chechestor.localaichat.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Настройки приложения (F007): общие для всех диалогов, сохраняются между запусками. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    /** «Размышления» модели; по умолчанию выключены. */
    val thinking: Flow<Boolean> = store.data.map { it[KEY_THINKING] ?: false }

    suspend fun thinkingNow(): Boolean = thinking.first()

    suspend fun setThinking(enabled: Boolean) {
        store.edit { it[KEY_THINKING] = enabled }
    }

    private companion object {
        val KEY_THINKING = booleanPreferencesKey("thinking")
    }
}
