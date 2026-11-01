package ua.svitlyna.search

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The search database, encrypted with SQLCipher. Its key is random, made on the phone, and kept
 * only wrapped by a key of Android's Keystore (which never leaves the phone's secure hardware).
 * Everything in it can be made again from the photos, so if the key is ever lost (the phone was
 * reset, the lock screen changed in some way), the database is simply started anew.
 */
object IndexDb {
    private const val NAME = "search.db"
    private const val ALIAS = "svitlyna_search"
    private const val KEY_FILE = "search.key"

    @Volatile
    private var helper: SupportSQLiteOpenHelper? = null

    @Synchronized
    fun index(context: Context): TextIndex = TextIndex(open(context.applicationContext))

    @Synchronized
    private fun open(context: Context): SupportSQLiteDatabase {
        helper?.let { return it.writableDatabase }
        System.loadLibrary("sqlcipher")
        // Started anew only when the old key is surely gone (never for a passing error: hours of
        // reading would be lost for nothing). Other errors go up; the next attempt tries again.
        val key = try {
            readKey(context)
        } catch (e: Exception) {
            if (!lost(e)) throw e
            null
        } ?: run {
            context.deleteDatabase(NAME)
            newKey(context)
        }
        val made = build(context, key)
        val db = try {
            made.writableDatabase
        } catch (e: Exception) {
            runCatching { made.close() }
            if (e.message?.contains("not a database", ignoreCase = true) != true) throw e
            // The file belongs to a key that is gone: started anew.
            context.deleteDatabase(NAME)
            val fresh = build(context, newKey(context))
            helper = fresh
            return fresh.writableDatabase.also(::secure)
        } finally {
            key.fill(0)
        }
        helper = made
        secure(db)
        return db
    }

    /** Deleted rows are overwritten, not just marked free. */
    private fun secure(db: SupportSQLiteDatabase) {
        runCatching { db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() } }
    }

    /** The key file is damaged, or the Keystore key that wraps it is gone. */
    private class LostKey(why: String) : Exception(why)

    /**
     * The wrapped key can never be opened again, as opposed to Keystore failing for a moment (it
     * reports many passing troubles as general key errors, so those are NOT taken as lost).
     */
    private fun lost(e: Exception): Boolean = e is LostKey ||
        e is javax.crypto.AEADBadTagException ||
        e is android.security.keystore.KeyPermanentlyInvalidatedException

    private fun build(context: Context, key: ByteArray): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(TextIndex.VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = TextIndex.create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = TextIndex.recreate(db)
            })
            .build()
        // A random key needs no stretching: given raw (x'…'), so opening is quick.
        val raw = "x'" + key.joinToString("") { "%02x".format(it) } + "'"
        return SupportOpenHelperFactory(raw.toByteArray(Charsets.US_ASCII)).create(config)
    }

    /** Everything read from photos is gone: the database and its key. */
    @Synchronized
    fun erase(context: Context) {
        val app = context.applicationContext
        runCatching { helper?.close() }
        helper = null
        app.deleteDatabase(NAME)
        File(app.noBackupFilesDir, KEY_FILE).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun wrapping(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun newKey(context: Context): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping()) }
        val sealed = cipher.iv + cipher.doFinal(key)
        File(context.noBackupFilesDir, KEY_FILE).writeBytes(byteArrayOf(cipher.iv.size.toByte()) + sealed)
        return key
    }

    private fun readKey(context: Context): ByteArray? {
        val file = File(context.noBackupFilesDir, KEY_FILE)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) throw LostKey("empty key file")
        val ivSize = bytes[0].toInt()
        if (ivSize <= 0 || bytes.size < 1 + ivSize + 16) throw LostKey("damaged key file")
        // The wrapping key is gone (e.g. the app's data in Keystore was cleared): the file can't be opened.
        if (!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS)) throw LostKey("no wrapping key")
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapping(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)
    }
}
