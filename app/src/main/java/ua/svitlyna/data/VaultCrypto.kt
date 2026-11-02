package ua.svitlyna.data

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * Encryption of the vault (Svitlyna): every file in it is stored encrypted with Tink's streaming
 * AES-256-GCM (in 1 MB authenticated segments, so a photo can be shown and a video played from any
 * point without decrypting all of it). The key is random, made on the phone and kept wrapped by a key
 * of Android's Keystore; it never leaves the phone. A file is bound to its name in the vault, so
 * files can't be swapped unnoticed.
 */
object VaultCrypto {
    private const val KEYSET = "vault_keyset"
    private const val PREFS = "vault_keyset_prefs"
    private const val MASTER = "android-keystore://svitlyna_vault"

    @Volatile
    private var aead: StreamingAead? = null

    @Synchronized
    private fun aead(context: Context): StreamingAead {
        aead?.let { return it }
        StreamingAeadConfig.register()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET, PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
            .withMasterKeyUri(MASTER)
            .build()
        // Svitlyna: never a vault key kept in the clear (Tink falls back to that when the Keystore
        // fails): then the vault doesn't work rather than work unprotected.
        if (!manager.isUsingKeystore) {
            // The key written in the clear is dropped while the vault is still empty, so the next
            // start makes a new one wrapped by the Keystore (otherwise this would fail forever).
            val empty = java.io.File(context.filesDir, "vault").listFiles()?.none { it.isFile && !it.name.startsWith(".") } ?: true
            if (empty) context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEYSET).commit()
            error("Android Keystore unavailable")
        }
        val handle = manager.keysetHandle
        @Suppress("DEPRECATION")
        val made = handle.getPrimitive(StreamingAead::class.java)
        aead = made
        return made
    }

    /**
     * Encrypts all of [input] into [target]; returns how many plain bytes were written. [name] is the
     * file's final name in the vault (it may be written under a temporary name first).
     */
    fun encrypt(context: Context, input: InputStream, target: File, name: String = target.name): Long {
        var count = 0L
        val file = FileOutputStream(target)
        aead(context).newEncryptingStream(file, name.toByteArray()).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                count += n
            }
        }
        // On the storage for real (the last segment is written on close) before the original may go.
        runCatching { java.io.RandomAccessFile(target, "rw").use { it.fd.sync() } }
        return count
    }

    /** The plain content of a vault [file], read from the start. */
    fun decrypting(context: Context, file: File, name: String = file.name): InputStream =
        aead(context).newDecryptingStream(FileInputStream(file), name.toByteArray())

    /** Small data (the vault's list) encrypted with the same key, bound to [label]. */
    fun seal(context: Context, plain: ByteArray, label: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        aead(context).newEncryptingStream(out, label.toByteArray()).use { it.write(plain) }
        return out.toByteArray()
    }

    fun open(context: Context, sealed: ByteArray, label: String): ByteArray =
        aead(context).newDecryptingStream(java.io.ByteArrayInputStream(sealed), label.toByteArray()).use { it.readBytes() }

    /** A new file name that says nothing about the photo: 32 random hex digits. */
    fun randomName(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** The plain content of a vault [file] with random access (for showing and playing). */
    fun seekable(context: Context, file: File): SeekableByteChannel =
        aead(context).newSeekableDecryptingChannel(FileInputStream(file).channel, file.name.toByteArray())
}
