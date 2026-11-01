package ua.svitlyna.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import java.io.File
import java.nio.ByteBuffer

/**
 * Shows vault files to the rest of the app (thumbnails, the viewer, the video player, sharing a
 * copy) as plain content, decrypted on the fly and only into memory: the decrypted photo is never
 * written to storage. Not exported: only Svitlyna can open it (and an app Svitlyna explicitly
 * hands one file to, when the person shares it).
 */
class VaultProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = "${context.packageName}.vault"
        fun uriFor(context: Context, vaultFileName: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(vaultFileName).build()

        private val thread by lazy { HandlerThread("vault-reader").apply { start() } }
    }

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        // Only a plain name inside the vault folder: no "..", no slashes.
        if (name.contains('/') || name.startsWith(".")) return null
        return File(File(ctx.filesDir, "vault"), name).takeIf { it.isFile }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("The vault is read only from here")
        val ctx = context ?: return null
        val f = file(uri) ?: throw java.io.FileNotFoundException()
        val channel = VaultCrypto.seekable(ctx, f)
        val storage = ctx.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = channel.size()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    try {
                        synchronized(channel) {
                            channel.position(offset)
                            val buf = ByteBuffer.wrap(data, 0, size)
                            var total = 0
                            while (buf.hasRemaining()) {
                                val n = channel.read(buf)
                                if (n <= 0) break
                                total += n
                            }
                            return total
                        }
                    } catch (e: Exception) {
                        // Tampered or damaged: reading fails instead of showing wrong bytes.
                        throw ErrnoException("read", OsConstants.EIO)
                    }
                }

                override fun onRelease() {
                    runCatching { channel.close() }
                }
            },
            Handler(thread.looper),
        )
    }

    override fun getType(uri: Uri): String? {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = runCatching { context?.let { VaultCrypto.seekable(it, f).use { c -> c.size() } } }.getOrNull()
        return MatrixCursor(cols).apply {
            addRow(cols.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> f.name.substringAfter('_')
                    OpenableColumns.SIZE -> size
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
