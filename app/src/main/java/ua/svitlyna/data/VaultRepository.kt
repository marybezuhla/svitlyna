package ua.svitlyna.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

data class VaultItem(
    val id: Long,
    val vaultFileName: String,
    val originalName: String,
    val originalPath: String,
    val originalBucketName: String,
    val originalBucketId: Long,
    val dateTaken: Long,
    val dateLocked: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val orientation: Int = 0,
    val title: String = "",
    val description: String = "",
    /** Svitlyna: the old file name while a renaming (sealNames) is being finished; else empty. */
    val previousName: String = "",
)

data class VaultMoveResult(
    val vaultedMedia: List<MediaImage>,
    val originalMedia: List<MediaImage>,
    val silentSuccess: Boolean = false,
    val indexWriteFailed: Boolean = false,
)

private data class VaultRestoreTarget(val uri: Uri, val path: String)

private const val VAULT_INDEX_CHECKPOINT_INTERVAL = 20
private const val VAULT_RECOVERED_BUCKET = "Recovered"
/** Svitlyna: a copy being written; never adopted, cleared at the next start. */
private const val PART = ".part_"
private const val INDEX_LABEL = "svitlyna-vault-index"
private val RANDOM_NAME = Regex("^[0-9a-f]{32}$")
/** One lock for the whole process: a recreated screen gets a new repository, never a second lock. */
private val INDEX_LOCK = Any()
private val VAULT_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "3gp", "3g2", "m4v",
    "mpeg", "mpg", "ts", "flv", "wmv", "mts", "m2ts"
)

