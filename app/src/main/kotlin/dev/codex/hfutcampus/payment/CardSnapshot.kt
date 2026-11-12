package dev.codex.hfutcampus

import kotlin.math.max
import kotlin.math.min

/** Code data never persists to disk. Always use CampusRuntime.current() before display.  */
class CardSnapshot(
    @JvmField val ready: Boolean,
    @JvmField val code: String,
    @JvmField val label: String,
    @JvmField val revision: Long,
    @JvmField val expiresAt: Long,
    @JvmField val remainingMs: Long,
    @JvmField val expiresElapsed: Long,
    @JvmField val testMode: Boolean,
    @JvmField val status: String,
    @JvmField val errorKind: String,
    @JvmField val recovered: Boolean
) {
    fun at(elapsed: Long, wall: Long): CardSnapshot {
        if (!ready) return this
        if (elapsed < 0 || wall < 0 || revision < 0 || code.isEmpty() || code.length > 2048 || remainingMs <= 0 || remainingMs > 60000) return hidden(
            max(0, revision),
            testMode,
            "二维码数据无效，请刷新。",
            "protocol"
        )
        val left = min(remainingMs, min(expiresElapsed - elapsed, expiresAt - wall))
        if (left <= 0 || left > 60000) return hidden(
            revision,
            testMode,
            "二维码已过期，请刷新。",
            "expired"
        )
        return CardSnapshot(
            true,
            code,
            label,
            revision,
            wall + left,
            left,
            expiresElapsed,
            testMode,
            status,
            errorKind,
            recovered
        )
    }

    companion object {
        @JvmStatic fun hidden(revision: Long, test: Boolean, status: String?, kind: String?): CardSnapshot {
            return CardSnapshot(false, "", "", revision, 0, 0, 0, test, status.orEmpty(), kind.orEmpty(), false)
        }
    }
}
