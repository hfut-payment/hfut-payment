package dev.codex.hfutcampus

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Lossless Code 128 of the original payment string. No extraction, truncation or persistence.  */
object RearBarcodeEncoder {
    @Throws(Exception::class)
    @JvmStatic fun encodePng(code: String, maxWidth: Int, height: Int): ByteArray? {
        return encode(code, maxWidth, height).png
    }

    @Throws(Exception::class)
    @JvmStatic fun encode(code: String, maxWidth: Int, height: Int): Image {
        return encode(code, maxWidth, height, false)
    }

    /** Already rotated on the phone: no bitmap transform or fractional scaling on the band.  */
    @Throws(Exception::class)
    @JvmStatic fun encodePortrait(code: String, maxLength: Int, barWidth: Int): Image {
        return encode(code, maxLength, barWidth, true)
    }

    @Throws(Exception::class)
    private fun encode(code: String, maxWidth: Int, height: Int, portrait: Boolean): Image {
        require(!(maxWidth < 64 || maxWidth > 1024 || height < 32 || height > 256)) { "Invalid barcode dimensions" }
        require(!(code == null || code.isEmpty() || code.length > 80)) { "Unsupported barcode data" }
        // ZXing reserves some extended characters as FNC instructions. Reject them and all
        // control characters instead of silently changing the meaning of the payment string.
        for (i in 0..<code.length) require(!(code.get(i).code < 32 || code.get(i).code > 126)) { "Unsupported barcode characters" }
        val hints: MutableMap<EncodeHintType?, Any?> =
            EnumMap<EncodeHintType?, Any?>(EncodeHintType::class.java)
        hints.put(EncodeHintType.MARGIN, 20) // Total: ten narrow modules at each side.
        val modules = Code128Writer().encode(code, BarcodeFormat.CODE_128, 0, 1, hints)
        val scale = maxWidth / modules.getWidth()
        require(scale >= 2) { "Barcode is too wide" }
        val length = modules.getWidth() * scale
        val width = if (portrait) height else length
        val rows = if (portrait) length else height
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            for (y in 0..<rows) {
                zip.write(0)
                for (x in 0..<width) zip.write(
                    if (modules.get(
                            (if (portrait) y else x) / scale,
                            0
                        )
                    ) 0 else 255
                )
            }
        }
        val png = ByteArrayOutputStream()
        val out = DataOutputStream(png)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        val ihdr = DataOutputStream(header)
        ihdr.writeInt(width)
        ihdr.writeInt(rows)
        ihdr.write(byteArrayOf(8, 0, 0, 0, 0))
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        out.flush()
        val bytes = png.toByteArray()
        require(bytes.size <= 131072) { "Barcode image too large" }
        return RearBarcodeEncoder.Image(bytes, width, rows, scale)
    }

    @Throws(Exception::class)
    private fun chunk(out: DataOutputStream, type: String, bytes: ByteArray) {
        val name: ByteArray? = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(bytes)
        out.writeInt(bytes.size)
        out.write(name)
        out.write(bytes)
        out.writeInt(crc.getValue().toInt())
    }

    class Image internal constructor(
        @JvmField val png: ByteArray,
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val modulePixels: Int
    )
}