class VaultRepository(private val context: Context) {
    private val vaultDir: File = File(context.filesDir, "vault").apply {
        if (!exists()) {
            mkdirs()
        }
        val nomedia = File(this, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    private val indexFile = AtomicFile(File(context.filesDir, "vault_index.json"))

    // Every index read-modify-write sequence runs under this lock so a slow batch
    // cannot overwrite an index update from a concurrent operation. Reentrant by
    // design: load calls nested inside a locked operation are safe.
    private val indexLock = INDEX_LOCK

    private val _vaultMedia = MutableStateFlow<List<MediaImage>>(emptyList())
    val vaultMedia: StateFlow<List<MediaImage>> = _vaultMedia.asStateFlow()

    init {
        // Svitlyna: decrypting the list must not hold up the screen.
        kotlin.concurrent.thread(name = "vault-load") { runCatching { loadVaultItems() } }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun loadVaultItems(): List<MediaImage> = synchronized(indexLock) {
        val items = readIndexStrict()
        if (items == null) {
            // The index exists but cannot be parsed right now. Keep the last known
            // list instead of flashing the vault as empty while recovery runs.
            return@synchronized _vaultMedia.value
        }
        val mediaList = items.mapNotNull { item ->
            val file = File(vaultDir, item.vaultFileName)
            if (file.exists()) {
                val uniqueId = if (item.id > 0) -item.id else item.id
                MediaImage(
                    id = uniqueId,
                    uri = VaultProvider.uriFor(context, file.name, item.mimeType),
                    name = item.originalName,
                    dateTaken = item.dateTaken,
                    width = item.width,
                    height = item.height,
                    path = "", // read only through the vault provider (decrypted), never as a raw file
                    bucketId = if (item.originalBucketId != 0L) item.originalBucketId else -1L,
                    bucketName = item.originalBucketName.ifBlank { "Locked" },
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else file.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                )
            } else {
                null
            }
        }
        _vaultMedia.value = mediaList
        mediaList
    }

    /**
     * Adopts files that exist in the vault directory but are missing from the index. These are
     * leftovers from vault moves that were interrupted before the index was persisted; without
     * this pass they would stay invisible to the app forever.
     *
     * Returns the number of files recovered. Aborts (and returns 0) when the index cannot be read,
     * so a corrupt index can never cause every file to be adopted and duplicates to be created.
     */
    suspend fun recoverOrphanedItems(): Int = indexLocked {
        val currentItems = (readIndexStrict() ?: return@indexLocked 0).toMutableList()
        // Svitlyna: finish or undo a renaming that was cut off (see sealNames), by exact names
        // kept in the list (never guessed), then clear the unfinished copies (".part_…").
        var repaired = false
        for ((i, item) in currentItems.withIndex()) {
            if (item.previousName.isEmpty()) continue
            val now = File(vaultDir, item.vaultFileName)
            val part = File(vaultDir, PART + item.vaultFileName)
            val old = File(vaultDir, item.previousName)
            if (!now.isFile && part.isFile && plainLength(part, item.vaultFileName) > 0) part.renameTo(now)
            currentItems[i] = when {
                now.isFile && plainLength(now, item.vaultFileName) > 0 -> {
                    // Finished: the old copy goes.
                    if (old.isFile) runCatching { old.delete() }
                    item.copy(previousName = "")
                }
                old.isFile && plainLength(old, item.previousName) > 0 -> {
                    // Undone: back to the old copy; a broken new one goes.
                    runCatching { now.delete() }
                    item.copy(vaultFileName = item.previousName, previousName = "")
                }
                // Neither can be read right now (e.g. the keystore is busy): tried again next start.
                else -> continue
            }
            repaired = true
        }
        (vaultDir.listFiles() ?: emptyArray()).filter { it.name.startsWith(PART) }.forEach { runCatching { it.delete() } }
        if (repaired && !persistIndexVerified(currentItems)) return@indexLocked 0
        val knownNames = currentItems.mapTo(mutableSetOf()) { it.vaultFileName }
        val usedIds = currentItems.mapTo(mutableSetOf()) { it.id }
        val orphans = (vaultDir.listFiles() ?: emptyArray())
            .filter { file ->
                file.isFile && !file.name.startsWith(".") && file.name !in knownNames && file.length() > 0 &&
                    // Only a file that decrypts to the end (authenticated) is adopted.
                    plainLength(file, file.name) > 0
            }
            .sortedBy { it.name }
        if (orphans.isEmpty()) {
            if (repaired) loadVaultItems()
            return@indexLocked 0
        }

        var recovered = 0
        for (file in orphans) {
            val parsedId = parseVaultFileId(file.name)
            val uniqueId = if (parsedId != null && parsedId !in usedIds) parsedId else generateUniqueId(currentItems)
            usedIds.add(uniqueId)
            val sniffed = sniffType(file)
            val isVideo = sniffed?.startsWith("video/") ?: isVideoFileName(file.name)
            currentItems.add(
                VaultItem(
                    id = uniqueId,
                    vaultFileName = file.name,
                    originalName = if (RANDOM_NAME.matches(file.name)) {
                        "recovered_$uniqueId." + (MimeTypeMap.getSingleton().getExtensionFromMimeType(sniffed ?: "") ?: if (isVideo) "mp4" else "jpg")
                    } else {
                        stripVaultPrefix(file.name)
                    },
                    originalPath = "",
                    originalBucketName = VAULT_RECOVERED_BUCKET,
                    originalBucketId = 0L,
                    dateTaken = file.lastModified(),
                    dateLocked = System.currentTimeMillis(),
                    width = 0,
                    height = 0,
                    isVideo = isVideo,
                    durationMs = 0L,
                    mimeType = sniffed ?: mimeTypeForVaultFile(file.name, isVideo),
                    sizeBytes = file.length(),
                    orientation = 0,
                    title = "",
                    description = "",
                )
            )
            recovered++
        }

        if (!persistIndexVerified(currentItems)) {
            return@indexLocked 0
        }
        loadVaultItems()
        recovered
    }

    suspend fun moveToVault(mediaList: List<MediaImage>): VaultMoveResult = indexLocked {
        // Svitlyna: if the list can't be read right now (e.g. the keystore is busy), nothing is
        // written: an empty list saved over it would lose every name, folder and date.
        val currentItems = (readIndexStrict() ?: return@indexLocked VaultMoveResult(emptyList(), emptyList(), indexWriteFailed = true)).toMutableList()
        val vaultedList = mutableListOf<MediaImage>()
        val originalList = mutableListOf<MediaImage>()
        val hasManagerAccess = hasAllFilesAccess()
        var indexWriteFailed = false
        var copiesSinceCheckpoint = 0

        for (item in mediaList) {
            val uniqueId = generateUniqueId(currentItems)
            // Svitlyna: the file's name in the vault says nothing (the real one is in the encrypted list).
            val vaultFileName = VaultCrypto.randomName()
            val targetFile = File(vaultDir, vaultFileName)
            // Written under a temporary name first: a copy cut off by a crash is never adopted.
            val partFile = File(vaultDir, PART + vaultFileName)

            val copySuccess = runCatching {
                // Svitlyna: the vault keeps files only encrypted (VaultCrypto).
                val copiedBytes = (runCatching { context.contentResolver.openInputStream(android.provider.MediaStore.setRequireOriginal(item.uri)) }.getOrNull() ?: context.contentResolver.openInputStream(item.uri))?.use { input ->
                    VaultCrypto.encrypt(context, input, partFile, vaultFileName)
                } ?: 0L
                // A truncated copy is worse than no copy: the original is only
                // deleted after this check passes.
                // …and it must decrypt back to the same length before the original may go.
                val readBack = plainLength(partFile, vaultFileName)
                copiedBytes > 0 && readBack == copiedBytes && (item.sizeBytes <= 0 || copiedBytes == item.sizeBytes) &&
                    partFile.renameTo(targetFile)
            }.getOrDefault(false)

            if (!copySuccess) {
                // Don't leave half-written bytes where orphan recovery could pick them up.
                runCatching { partFile.delete() }
                runCatching { targetFile.delete() }
            }

            if (copySuccess) {
                val vaultItem = VaultItem(
                    id = uniqueId,
                    vaultFileName = vaultFileName,
                    originalName = item.name,
                    originalPath = item.path,
                    originalBucketName = item.bucketName,
                    originalBucketId = item.bucketId,
                    dateTaken = item.dateTaken,
                    dateLocked = System.currentTimeMillis(),
                    width = item.width,
                    height = item.height,
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else targetFile.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                )
                currentItems.add(vaultItem)
                val media = MediaImage(
                    id = -uniqueId,
                    uri = VaultProvider.uriFor(context, targetFile.name, item.mimeType),
                    name = item.name,
                    dateTaken = item.dateTaken,
                    width = item.width,
                    height = item.height,
                    path = "", // read only through the vault provider (decrypted), never as a raw file
                    bucketId = if (item.bucketId != 0L) item.bucketId else -1L,
                    bucketName = item.bucketName.ifBlank { "Locked" },
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = targetFile.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                )
                vaultedList.add(media)
                originalList.add(item)
                copiesSinceCheckpoint++
            }

            // Persist the index periodically so a process kill mid-batch cannot orphan
            // already-copied files.
            if (copiesSinceCheckpoint >= VAULT_INDEX_CHECKPOINT_INTERVAL) {
                if (!writeIndex(currentItems)) {
                    indexWriteFailed = true
                    break
                }
                copiesSinceCheckpoint = 0
            }
        }

        if (!indexWriteFailed) {
            indexWriteFailed = !persistIndexVerified(currentItems)
        }

        // Originals are only ever removed after the index is safely on disk.
        var silentSuccess = false
        if (!indexWriteFailed && hasManagerAccess && vaultedList.isNotEmpty()) {
            // Attempt every delete. all{} short-circuits on the first failure and
            // would leave the remaining originals behind.
            val deleteResults = originalList.map { item -> deleteFromStorage(item) }
            if (deleteResults.all { it }) {
                silentSuccess = true
            }
        }

        loadVaultItems()
        VaultMoveResult(
            vaultedMedia = vaultedList,
            originalMedia = originalList,
            silentSuccess = silentSuccess,
            indexWriteFailed = indexWriteFailed
        )
    }

    private fun deleteFromStorage(item: MediaImage): Boolean {
        var deleted = false
        if (item.path.isNotBlank()) {
            val file = File(item.path)
            if (file.exists()) {
                val fDel = runCatching { file.delete() }.getOrDefault(false)
                val cDel = if (!fDel && file.exists()) runCatching { file.canonicalFile.delete() }.getOrDefault(false) else false
                deleted = fDel || cDel
            }
        }
        val mediaVolume = if (item.uri.authority == "media") {
            item.uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: "external"
        } else "external"
        val mediaStoreUri = if (item.uri.authority == "media") {
            item.uri.buildUpon().clearQuery().build()
        } else if (item.id > 0) {
            if (item.isVideo) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        } else {
            item.uri
        }
        val contentDeleted = runCatching {
            context.contentResolver.delete(mediaStoreUri, null, null) > 0
        }.getOrDefault(false)
        deleted = deleted || contentDeleted

        if (item.id > 0) {
            val table = if (item.isVideo) MediaStore.Video.Media.getContentUri(mediaVolume) else MediaStore.Images.Media.getContentUri(mediaVolume)
            val idDeleted = runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString())) > 0
            }.getOrDefault(false)
            deleted = deleted || idDeleted
        }

        if (item.uri != mediaStoreUri) {
            val genericDeleted = runCatching {
                context.contentResolver.delete(item.uri, null, null) > 0
            }.getOrDefault(false)
            deleted = deleted || genericDeleted
        }

        if (item.path.isNotBlank()) {
            val table = if (item.isVideo) MediaStore.Video.Media.getContentUri(mediaVolume) else MediaStore.Images.Media.getContentUri(mediaVolume)
            val pathDeleted = runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path)) > 0
            }.getOrDefault(false)
            deleted = deleted || pathDeleted
            runCatching {
                context.contentResolver.delete(MediaStore.Files.getContentUri(mediaVolume), "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
        }
        return deleted
    }

    suspend fun rollbackVault(vaultedItems: List<MediaImage>): Unit = indexLocked {
        val currentItems = (readIndexStrict() ?: return@indexLocked).toMutableList()
        val idsToRemove = vaultedItems.map { abs(it.id) }.toSet()

        currentItems.removeAll { item ->
            // Svitlyna: a copy is undone only while its original is still there; if the original
            // was already removed, the vault copy is the only one left and stays.
            if (item.id in idsToRemove && originalStillThere(item.originalPath)) {
                val file = File(vaultDir, item.vaultFileName)
                runCatching { file.delete() }
                true
            } else {
                false
            }
        }
        // Copies that never made it into the index: their originals were never removed (that only
        // happens after the index is safely written), so the copy goes.
        val indexed = (readIndexStrict() ?: return@indexLocked).map { it.vaultFileName }.toSet()
        for (media in vaultedItems) {
            val name = media.uri.lastPathSegment ?: continue
            if (name !in indexed && !name.contains('/') && !name.startsWith(".")) runCatching { File(vaultDir, name).delete() }
        }
        writeIndex(currentItems)
        loadVaultItems()
    }

    /** The old name and an unfinished copy of a renaming go with the item, so it never comes back. */
    private fun dropLeftovers(item: VaultItem) {
        if (item.previousName.isNotEmpty() && !item.previousName.contains('/')) runCatching { File(vaultDir, item.previousName).delete() }
        runCatching { File(vaultDir, PART + item.vaultFileName).delete() }
    }

    /** Unknown path: not known to be there, so the vault copy stays (a duplicate beats a loss). */
    private fun originalStillThere(path: String): Boolean = path.isNotBlank() && runCatching { File(path).exists() }.getOrDefault(false)

    suspend fun restoreFromVault(mediaList: List<MediaImage>): List<MediaImage> = indexLocked {
        val currentItems = (readIndexStrict() ?: return@indexLocked emptyList()).toMutableList()
        val restoredList = mutableListOf<MediaImage>()
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()

        val itemsToRestore = currentItems.filter { it.id in idsToRemove }
        for (item in itemsToRestore) {
            val vaultFile = File(vaultDir, item.vaultFileName)
            // Svitlyna: an entry whose file is missing (a renaming being finished) is left alone.
            if (!vaultFile.exists()) continue

            val restored = restoreFileToStorage(item, vaultFile)
                ?.takeIf { r ->
                    // The restored photo must be whole before the vault copy goes.
                    val want = plainLength(vaultFile, vaultFile.name)
                    val got = runCatching {
                        context.contentResolver.openInputStream(r.uri)!!.use { s -> var n = 0L; val b = ByteArray(65536); while (true) { val k = s.read(b); if (k < 0) break; n += k }; n }
                    }.getOrDefault(-1L)
                    (want > 0 && got == want).also { whole ->
                        // Not whole: the fresh copy (new, with its own name) goes; the vault copy stays.
                        if (!whole) runCatching {
                            if (r.uri.scheme == "file") File(r.path).delete() else context.contentResolver.delete(r.uri, null, null)
                        }
                    }
                }
            if (restored != null) {
                runCatching { vaultFile.delete() }
                dropLeftovers(item)
                currentItems.remove(item)
                restoredList.add(
                    MediaImage(
                        id = -item.id,
                        uri = restored.uri,
                        name = item.originalName,
                        dateTaken = item.dateTaken,
                        width = item.width,
                        height = item.height,
                        path = restored.path,
                        bucketId = item.originalBucketId,
                        bucketName = item.originalBucketName,
                        isVideo = item.isVideo,
                        durationMs = item.durationMs,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        orientation = item.orientation,
                        title = item.title,
                        description = item.description,
                    )
                )
            }
        }

        writeIndex(currentItems)
        loadVaultItems()
        restoredList
    }

    suspend fun deletePermanently(mediaList: List<MediaImage>): Unit = indexLocked {
        val currentItems = (readIndexStrict() ?: return@indexLocked).toMutableList()
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()

        currentItems.removeAll { item ->
            if (item.id in idsToRemove) {
                val vaultFile = File(vaultDir, item.vaultFileName)
                runCatching { vaultFile.delete() }
                dropLeftovers(item)
                true
            } else {
                false
            }
        }
        writeIndex(currentItems)
        loadVaultItems()
    }

    private fun restoreFileToStorage(item: VaultItem, vaultFile: File): VaultRestoreTarget? {
        return try {
            if (hasAllFilesAccess()) {
                val wanted = if (item.originalPath.isNotBlank()) {
                    val file = File(item.originalPath)
                    file.parentFile?.mkdirs()
                    file
                } else {
                    val fallbackDir = Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Svitlyna").apply { mkdirs() }
                    File(fallbackDir, item.originalName)
                }
                // Decrypted back; never over a file already there.
                val target = if (wanted.exists()) uniqueDestinationFile(wanted.parentFile!!, item.originalName) else wanted
                VaultCrypto.decrypting(context, vaultFile).use { input -> target.outputStream().use { input.copyTo(it) } }
                val destFile = target
                if (item.dateTaken > 0) {
                    destFile.setLastModified(item.dateTaken)
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(item.mimeType.ifBlank { null }),
                    null
                )
                VaultRestoreTarget(Uri.fromFile(destFile), destFile.absolutePath)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val relativePath = computeRelativePath(item.originalPath, item.isVideo)
                val nowSec = System.currentTimeMillis() / 1000
                val dateSec = if (item.dateTaken > 0) item.dateTaken / 1000 else nowSec
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
                    )
                    put(MediaStore.MediaColumns.DATE_ADDED, nowSec)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec)
                    put(MediaStore.MediaColumns.DATE_TAKEN, if (item.dateTaken > 0) item.dateTaken else dateSec * 1000)
                    if (!item.isVideo) {
                        put(MediaStore.Images.Media.ORIENTATION, item.orientation)
                    }
                    if (item.title.isNotBlank()) put(MediaStore.MediaColumns.TITLE, item.title)
                    if (item.isVideo) {
                        put(MediaStore.Video.VideoColumns.DURATION, item.durationMs)
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                }
                val newUri = context.contentResolver.insert(collection, values) ?: return null
                val outputStream = context.contentResolver.openOutputStream(newUri)
                if (outputStream == null) {
                    context.contentResolver.delete(newUri, null, null)
                    return null
                }
                outputStream.use { output ->
                    VaultCrypto.decrypting(context, vaultFile).use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec)
                if (context.contentResolver.update(newUri, values, null, null) <= 0) {
                    context.contentResolver.delete(newUri, null, null)
                    return null
                }
                VaultRestoreTarget(newUri, mediaStorePath(newUri))
            } else {
                @Suppress("DEPRECATION")
                val publicDir = if (item.originalPath.isNotBlank()) {
                    File(item.originalPath).parentFile ?: Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Svitlyna")
                } else {
                    Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Svitlyna")
                }.apply { mkdirs() }
                val destFile = uniqueDestinationFile(publicDir, item.originalName)
                VaultCrypto.decrypting(context, vaultFile).use { input -> destFile.outputStream().use { input.copyTo(it) } }
                if (item.dateTaken > 0) {
                    destFile.setLastModified(item.dateTaken)
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(item.mimeType.ifBlank { null }),
                    null
                )
                VaultRestoreTarget(Uri.fromFile(destFile), destFile.absolutePath)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Where a restore through MediaStore actually landed. */
    private fun mediaStorePath(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty()

    private fun computeRelativePath(originalPath: String, isVideo: Boolean): String {
        val defaultDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (originalPath.isNotBlank()) {
            val parent = File(originalPath).parentFile?.absolutePath.orEmpty()
            val emulatedPrefix = "/storage/emulated/0/"
            if (parent.startsWith(emulatedPrefix)) {
                val rel = parent.removePrefix(emulatedPrefix).trim('/')
                if (rel.isNotBlank()) {
                    val root = rel.substringBefore('/')
                    val allowed = if (isVideo) {
                        root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) ||
                                root.equals(Environment.DIRECTORY_MOVIES, ignoreCase = true)
                    } else {
                        root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) ||
                                root.equals(Environment.DIRECTORY_PICTURES, ignoreCase = true)
                    }
                    if (allowed) {
                        return "$rel/"
                    }
                }
            }
        }
        return "$defaultDir/Iris/"
    }

    private fun generateUniqueId(existing: List<VaultItem>): Long {
        var id = System.currentTimeMillis()
        val ids = existing.map { it.id }.toSet()
        while (id in ids || id <= 0) {
            id++
        }
        return id
    }

    private fun parseVaultFileId(fileName: String): Long? {
        val match = Regex("^(\\d{5,})_").find(fileName) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

    private fun stripVaultPrefix(fileName: String): String {
        val stripped = fileName.replace(Regex("^\\d{5,}_"), "")
        return stripped.ifBlank { fileName }
    }

    private fun isVideoFileName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in VAULT_VIDEO_EXTENSIONS

    private fun mimeTypeForVaultFile(fileName: String, isVideo: Boolean): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (isVideo) "video/mp4" else "image/jpeg"
    }

    /**
     * Svitlyna: vault files from 0.5.0 still carry the photo's name ("123_IMG_2024.jpg") and the list
     * was plain JSON. Each such file is re-encrypted under a random name, outside the lock (so the
     * gallery never waits for a long video). Then, under the lock: the list is saved naming the new
     * file, the new copy takes its name, and only then the old one goes. Any crash in between is
     * finished or undone by recoverOrphanedItems: never a missing copy, never a lasting second one.
     */
    suspend fun sealNames(): Int = withContext(Dispatchers.IO) {
        synchronized(indexLock) {
            val items = readIndexStrict() ?: return@withContext 0
            if (indexIsPlain() && !persistIndexVerified(items)) return@withContext 0
        }
        val failed = HashSet<String>()
        var done = 0
        while (true) {
            val legacy = synchronized(indexLock) {
                readIndexStrict()?.firstOrNull {
                    !RANDOM_NAME.matches(it.vaultFileName) && it.vaultFileName !in failed && File(vaultDir, it.vaultFileName).isFile
                }
            } ?: break
            val old = File(vaultDir, legacy.vaultFileName)
            val newName = VaultCrypto.randomName()
            val part = File(vaultDir, PART + newName)
            val copied = runCatching {
                val plain = VaultCrypto.decrypting(context, old).use { input -> VaultCrypto.encrypt(context, input, part, newName) }
                plain > 0 && plainLength(part, newName) == plain
            }.getOrDefault(false)
            val ok = copied && synchronized(indexLock) {
                val items = readIndexStrict() ?: return@synchronized false
                // Still the same file under the same name (not deleted or restored meanwhile), and the
                // new copy still there (another start may have cleared unfinished copies)?
                val current = items.firstOrNull { it.id == legacy.id && it.vaultFileName == legacy.vaultFileName }
                if (current == null || !old.isFile || !part.isFile) return@synchronized false
                val target = File(vaultDir, newName)
                // The list names the new file and remembers the old one, so a crash at any point
                // below is finished or undone exactly at the next start.
                val updated = items.map { if (it === current) it.copy(vaultFileName = newName, previousName = old.name) else it }
                if (!persistIndexVerified(updated) && readIndexStrict()?.any { it.vaultFileName == newName } != true) {
                    return@synchronized false
                }
                if (!part.renameTo(target)) {
                    // Back to the old name: both copies are still there.
                    persistIndexVerified(items)
                    return@synchronized false
                }
                runCatching { old.delete() }
                persistIndexVerified(updated.map { if (it.vaultFileName == newName) it.copy(previousName = "") else it })
                loadVaultItems()
                true
            }
            if (ok) {
                done++
            } else {
                failed += legacy.vaultFileName
                if (part.isFile && synchronized(indexLock) { readIndexStrict()?.none { it.vaultFileName == newName } == true }) {
                    runCatching { part.delete() }
                }
            }
            kotlinx.coroutines.yield()
        }
        done
    }

    private fun indexIsPlain(): Boolean = runCatching {
        val f = indexFile.baseFile
        f.exists() && f.inputStream().use { s ->
            var c = s.read()
            while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) c = s.read()
            c == '['.code
        }
    }.getOrDefault(false)

    private fun plainLength(file: File, name: String): Long = runCatching {
        VaultCrypto.decrypting(context, file, name).use { s ->
            var n = 0L
            val b = ByteArray(65536)
            while (true) { val r = s.read(b); if (r < 0) break; n += r }
            n
        }
    }.getOrDefault(-1L)

    /** The type of an adopted file from its first bytes (its name says nothing). */
    private fun sniffType(file: File): String? = runCatching {
        val head = ByteArray(16)
        val n = VaultCrypto.decrypting(context, file).use { s ->
            var got = 0
            while (got < head.size) { val r = s.read(head, got, head.size - got); if (r < 0) break; got += r }
            got
        }
        if (n < 12) return@runCatching null
        fun at(i: Int, s: String) = String(head, i, s.length, Charsets.ISO_8859_1) == s
        when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            at(1, "PNG") -> "image/png"
            at(0, "GIF8") -> "image/gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            at(4, "ftyp") -> {
                val brand = String(head, 8, 4, Charsets.ISO_8859_1)
                when (brand) {
                    "heic", "heix", "mif1", "msf1", "hevc" -> "image/heic"
                    "avif", "avis" -> "image/avif"
                    "qt  " -> "video/quicktime"
                    else -> "video/mp4"
                }
            }
            at(0, "\u001aEß£") -> "video/webm"
            else -> null
        }
    }.getOrNull()

    private suspend fun <T> indexLocked(block: () -> T): T =
        withContext(Dispatchers.IO) { synchronized(indexLock) { block() } }


    /** Returns null when the index exists but cannot be parsed. */
    private fun readIndexStrict(): List<VaultItem>? {
        return try {
            // openRead also finds a backup left by an interrupted write.
            val base = indexFile.baseFile
            // Empty only when there is truly no list (nor a backup of one); any other failure to
            // open it (no access, too many open files) is "unreadable", never "empty".
            if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) return emptyList()
            val raw = indexFile.openRead().use { it.readBytes() }
            // Svitlyna: the list is encrypted; a plain one (0.5.0) is read once and then sealed.
            val json = runCatching { String(VaultCrypto.open(context, raw, INDEX_LABEL), Charsets.UTF_8) }
                .getOrElse {
                    val plain = String(raw, Charsets.UTF_8)
                    check(plain.trimStart().startsWith("[")) { "vault index unreadable" }
                    plain
                }
            val array = JSONArray(json)
            val list = mutableListOf<VaultItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VaultItem(
                        id = obj.optLong("id"),
                        vaultFileName = obj.optString("vaultFileName"),
                        originalName = obj.optString("originalName"),
                        originalPath = obj.optString("originalPath"),
                        originalBucketName = obj.optString("originalBucketName"),
                        originalBucketId = obj.optLong("originalBucketId"),
                        dateTaken = obj.optLong("dateTaken"),
                        dateLocked = obj.optLong("dateLocked"),
                        width = obj.optInt("width"),
                        height = obj.optInt("height"),
                        isVideo = obj.optBoolean("isVideo"),
                        durationMs = obj.optLong("durationMs"),
                        mimeType = obj.optString("mimeType"),
                        sizeBytes = obj.optLong("sizeBytes"),
                        orientation = obj.optInt("orientation"),
                        title = obj.optString("title"),
                        description = obj.optString("description"),
                        previousName = obj.optString("previousName"),
                    )
                )
            }
            list
        } catch (e: Exception) {
            null
        }
    }

    private fun writeIndex(items: List<VaultItem>): Boolean {
        var stream: FileOutputStream? = null
        return try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("vaultFileName", item.vaultFileName)
                    put("originalName", item.originalName)
                    put("originalPath", item.originalPath)
                    put("originalBucketName", item.originalBucketName)
                    put("originalBucketId", item.originalBucketId)
                    put("dateTaken", item.dateTaken)
                    put("dateLocked", item.dateLocked)
                    put("width", item.width)
                    put("height", item.height)
                    put("isVideo", item.isVideo)
                    put("durationMs", item.durationMs)
                    put("mimeType", item.mimeType)
                    put("sizeBytes", item.sizeBytes)
                    put("orientation", item.orientation)
                    put("title", item.title)
                    put("description", item.description)
                    if (item.previousName.isNotEmpty()) put("previousName", item.previousName)
                }
                array.put(obj)
            }
            stream = indexFile.startWrite()
            stream.write(VaultCrypto.seal(context, array.toString().toByteArray(Charsets.UTF_8), INDEX_LABEL))
            indexFile.finishWrite(stream)
            true
        } catch (e: Exception) {
            runCatching { stream?.let { indexFile.failWrite(it) } }
            false
        }
    }

    /**
     * Writes the index and reads it back before callers are allowed to run destructive steps
     * (deleting originals). If this returns false, nothing that was copied may be moved again.
     */
    private fun persistIndexVerified(items: List<VaultItem>): Boolean {
        if (!writeIndex(items)) return false
        val readBack = readIndexStrict() ?: return false
        return readBack.size == items.size
    }
}
