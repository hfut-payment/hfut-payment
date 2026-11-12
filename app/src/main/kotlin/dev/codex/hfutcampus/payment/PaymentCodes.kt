package dev.codex.hfutcampus

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import java.util.Collections
import kotlin.math.max

/** Revision-scoped local rendering. Reading the payload never invokes an encoder.  */
class PaymentCodes {
    class QrPlan internal constructor(code: QRCode) {
        @JvmField val version: Int
        @JvmField val mask: Int
        @JvmField val mode: String

        init {
            version = code.getVersion().getVersionNumber()
            mask = code.getMaskPattern()
            mode = code.getMode().name
        }
    }

    private val cache: MutableMap<String?, Any?> =
        object : LinkedHashMap<String?, Any?>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String?, Any?>?): Boolean {
                return size > 8
            }
        }
    private var revision: Long = -1
    private var minRevision: Long = 0
    private var payload = ""

    private fun select(snapshot: CardSnapshot) {
        require(!(!snapshot.ready || snapshot.revision < minRevision)) { "No usable payment code" }
        if (snapshot.revision != revision || snapshot.code != payload) {
            cache.clear()
            revision = snapshot.revision
            payload = snapshot.code
        }
    }

    @Synchronized
    fun discardBefore(publishedRevision: Long) {
        minRevision = max(minRevision, publishedRevision)
        if (revision < publishedRevision) {
            cache.clear()
            payload = ""
            revision = -1
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun plan(snapshot: CardSnapshot): QrPlan {
        select(snapshot)
        var plan = cache.get("plan") as QrPlan?
        if (plan == null) {
            plan = QrPlan(
                Encoder.encode(
                    payload, ErrorCorrectionLevel.M,
                    Collections.singletonMap<EncodeHintType?, String?>(
                        EncodeHintType.CHARACTER_SET,
                        "UTF-8"
                    )
                )
            )
            cache.put("plan", plan)
        }
        return plan
    }

    @Synchronized
    @Throws(Exception::class)
    fun qr(snapshot: CardSnapshot, maxSide: Int): RearQrEncoder.Image {
        select(snapshot)
        val key = "qr:" + maxSide
        var image = cache.get(key) as RearQrEncoder.Image?
        if (image == null) {
            image = RearQrEncoder.encode(payload, maxSide)
            cache.put(key, image)
        }
        return image
    }

    @Synchronized
    @Throws(Exception::class)
    fun croppedQr(snapshot: CardSnapshot, side: Int): RearQrEncoder.Display {
        select(snapshot)
        val key = "display:" + side
        var display = cache.get(key) as RearQrEncoder.Display?
        if (display == null) {
            display = RearQrEncoder.resizeForDisplay(qr(snapshot, side), side, 1)
            cache.put(key, display)
        }
        return display
    }

    @Synchronized
    @Throws(Exception::class)
    fun barcode(snapshot: CardSnapshot, width: Int, height: Int): RearBarcodeEncoder.Image? {
        select(snapshot)
        val key = "bar:" + width + ":" + height
        if (!cache.containsKey(key)) {
            var image: RearBarcodeEncoder.Image? = null
            try {
                image = RearBarcodeEncoder.encode(payload, width, height)
            } catch (unsupported: IllegalArgumentException) { /* Barcode is optional. */
            }
            cache.put(key, image)
        }
        return cache.get(key) as RearBarcodeEncoder.Image?
    }

    companion object {
        const val PROFILE: String = "zxing-3.5.3-m-utf8"
        private val SHARED = PaymentCodes()
        @JvmStatic fun shared(): PaymentCodes {
            return SHARED
        }
    }
}
