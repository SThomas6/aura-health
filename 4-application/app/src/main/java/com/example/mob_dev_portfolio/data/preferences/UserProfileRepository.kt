package com.example.mob_dev_portfolio.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's self-reported identity details. Held locally; **never** sent to
 * the Gemini API in this raw form — see `AnalysisSanitizer`, which strips
 * names and replaces DOB with an age bucket before any network call.
 *
 * Nullable fields so a fresh install has no profile and the AI screen can
 * show a "set up your details" hint instead of silently sending blanks.
 */
data class UserProfile(
    val fullName: String? = null,
    val dateOfBirthEpochMillis: Long? = null,
)

open class UserProfileRepository(
    private val dataStore: DataStore<Preferences>,
) {

    open val profile: Flow<UserProfile> = dataStore.data.map { prefs ->
        UserProfile(
            fullName = prefs[FULL_NAME]?.takeIf { it.isNotBlank() },
            dateOfBirthEpochMillis = prefs[DOB_MILLIS],
        )
    }

    open suspend fun setFullName(name: String?) {
        dataStore.edit { editor ->
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) editor.remove(FULL_NAME)
            else editor[FULL_NAME] = trimmed
        }
    }

    open suspend fun setDateOfBirth(dobMillis: Long?) {
        dataStore.edit { editor ->
            if (dobMillis == null) editor.remove(DOB_MILLIS)
            else editor[DOB_MILLIS] = dobMillis
        }
    }

    companion object {
        private val FULL_NAME = stringPreferencesKey("user_full_name")
        private val DOB_MILLIS = longPreferencesKey("user_dob_millis")
    }
}
