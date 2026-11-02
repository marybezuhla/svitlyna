package ua.svitlyna.media

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/** QR and bar codes found on a picture (ZXing, on the phone). Each text once, in reading order. */
object Codes {
    private val FORMATS = listOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
    )

    fun read(picture: Bitmap): List<String> {
        val w = picture.width
        val h = picture.height
        val px = IntArray(w * h)
        picture.getPixels(px, 0, w, 0, 0, w, h)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to FORMATS,
            DecodeHintType.TRY_HARDER to true,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))
        val found = runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints).toList() }
            .getOrNull().orEmpty()
            .ifEmpty { listOfNotNull(runCatching { MultiFormatReader().decode(bitmap, hints) }.getOrNull()) }
        return found.sortedBy { r -> r.resultPoints?.minOfOrNull { it.y + it.x / 10f } ?: 0f }
            .map { it.text }.filter { it.isNotBlank() }.distinct()
    }

    /** A web address the person may choose to open (shown in full first). */
    fun isLink(text: String) = text.startsWith("https://", true) || text.startsWith("http://", true)
}
