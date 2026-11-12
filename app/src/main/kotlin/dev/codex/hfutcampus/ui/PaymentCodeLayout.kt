package dev.codex.hfutcampus

import kotlin.math.max
import kotlin.math.min

/** Pixel-based layout: preserve integer QR modules; hide optional barcode before losing QR.  */
object PaymentCodeLayout {
    fun fit(width: Int, height: Int, modules: Int, barWidth: Int, barHeight: Int, gap: Int): Fit {
        if (width <= 0 || height <= 0 || modules < 23 || modules > 185 || gap < 0) return Fit(
            0,
            0,
            0,
            0,
            0,
            false
        )
        val bar =
            barWidth > 0 && barHeight > 0 && barWidth <= width && height.toLong() >= barHeight + gap.toLong() + modules * 2L
        val used = if (bar) barHeight + gap else 0
        val scale = min(width, height - used) / modules
        if (scale < 2) return Fit(0, 0, 0, 0, 0, false)
        val side = modules * scale
        val top = (height - side - used) / 2
        return Fit(
            side,
            (width - side) / 2,
            top + used,
            if (bar) (width - barWidth) / 2 else 0,
            top,
            bar
        )
    }

    /** Wallet proportions. The QR cap is soft only when two pixels per module need more space.  */
    fun fitWallet(
        width: Int,
        height: Int,
        modules: Int,
        sourceBarWidth: Int,
        sourceBarHeight: Int,
        gap: Int,
        qrCap: Int,
        desiredBarHeight: Int
    ): Fit {
        if (width <= 0 || height <= 0 || modules < 23 || modules > 185 || gap < 0 || qrCap <= 0 || desiredBarHeight <= 0) return Fit(
            0,
            0,
            0,
            0,
            0,
            false
        )
        val bar =
            sourceBarWidth > 0 && sourceBarWidth <= width && sourceBarHeight > 0 && height.toLong() >= desiredBarHeight + gap.toLong() + modules * 2L
        val used = if (bar) desiredBarHeight + gap else 0
        val scale = min(min(width, height - used), max(qrCap, modules * 2)) / modules
        if (scale < 2) return Fit(0, 0, 0, 0, 0, false)
        val side = modules * scale
        val top = (height - side - used) / 2
        return Fit(
            side,
            (width - side) / 2,
            top + used,
            0,
            top,
            bar,
            if (bar) width else 0,
            if (bar) desiredBarHeight else 0
        )
    }

    class Fit internal constructor(
        val qrSide: Int,
        val qrX: Int,
        val qrY: Int,
        val barX: Int,
        val barY: Int,
        val showBar: Boolean,
        val barWidth: Int = 0,
        val barHeight: Int = 0
    )
}
