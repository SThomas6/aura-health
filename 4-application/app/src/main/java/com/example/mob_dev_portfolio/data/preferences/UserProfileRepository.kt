package com.example.mob_dev_portfolio.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Coarse biological-sex bucket fed into the Gemini prompt alongside the
 * age range. Kept deliberately small so it maps cleanly onto the analysis
 * prompt without exposing free-text self-description. A user who prefers
 * not to say picks [PreferNotToSay]; the prompt drops the field entirely
 * rather than sending "prefer not to say", which the model sometimes
 * echoes back in its response.
 */
enum class BiologicalSex(val storageKey: String, val displayLabel: String) {
    Female(storageKey = "female", displayLabel = "Female"),
    Male(storageKey = "male", displayLabel = "Male"),
    Intersex(storageKey = "intersex", displayLabel = "Intersex"),
    PreferNotToSay(storageKey = "prefer_not_to_say", displayLabel = "Prefer not to say");

    companion object {
        fun fromStorageKey(key: String?): BiologicalSex? =
            entries.firstOrNull { it.storageKey == key }
    }
}

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
    /**
     * Coarse self-reported biological sex. Nullable so a fresh install
     * surfaces the setup flow, and [BiologicalSex.PreferNotToSay] distinct
     * from null so a user's explicit "don't use this" is remembered and
     * isn't re-asked every session.
     */
    val biologicalSex: BiologicalSex? = null,
)

/**
 * DataStore-backed repository for the user's self-reported profile
 * (full name, date of birth, biological sex).
 *
 * Stored in the **unencrypted** Preferences DataStore. The threat model
 * here is intentional: full name and DOB are not transmitted in raw form
 * — `AnalysisSanitizer` redacts the name and converts DOB to a coarse
 * age bucket before any Gemini call — and the Preferences file is
 * already inside per-user FBE on Android 7+. Promoting these fields
 * into the encrypted Room database would force opening SQLCipher just
 * to render the Settings header, which lengthens cold start for no
 * additional protection.
 *
 * `open` for test-double purposes — see [UiPreferencesRepository] KDoc.
 */
open class UserProfileRepository(
    private val dataStore: DataStore<Preferences>,
) {

    open val profile: Flow<UserProfile> = dataStore.data.map { prefs ->
        UserProfile(
            fullName = prefs[FULL_NAME]?.takeIf { it.isNotBlank() },
            dateOfBirthEpochMillis = prefs[DOB_MILLIS],
            biologicalSex = BiologicalSex.fromStorageKey(prefs[BIOLOGICAL_SEX]),
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

    open suspend fun setBiologicalSex(sex: BiologicalSex?) {
        dataStore.edit { editor ->
            if (sex == null) editor.remove(BIOLOGICAL_SEX)
            else editor[BIOLOGICAL_SEX] = sex.storageKey
        }
    }

    companion object {
        private val FULL_NAME = stringPreferencesKey("user_full_name")
        private val DOB_MILLIS = longPreferencesKey("user_dob_millis")
        private val BIOLOGICAL_SEX = stringPreferencesKey("user_biological_sex")
    }
}
