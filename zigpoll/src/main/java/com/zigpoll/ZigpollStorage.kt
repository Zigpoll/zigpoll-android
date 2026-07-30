package com.zigpoll

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Participant-id persistence. The anonymous id is the response-deduplication
 * key, so it must be stable across app launches.
 */
internal object ZigpollStorage {

    private const val PREFS = "zigpoll-sdk"
    private const val KEY_ANONYMOUS = "participant-id"
    private const val KEY_IDENTIFIED = "identified-id"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun saveIdentifiedId(id: String) {
        prefs.edit().putString(KEY_IDENTIFIED, id).apply()
    }

    fun loadIdentifiedId(): String? = prefs.getString(KEY_IDENTIFIED, null)

    fun clearIdentifiedId() {
        prefs.edit().remove(KEY_IDENTIFIED).apply()
    }

    fun anonymousId(): String {
        prefs.getString(KEY_ANONYMOUS, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").lowercase()
        prefs.edit().putString(KEY_ANONYMOUS, id).apply()
        return id
    }
}
