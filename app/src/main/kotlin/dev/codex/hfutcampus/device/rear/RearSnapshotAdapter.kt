package dev.codex.hfutcampus

import dev.codex.hfutcampus.CardSnapshot
import dev.codex.hfutcampus.RearSnapshotAdapter
import kotlin.math.max
import kotlin.math.min

/** Pure snapshot gate; no networking, persistence or Android services.  */
object RearSnapshotAdapter {
    private const val MAX_SAFE = 9007199254740991L
    const val MIN_REMAINING_MS: Long = 1000
    const val DISPLAY_GUARD_MS: Long = 1000
    fun hidden(instance: String?, revision: Long, test: Boolean, status: String?): Frame {
        return RearSnapshotAdapter.Frame(
            false,
            test,
            safeInstance(instance),
            "",
            "",
            status,
            max(0, revision),
            0,
            0,
            0
        )
    }

    private fun safeInstance(value: String?): String {
        return if (value != null && value.matches("[A-Za-z0-9_-]{16,80}".toRegex())) value else "invalid-instance"
    }

    fun from(
        state: CardSnapshot?, instance: String?, sharing: Boolean,
        trustedRealReader: Boolean, elapsedNow: Long, wallNow: Long
    ): Frame {
        val revision = if (state == null) 0 else state.revision
        val test = state != null && state.testMode
        if (!sharing) return hidden(instance, revision, test, "请在伴侣App开启背屏共享")
        if (!test && !trustedRealReader) return hidden(
            instance,
            revision,
            false,
            "请先在伴侣App批准背屏宿主"
        )
        if (state == null || !state.ready) return hidden(
            instance,
            revision,
            test,
            "暂时没有有效二维码，请在伴侣App准备付款"
        )
        if ((safeInstance(instance) != instance) || state.revision < 0 || state.revision > MAX_SAFE || elapsedNow < 0 || wallNow < 0 || state.expiresElapsed <= elapsedNow || state.expiresElapsed > MAX_SAFE || state.expiresAt <= wallNow || state.expiresAt > MAX_SAFE || state.remainingMs <= 0 || state.remainingMs > 60000 || state.code == null || state.code.isEmpty() || state.code.length > 2048) {
            return hidden(instance, revision, test, "二维码已过期或数据无效")
        }
        // Reserve time for IPC, bitmap assignment and the native host's next render tick.
        val left = min(
            state.remainingMs,
            min(state.expiresElapsed - elapsedNow, state.expiresAt - wallNow)
        ) - DISPLAY_GUARD_MS
        if (left < MIN_REMAINING_MS) return hidden(
            instance,
            revision,
            test,
            "二维码即将过期，请重新准备"
        )
        val label = if (state.label == null) "校园卡" else state.label.substring(
            0,
            min(40, state.label.length)
        )
        return RearSnapshotAdapter.Frame(
            true, test, instance, state.code, label, if (test) "测试码，不能付款" else "校园卡付款",
            revision, wallNow + left, elapsedNow + left, left
        )
    }

    class Frame internal constructor(
        val ready: Boolean,
        val testMode: Boolean,
        val instanceId: String?,
        val code: String?,
        val label: String?,
        val status: String?,
        val generation: Long,
        val expiresAt: Long,
        val expiresElapsed: Long,
        val remainingMs: Long
    )
}
