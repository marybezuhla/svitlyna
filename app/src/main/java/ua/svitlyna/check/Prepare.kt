package ua.svitlyna.check

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.Share
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Sizes for sharing: the photo as it is, or cut and scaled the way each network shows it best. */
enum class Preset(val ratioW: Int, val ratioH: Int, val width: Int, val longSide: Int) {
    ORIGINAL(0, 0, 0, 0),
    POST(4, 5, 1080, 0),
    SQUARE(1, 1, 1080, 0),
    STORY(9, 16, 1080, 0),
    TELEGRAM(0, 0, 0, 2560),
    ;

    /** The part of the photo kept (fractions of width and height), centred. */
    fun keep(w: Int, h: Int): RectF {
        if (ratioW == 0) return RectF(0f, 0f, 1f, 1f)
        val want = ratioW.toFloat() / ratioH
        val have = w.toFloat() / h
        return if (have > want) {
            val f = want / have
            RectF((1 - f) / 2, 0f, (1 + f) / 2, 1f)
        } else {
            val f = have / want
            RectF(0f, (1 - f) / 2, 1f, (1 + f) / 2)
        }
    }
}

/**
 * Makes the copy that is sent: blacked-out areas painted over (solid, not blurred, so nothing
 * can be brought back), cut and scaled for the chosen network, and encoded afresh, so the copy has
 * NO metadata at all (no location, no camera, no dates). The original is not touched.
 */
object Prepare {
    private const val MAX_SIDE = 4096

    /** Whether the original carries a location (read with "media location" access). */
    suspend fun hasLocation(context: Context, item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri))!!.use {
                ExifInterface(it).latLong != null
            }
        }.getOrDefault(false)
    }

    /**
     * [blackOut] areas are fractions of the upright photo (0..1). Returns a share intent, or null if
     * the photo couldn't be read.
     */
    suspend fun share(context: Context, item: MediaItem, preset: Preset, blackOut: List<RectF>): Intent? =
        withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, info, _ ->
                    val longer = max(info.size.width, info.size.height)
                    if (longer > MAX_SIDE) {
                        val s = MAX_SIDE.toFloat() / longer
                        d.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                    }
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.isMutableRequired = true
                }
            }.getOrNull() ?: return@withContext null

            val w = bitmap.width
            val h = bitmap.height
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.rgb(24, 24, 24); style = Paint.Style.FILL }
            // A little wider than the words, so no edge of a letter shows.
            val pad = max(w, h) * 0.004f
            for (r in blackOut) {
                canvas.drawRect(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad, paint)
            }

            val k = preset.keep(w, h)
            var out = Bitmap.createBitmap(
                bitmap,
                (k.left * w).roundToInt(),
                (k.top * h).roundToInt(),
                ((k.right - k.left) * w).roundToInt().coerceIn(1, w),
                ((k.bottom - k.top) * h).roundToInt().coerceIn(1, h),
            )
            val targetW = when {
                preset.width > 0 -> preset.width
                preset.longSide > 0 && max(out.width, out.height) > preset.longSide ->
                    (out.width * preset.longSide.toFloat() / max(out.width, out.height)).roundToInt()
                else -> out.width
            }
            if (targetW != out.width) {
                val targetH = (out.height * targetW.toFloat() / out.width).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(out, targetW, targetH, true)
                if (out !== bitmap) out.recycle()
                out = scaled
            }

            Share.cleanup(context)
            val dir = File(File(context.cacheDir, "share"), System.currentTimeMillis().toString()).apply { mkdirs() }
            val png = item.mime.equals("image/png", ignoreCase = true)
            val file = File(dir, "svitlyna-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            file.outputStream().use {
                out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
            }
            if (out !== bitmap) out.recycle()
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(if (png) "image/png" else "image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(null, uri)
            Intent.createChooser(send, null)
        }
}
