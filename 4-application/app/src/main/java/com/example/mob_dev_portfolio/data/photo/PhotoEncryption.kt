package com.example.mob_dev_portfolio.data.photo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM/NoPadding file encryption backed by AndroidKeyStore.
 *
 * Used by [SymptomPhotoRepository] to satisfy NFR-PA-02 ("Photos must
 * be stored in app-scoped internal storage, encrypted in line with
 * NFR-SE-01"). The same key is never reused for a second attachment:
 * GCM reuse under a fixed key leaks plaintext XORs, so every
 * encrypt/decrypt pair generates a fresh 12-byte IV and prepends it
 * to the ciphertext.
 *
 * Design decisions:
 *
 *  - **Separate alias** from [com.example.mob_dev_portfolio.data.security.DatabasePassphraseProvider]
 *    (`aura_photo_master_v1` vs `aura_db_master`): the threat model
 *    treats the photo key as rotatable without touching the DB, and
 *    sharing the alias would mean rotating one forces rotating the
 *    other. The `_v1` suffix leaves room for a future key-rotation
 *    story without stepping on existing installs.
 *
 *  - **Streaming I/O** (`CipherInputStream` / `CipherOutputStream`):
 *    photos are large enough (1-2 MB post-compression) that holding
 *    the full plaintext in memory during write is avoidable waste.
 *    The GCM auth tag lands at the tail of the stream, so
 *    `CipherInputStream` only validates at `close()` — callers must
 *    close the stream before trusting the bytes they read.
 *
 *  - **IV stored as a prefix**, not in a sidecar row. Anything else
 *    turns this into a two-write commit with its own set of
 *    tear-stripe failure modes; a single file per photo is the
 *    simplest thing that works.
 */
object PhotoEncryption {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "aura_photo_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Encrypts [plaintext] with a fresh IV and writes `[IV || ciphertext-with-tag]`
     * to [sink]. Does **not** close [sink] — the caller owns stream
     * lifetimes so it can chain this into a `use {}` block that also
     * manages the underlying `FileOutputStream`.
     */
    fun encryptToStream(plaintext: ByteArray, sink: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) {
            "Expected $IV_BYTES-byte GCM IV, got ${iv.size}"
        }
        sink.write(iv)
        CipherOutputStream(sink, cipher).use { cipherSink ->
            cipherSink.write(plaintext)
        }
    }

    /**
     * Decrypts a stream produced by [encryptToStream] and returns the
     * plaintext bytes. Reads the 12-byte IV prefix, then streams the
     * remainder through a [CipherInputStream] whose final `read()` (on
     * close) validates the GCM auth tag. A corrupted file surfaces as
     * an [javax.crypto.AEADBadTagException], which the caller should
     * treat as "photo unreadable" rather than a crash.
     */
    fun decryptFromStream(source: InputStream): ByteArray {
        val iv = ByteArray(IV_BYTES)
        var read = 0
        while (read < IV_BYTES) {
            val n = source.read(iv, read, IV_BYTES - read)
            require(n >= 0) { "Photo file truncated before IV was fully read" }
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(source, cipher).use { it.readBytes() }
    }

    private fun getOrCreateKey(): SecretKey {
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
}
