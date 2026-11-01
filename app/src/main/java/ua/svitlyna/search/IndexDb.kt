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
        val key = runCatching { readKey(context) }.getOrNull() ?: run {
            // No key, or it can't be unwrapped any more: a new, empty database.
            context.deleteDatabase(NAME)
            newKey(context)
        }
        val made = try {
            build(context, key).also { it.writableDatabase }
        } catch (e: Exception) {
            // The file doesn't open with this key (left from before): started anew.
            context.deleteDatabase(NAME)
            build(context, newKey(context)).also { it.writableDatabase }
        }
        helper = made
        return made.writableDatabase
    }

    private fun build(context: Context, key: ByteArray): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = TextIndex.create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = TextIndex.create(db)
            })
            .build()
        return SupportOpenHelperFactory(key.copyOf()).create(config)
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
        val ivSize = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapping(), GCMParameterSpec(128, iv)) }
        return cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize)
    }
}
