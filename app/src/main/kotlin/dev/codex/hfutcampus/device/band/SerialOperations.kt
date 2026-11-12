package dev.codex.hfutcampus

import dev.codex.hfutcampus.SerialOperations.Work
import dev.codex.hfutcampus.SerialOperations
import java.util.ArrayDeque
import java.util.function.BooleanSupplier

/** Serializes asynchronous SDK mutations. Completion must be dispatched to the
 * same thread as submit; a stale task must finish before a replacement starts.  */
internal class SerialOperations {
    internal fun interface Work {
        fun start(finished: Runnable?)
    }

    private val pending = ArrayDeque<Runnable?>()
    private var active = false
    fun submit(needed: BooleanSupplier, work: Work) {
        pending.add(Runnable {
            val finished = booleanArrayOf(false)
            val done = Runnable {
                if (!finished[0]) {
                    finished[0] = true
                    active = false
                    drain()
                }
            }
            try {
                if (needed.getAsBoolean()) work.start(done) else done.run()
            } catch (error: RuntimeException) {
                done.run()
            }
        })
        drain()
    }

    private fun drain() {
        if (active || pending.isEmpty()) return
        active = true
        pending.remove()!!.run()
    }
}
