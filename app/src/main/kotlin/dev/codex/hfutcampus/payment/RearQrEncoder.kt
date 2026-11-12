package dev.codex.hfutcampus

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.floor

/** ZXing QR encoding and lossless grayscale PNG, entirely in memory. No Android bitmap dependency.  */
object RearQrEncoder {
    /** Crop only the original white border, then nearest-neighbor resize its pixels.
     * No QR encoder runs here: version, mask, data and every code module stay unchanged.
     * The source must be the original default four-module image, not a re-encoded variant.  */
    @Throws(Exception::class)
    @JvmStatic fun resizeForDisplay(source: Image, targetSide: Int, keepQuietModules: Int): Display {
        require(!(source == null || targetSide < 64 || targetSide > 512 || keepQuietModules < 1 || keepQuietModules > 4)) { "Invalid display resize" }
        val crop = (4 - keepQuietModules) * source.modulePixels
        val span = source.side - 2 * crop
        val modules = source.moduleCount - 8 + 2 * keepQuietModules
        require(!(span <= 0 || targetSide < modules * 2)) { "Display is too small" }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            for (y in 0..<targetSide) {
                zip.write(0)
                val sy = crop + (((2L * y + 1) * span) / (2L * targetSide)).toInt()
                for (x in 0..<targetSide) {
                    val sx = crop + (((2L * x + 1) * span) / (2L * targetSide)).toInt()
                    zip.write(
                        if (source.original.get(
                                sx / source.modulePixels,
                                sy / source.modulePixels
                            )
                        ) 0 else 255
                    )
                }
            }
        }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        val ihdr = DataOutputStream(header)
        ihdr.writeInt(targetSide)
        ihdr.writeInt(targetSide)
        ihdr.write(byteArrayOf(8, 0, 0, 0, 0))
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        out.flush()
        val quiet =
            floor(source.modulePixels.toDouble() * keepQuietModules * targetSide / span).toInt()
        return RearQrEncoder.Display(bytes.toByteArray(), targetSide, quiet, modules)
    }

    /** Fit an integer-module QR into maxSide; too-dense data is rejected rather than resampled.  */
    @JvmOverloads
    @Throws(Exception::class)
    @JvmStatic fun encodePng(code: String, maxSide: Int = 512): ByteArray? {
        return encode(code, maxSide).png
    }

    /** Module metadata lets native views fit whole modules to their actual measured bounds.  */
    @Throws(Exception::class)
    @JvmStatic fun encode(code: String, maxSide: Int): Image {
        require(!(maxSide < 64 || maxSide > 512)) { "QR image limit must be between 64 and 512" }
        require(!(code == null || code.isEmpty() || code.length > 2048)) { "Invalid QR data" }
        val bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(code))
            .remaining()
        require(bytes <= 2048) { "QR data too large" }
        val hints: MutableMap<EncodeHintType?, Any?> =
            EnumMap<EncodeHintType?, Any?>(EncodeHintType::class.java)
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        hints.put(EncodeHintType.MARGIN, 4)
        val modules = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, hints)
        val scale = maxSide / modules.getWidth()
        require(!(scale < 2 || modules.getWidth() != modules.getHeight())) { "QR is too dense" }
        val side = scale * modules.getWidth()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            for (y in 0..<side) {
                zip.write(0) // PNG filter: none.
                for (x in 0..<side) zip.write(if (modules.get(x / scale, y / scale)) 0 else 255)
            }
        }
        val png = ByteArrayOutputStream()
        val out = DataOutputStream(png)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        val ihdr = DataOutputStream(header)
        ihdr.writeInt(side)
        ihdr.writeInt(side)
        ihdr.write(byteArrayOf(8, 0, 0, 0, 0))
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        out.flush()
        val result = png.toByteArray()
        require(result.size <= 131072) { "QR image too large" }
        return RearQrEncoder.Image(result, side, modules.getWidth(), scale, modules)
    }

    @Throws(Exception::class)
    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        val name: ByteArray? = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(data)
        out.writeInt(data.size)
        out.write(name)
        out.write(data)
        out.writeInt(crc.getValue().toInt())
    }

    class Image internal constructor(
        @JvmField val png: ByteArray,
        @JvmField val side: Int,
        @JvmField val moduleCount: Int,
        @JvmField val modulePixels: Int,
        internal val original: BitMatrix
    )

    class Display internal constructor(
        @JvmField val png: ByteArray,
        @JvmField val side: Int,
        @JvmField val quietPixels: Int,
        @JvmField val moduleCount: Int
    )
}
