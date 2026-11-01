package ua.svitlyna.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Everything the person set in the editor, applied the same way to the preview and the saved copy. */
data class Edits(
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    /** The longest side of the saved copy in pixels; 0: as large as the photo (the edit's own size). */
    val maxSide: Int = 0,
) {
    val turned: Boolean get() = rotation != 0 || flipH || flipV

    /** The colour adjustments as one matrix (the same formula as Iris Gallery's editor). */
    fun colorMatrix(): ColorMatrix {
        val colors = ColorMatrix().apply { setSaturation(saturation) }
        val shift = brightness * 255f
        val pivot = (1f - contrast) * 128f
        colors.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift + pivot + warmth * 36f,
                    0f, contrast, 0f, 0f, shift + pivot,
                    0f, 0f, contrast, 0f, shift + pivot - warmth * 36f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        return colors
    }

    val adjusted: Boolean get() = brightness != 0f || contrast != 1f || saturation != 1f || warmth != 0f
}

/**
 * The edited picture at full size: turned, straightened, cut, colours adjusted, then the drawing,
 * shapes, blackouts and text on top. Adapted from Iris Gallery's editor (Apache 2.0); saving is done
 * by [ua.svitlyna.media.SaveCopy], which keeps the original's date and metadata.
 */
object EditorRender {
    /** Longest side of the saved picture (memory); photos larger than this are scaled down. */
    private const val MAX_SIDE = 8192

    /**
     * The preview the editor works on: turned and straightened exactly as the saved copy will be,
     * at most [maxSide]. Drawing happens on this picture, so what is blacked out on the screen is
     * blacked out in the file.
     */
    suspend fun preview(context: Context, item: MediaItem, edits: Edits, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        decode(context, item, maxSide)?.let { straighten(turn(it, edits), edits.straighten) }
    }

    private fun straighten(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return source
        val rad = Math.toRadians(abs(angle).toDouble())
        val r = source.width.toFloat() / source.height.toFloat().coerceAtLeast(1f)
        val maxR = maxOf(r, 1f / r)
        val scale = (cos(rad) + sin(rad) * maxR).toFloat()
        val m = Matrix().apply {
            postRotate(angle, source.width / 2f, source.height / 2f)
            postScale(scale, scale, source.width / 2f, source.height / 2f)
        }
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return out
    }

