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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.securityStore: DataStore<Preferences> by preferencesDataStore(name = "aura_security")

/**
 * What happened during a single [DatabasePassphraseProvider.obtain] call.
 * Surfaced so the caller (typically [com.example.mob_dev_portfolio.AppContainer])
 * can take corrective action when the previously-stored wrap couldn't be
 * decrypted (e.g. delete the now-unreachable encrypted DB and start fresh,
 * since the old data is unrecoverable without its key).
 */
enum class PassphraseOutcome {
    /** Existing wrapped key was successfully decrypted by the Keystore. */
    Reused,

    /** No wrapped key existed yet (fresh install or post-uninstall). */
    GeneratedFresh,

    /** A wrapped key was present but unwrap failed — Keystore rotated or device wiped. */
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

/**
 * Source of the SQLCipher passphrase used to open the encrypted Room
 * database. Implements the "key wrapped by Keystore" pattern recommended
 * by Android security:
 *
 *   1. A 32-byte random passphrase is generated once with [SecureRandom].
 *   2. That passphrase is encrypted under an AES-GCM key held inside the
 *      AndroidKeyStore HAL (alias `aura_db_master`). The wrapped bytes +
 *      IV are written to a dedicated unencrypted DataStore.
 *   3. On every cold start we attempt to unwrap. Success ⇒ reuse the same
 *      passphrase, so the existing DB still opens. Failure ⇒ generate a
 *      fresh passphrase and report [PassphraseOutcome.GeneratedAfterCorruption]
 *      so the caller can wipe the now-orphaned encrypted DB.
 *
 * Why a separate alias from [com.example.mob_dev_portfolio.data.photo.PhotoEncryption]
 * (`aura_db_master` vs `aura_photo_master_v1`)? The DB key needs to last
 * the lifetime of the install; the photo key is rotatable. Sharing aliases
 * would couple their rotation stories.
 *
 * Concurrency: the in-memory [cached] field uses a double-checked
 * `synchronized` block to serialise the rare cold-start race where two
 * coroutines both reach [obtain] before the first has stashed the result.
 *
 * Failure modes the caller cares about:
 *   - **Keystore wiped** (factory reset, "remove biometric/PIN" with
 *     `setUserAuthenticationRequired`, OS rotation): unwrap fails, we
 *     return [PassphraseOutcome.GeneratedAfterCorruption], existing DB
 *     becomes unreadable. Caller deletes and recreates.
 *   - **DataStore corrupted**: unwrap returns null because the wrapped
 *     bytes are gone. Same outcome as Keystore wipe — fresh passphrase.
 */
object DatabasePassphraseProvider {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "aura_db_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    private val WRAPPED_PASSPHRASE = stringPreferencesKey("wrapped_passphrase")
    private val WRAPPED_IV = stringPreferencesKey("wrapped_iv")

    /**
     * Coroutine-aware lock. We need to hold the critical section across
     * suspending DataStore reads + Keystore calls, and a JVM `synchronized`
     * block can't hold across a suspend. `Mutex` is the right tool.
     */
    private val mutex = Mutex()
    @Volatile private var cached: ByteArray? = null

    /**
     * Resolve the database passphrase. Idempotent within a single process
     * (subsequent calls return the cached bytes with [PassphraseOutcome.Reused]).
     * The first call performs the unwrap → fall-back-generate dance
     * described in the object KDoc.
     *
     * Concurrency contract: a fast-path cache read is taken without
     * the lock for hot calls; on miss the entire unwrap-or-generate
     * sequence is held under [mutex] so two concurrent first callers
     * can never both read DataStore as empty, both generate competing
     * passphrases, and race to write back different wrapped keys.
     * (Pre-fix the cache check was outside the lock and the lock body
     * only covered the in-memory cache update.)
     */
    suspend fun obtain(context: Context): PassphraseResult {
        // Fast path: cache already populated by a prior caller in the
        // same process. Safe outside the lock because [cached] is
        // volatile and the slow path publishes it before releasing
        // the mutex.
        cached?.let { return PassphraseResult(it, PassphraseOutcome.Reused) }

        return mutex.withLock {
            // Re-check inside the lock: a parallel caller may have
            // populated the cache while we were waiting on the mutex.
            cached?.let { return@withLock PassphraseResult(it, PassphraseOutcome.Reused) }

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
                    return@withLock PassphraseResult(decrypted, PassphraseOutcome.Reused)
                }
            }

            val fresh = generatePassphrase()
            val (iv, ciphertext) = encryptWithKeystore(fresh)
            store.edit { editor ->
                editor[WRAPPED_PASSPHRASE] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                editor[WRAPPED_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
            }
            // Publish to the in-memory cache only AFTER persistence
            // succeeds. If `encryptWithKeystore` or `store.edit` throws,
            // the mutex unwinds without a stale cache, the lazy DB
            // getter retries cleanly, and the next cold start sees no
            // wrapped key in DataStore — matching reality. Pre-fix the
            // order was `cached = fresh` first, which on a transient
            // Keystore failure would let the in-flight process keep
            // using `fresh` while the next cold start regenerated and
            // quarantined the encrypted DB → silent data loss.
            cached = fresh
            val outcome = if (hadWrappedKey) {
                PassphraseOutcome.GeneratedAfterCorruption
            } else {
                PassphraseOutcome.GeneratedFresh
            }
            PassphraseResult(fresh, outcome)
        }
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
