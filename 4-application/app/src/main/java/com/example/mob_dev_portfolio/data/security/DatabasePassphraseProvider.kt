package com.example.mob_dev_portfolio.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.securityStore: DataStore<Preferences> by preferencesDataStore(name = "aura_security")

enum class PassphraseOutcome {
    Reused,
    GeneratedFresh,
    GeneratedAfterCorruption,
}

/**
 * Result of a passphrase request — the raw key bytes plus how they
 * were obtained.
 *
 * Hand-rolled `equals` / `hashCode` because the auto-generated data-class
 * versions compare arrays by reference identity (Kotlin / JVM
 * limitation). Without these overrides two `PassphraseResult` values
 * holding the same bytes would test as unequal, which would silently
 * break any future caching or equality-based assertions.
 */
class PassphraseResult(
    val passphrase: ByteArray,
    val outcome: PassphraseOutcome,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PassphraseResult) return false
        if (!passphrase.contentEquals(other.passphrase)) return false
        if (outcome != other.outcome) return false
        return true
    }

    override fun hashCode(): Int {
        var result = passphrase.contentHashCode()
        result = 31 * result + outcome.hashCode()
        return result
    }
}

object DatabasePassphraseProvider {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "aura_db_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    private val WRAPPED_PASSPHRASE = stringPreferencesKey("wrapped_passphrase")
    private val WRAPPED_IV = stringPreferencesKey("wrapped_iv")

    private val lock = Any()
    @Volatile private var cached: ByteArray? = null

    suspend fun get(context: Context): ByteArray = obtain(context).passphrase

    suspend fun obtain(context: Context): PassphraseResult {
        cached?.let { return PassphraseResult(it, PassphraseOutcome.Reused) }
        val store = context.applicationContext.securityStore
        val prefs = store.data.firstOrNull()
        val existingCipher = prefs?.get(WRAPPED_PASSPHRASE)
        val existingIv = prefs?.get(WRAPPED_IV)
        val hadWrappedKey = existingCipher != null && existingIv != null
        if (hadWrappedKey) {
            val decrypted = runCatching {
                decryptWithKeystore(
                    Base64.decode(existingCipher, Base64.NO_WRAP),
                    Base64.decode(existingIv, Base64.NO_WRAP),
                )
            }.getOrNull()
            if (decrypted != null) {
                cached = decrypted
                return PassphraseResult(decrypted, PassphraseOutcome.Reused)
            }
        }
        val fresh = synchronized(lock) {
            cached ?: generatePassphrase().also { cached = it }
        }
        val (iv, ciphertext) = encryptWithKeystore(fresh)
        store.edit { editor ->
            editor[WRAPPED_PASSPHRASE] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            editor[WRAPPED_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
        }
        val outcome = if (hadWrappedKey) {
            PassphraseOutcome.GeneratedAfterCorruption
        } else {
            PassphraseOutcome.GeneratedFresh
        }
        return PassphraseResult(fresh, outcome)
    }

    private fun generatePassphrase(): ByteArray {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(PASSPHRASE_BYTES)
        random.nextBytes(bytes)
        return bytes
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv to ciphertext
    }

    private fun decryptWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
