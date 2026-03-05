package com.mallowlink.app.util.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// AudioEncryption
//
// All audio and transcript data is encrypted at rest using AES-256-GCM.
// Keys are stored in the Android Keystore (hardware-backed where available).
//
// Key-per-session: each RecordingSession gets its own Keystore alias so
// deleting a session's key cryptographically erases the data independently.
// ─────────────────────────────────────────────────────────────────────────────

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALGORITHM     = KeyProperties.KEY_ALGORITHM_AES
private const val BLOCK_MODE        = KeyProperties.BLOCK_MODE_GCM
private const val PADDING           = KeyProperties.ENCRYPTION_PADDING_NONE
private const val TRANSFORMATION    = "AES/GCM/NoPadding"
private const val KEY_SIZE_BITS     = 256
private const val GCM_TAG_BITS      = 128
private const val IV_SIZE_BYTES     = 12

@Singleton
class AudioEncryption @Inject constructor() {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** Creates a new hardware-backed AES-256-GCM key for [alias]. */
    fun createKey(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setUserAuthenticationRequired(false)  // no biometric guard on key; file-level protection
            .setRandomizedEncryptionRequired(true)
            .build()

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    /** Permanently destroys the Keystore key for [alias], rendering the ciphertext unreadable. */
    fun destroyKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun getKey(alias: String): SecretKey =
        (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey

    /**
     * Returns an [OutputStream] that encrypts writes to [dest].
     * Prepends a 12-byte IV to the file.
     */
    fun encryptingOutputStream(alias: String, dest: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(alias))
        val iv = cipher.iv  // random 12 bytes
        dest.write(iv)
        return CipherOutputStream(dest, cipher)
    }

    /**
     * Returns an [InputStream] that decrypts reads from [src].
     * Reads the 12-byte IV prepended by [encryptingOutputStream].
     */
    fun decryptingInputStream(alias: String, src: InputStream): InputStream {
        val iv = ByteArray(IV_SIZE_BYTES)
        src.read(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(src, cipher)
    }

    /** Encrypt a small byte array (e.g. transcript text) in-memory. */
    fun encryptBytes(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(alias))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /** Decrypt a small byte array encrypted with [encryptBytes]. */
    fun decryptBytes(alias: String, blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = blob.copyOfRange(IV_SIZE_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Securely delete a file by overwriting before deletion. */
    fun secureDelete(file: File) {
        if (!file.exists()) return
        try {
            file.outputStream().use { out ->
                val zeros = ByteArray(4096)
                var remaining = file.length()
                while (remaining > 0) {
                    val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, chunk)
                    remaining -= chunk
                }
                out.flush()
            }
        } finally {
            file.delete()
        }
    }
}
