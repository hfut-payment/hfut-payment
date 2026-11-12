package dev.codex.hfutcampus

import android.os.Handler
import org.json.JSONObject

/** Coalesces edits and keeps at most one save in flight. All callbacks run on the UI thread. */
internal class CommunityAutosave(
    private val main: Handler,
    private val form: CommunityForm,
    private val save: (JSONObject, (String?) -> Unit) -> Unit
) {
    private var waiting: ((Boolean) -> Unit)? = null
    private val dispatch = Runnable { flush() }
    private var dirty = false
    private var saving = false
    private var closed = false
    private var lastValid = true

    init {
        form.watchChanges {
            if (!closed) {
                dirty = true
                main.removeCallbacks(dispatch)
                main.postDelayed(dispatch, 550L)
            }
        }
    }

    fun flush(after: ((Boolean) -> Unit)? = null) {
        if (after != null) waiting = after
        main.removeCallbacks(dispatch)
        if (saving) return
        if (!dirty) {
            val action = waiting
            waiting = null
            action?.invoke(lastValid)
            return
        }
        dirty = false
        saving = true
        if (!closed) form.showStatus("正在保存…")
        save(form.values()) { problem ->
            saving = false
            lastValid = problem == null
            if (!closed && !dirty) form.showStatus(problem ?: "已自动保存", problem != null)
            if (!dirty || closed || waiting != null) flush()
            else main.postDelayed(dispatch, 550L)
        }
    }

    fun close() {
        closed = true
        waiting = null
        flush()
    }
}