    suspend fun render(context: Context, item: MediaItem, edits: Edits, session: EditorSession, previewWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        // Memory: a quarter of what the app may use, at 4 bytes a pixel, a few copies at a time.
        val budget = (Runtime.getRuntime().maxMemory() / 4 / 4 / 3).toDouble()
        val side = minOf(MAX_SIDE, kotlin.math.sqrt(budget).toInt().coerceAtLeast(2048))
        val raw = decode(context, item, side) ?: return@withContext null
        val source = straighten(turn(raw, edits), edits.straighten)
        val crop = RectF(session.crop)
        val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
        val w = (crop.width() * source.width).toInt().coerceIn(1, source.width - left)
        val h = (crop.height() * source.height).toInt().coerceIn(1, source.height - top)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source,
            android.graphics.Rect(left, top, left + w, top + h),
            android.graphics.Rect(0, 0, w, h),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { if (edits.adjusted) colorFilter = ColorMatrixColorFilter(edits.colorMatrix()) },
        )
        // Blur and pixel strength were chosen on the smaller preview: scaled so the saved copy looks the same.
        val factor = source.width.toFloat() / previewWidth.coerceAtLeast(1)
        source.recycle()
        renderBrushes(out, session.strokes.map { if (factor > 1f) it.copy(strength = (it.strength * factor).toInt()) else it }, crop)
        renderTextOverlays(out, session.textOverlays, crop)
        smaller(out, edits.maxSide)
    }

    /** Scaled down so the longer side is [maxSide] (never up), smoothly. */
    private fun smaller(picture: Bitmap, maxSide: Int): Bitmap {
        val longer = maxOf(picture.width, picture.height)
        if (maxSide <= 0 || longer <= maxSide) return picture
        val s = maxSide.toFloat() / longer
        val out = Bitmap.createScaledBitmap(picture, (picture.width * s).toInt().coerceAtLeast(1), (picture.height * s).toInt().coerceAtLeast(1), true)
        if (out !== picture) picture.recycle()
        return out
    }

    private fun decode(context: Context, item: MediaItem, maxSide: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, info, _ ->
            val longer = maxOf(info.size.width, info.size.height)
            if (longer > maxSide) {
                val s = maxSide.toFloat() / longer
                d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()?.also { bmp ->
        // An Ultra HDR photo's gain map would carry the original pixels (blacked-out parts included).
        if (android.os.Build.VERSION.SDK_INT >= 34) bmp.gainmap = null
    }

    private fun turn(raw: Bitmap, edits: Edits): Bitmap {
        if (!edits.turned) return raw
        val m = Matrix()
        if (edits.rotation != 0) m.postRotate(edits.rotation.toFloat())
        if (edits.flipH || edits.flipV) m.postScale(if (edits.flipH) -1f else 1f, if (edits.flipV) -1f else 1f)
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    // Brush strokes and text, from Iris Gallery's editor (Apache 2.0).

    private fun renderBrushes(target: Bitmap, strokes: List<BrushStroke>, crop: RectF) {
        if (strokes.isEmpty()) return
        val canvas = Canvas(target)
        strokes.forEach { stroke ->
            if (stroke.shape != ShapeType.NONE && stroke.points.size >= 2) {
                val p1 = stroke.points.first()
                val p2 = stroke.points.last()
                val x1 = (p1.x - crop.left) / crop.width() * target.width
                val y1 = (p1.y - crop.top) / crop.height() * target.height
                val x2 = (p2.x - crop.left) / crop.width() * target.width
                val y2 = (p2.y - crop.top) / crop.height() * target.height
                val strokeWidth = stroke.radius / crop.width() * target.width
                renderShape(canvas, stroke, x1, y1, x2, y2, strokeWidth)
                return@forEach
            }
            val path = Path()
            var visible = false
            stroke.points.forEach { point ->
                val x = (point.x - crop.left) / crop.width() * target.width
                val y = (point.y - crop.top) / crop.height() * target.height
                if (!visible) {
                    path.moveTo(x, y)
                    path.lineTo(x + .1f, y + .1f)
                    visible = true
                } else {
                    path.lineTo(x, y)
                }
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.radius / crop.width() * target.width
            }
            if (stroke.effect == BrushEffect.COLOR) {
                paint.color = stroke.color
                canvas.drawPath(path, paint)
            } else {
                val effect = if (stroke.effect == BrushEffect.PIXELATE) createPixelatedBitmap(target, stroke.strength)
                else createBlurredBitmap(target, stroke.strength)
                paint.shader = BitmapShader(effect, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
                effect.recycle()
            }
        }
    }

    private fun renderTextOverlays(target: Bitmap, overlays: List<TextOverlay>, crop: RectF) {
        if (overlays.isEmpty()) return
        val canvas = Canvas(target)
        overlays.forEach { overlay ->
            val x = (overlay.x - crop.left) / crop.width() * target.width
            val y = (overlay.y - crop.top) / crop.height() * target.height
            val pxSize = (overlay.textSizeRatio / crop.width()) * target.width
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = overlay.color
                textSize = pxSize.coerceAtLeast(16f)
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(overlay.text, 0, overlay.text.length, bounds)
            val padX = pxSize * 0.4f
            val padY = pxSize * 0.25f
            val bg = RectF(x - bounds.width() / 2f - padX, y - bounds.height() / 2f - padY, x + bounds.width() / 2f + padX, y + bounds.height() / 2f + padY)
            canvas.save()
            canvas.rotate(overlay.rotationDeg, x, y)
            if (overlay.bgColor != 0) {
                canvas.drawRoundRect(bg, pxSize * 0.25f, pxSize * 0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlay.bgColor })
            }
            canvas.drawText(overlay.text, x, y + bounds.height() / 2f - bounds.bottom, textPaint)
            canvas.restore()
        }
    }
}
