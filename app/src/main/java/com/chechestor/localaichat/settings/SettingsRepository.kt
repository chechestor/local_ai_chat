package com.chechestor.localaichat.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Настройки приложения (F007, F009): общие для всех диалогов, сохраняются между запусками. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    /** «Размышления» модели; по умолчанию выключены. */
    val thinking: Flow<Boolean> = store.data.map { it[KEY_THINKING] ?: false }

    suspend fun thinkingNow(): Boolean = thinking.first()

    suspend fun setThinking(enabled: Boolean) {
        store.edit { it[KEY_THINKING] = enabled }
    }

    /** Активная языковая модель (F009 п. 3); копия — в `models.json`, чтобы пережить переустановку. */
    val activeModelId: Flow<String?> = store.data.map { it[KEY_ACTIVE] }

    suspend fun activeModelIdNow(): String? = activeModelId.first()

    suspend fun setActiveModelId(id: String?) {
        store.edit { if (id == null) it.remove(KEY_ACTIVE) else it[KEY_ACTIVE] = id }
    }

    /**
     * Отметка «модель грузится или отвечает» (F009 п. 9). Если процесс убили в это время (нехватка памяти),
     * при следующем запуске отметка ещё стоит — показываем сообщение об аварийном завершении.
     */
    suspend fun busyModelIdNow(): String? = store.data.first()[KEY_BUSY]

    suspend fun setBusyModelId(id: String?) {
        store.edit { if (id == null) it.remove(KEY_BUSY) else it[KEY_BUSY] = id }
    }

    private companion object {
        val KEY_THINKING = booleanPreferencesKey("thinking")
        val KEY_ACTIVE = stringPreferencesKey("active_model")
        val KEY_BUSY = stringPreferencesKey("busy_model")
    }
}
