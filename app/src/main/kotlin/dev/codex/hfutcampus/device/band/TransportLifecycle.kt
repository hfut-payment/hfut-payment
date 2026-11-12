package dev.codex.hfutcampus

import dev.codex.hfutcampus.TransportLifecycle
import kotlin.math.max
import kotlin.math.min

/** Connection generations and bounded recovery policy, independent of Android.  */
internal class TransportLifecycle(var epoch: Long) {
    private var restarting = false
    private var attemptActive = true
    private var failures = 0
    private var retryAt: Long = 0
    private var healthySince: Long = -1
    fun accepts(value: Long): Boolean {
        return epoch == value
    }

    fun recovering(): Boolean {
        return restarting
    }

    fun restart(now: Long): Boolean {
        if (restarting && !attemptActive) return false
        restarting = true
        attemptActive = false
        epoch++
        healthySince = -1
        retryAt = now + min(60000, 1000L shl min(failures++, 6))
        return true
    }

    fun beginAttempt() {
        attemptActive = true
    }

    fun connected() {
        restarting = false
        attemptActive = true
    }

    fun delay(now: Long): Long {
        return max(0, retryAt - now)
    }

    fun confirmed(now: Long) {
        if (healthySince < 0) healthySince = now else if (now - healthySince >= 120000) failures = 0
    }
}
