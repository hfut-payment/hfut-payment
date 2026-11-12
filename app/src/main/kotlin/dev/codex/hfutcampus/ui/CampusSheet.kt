package dev.codex.hfutcampus

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.function.Predicate

/** One inset-aware modal design for forms, choices, permissions, confirmations and errors.  */
class CampusSheet internal constructor(
    val activity: Activity,
    title: String?,
    description: String?,
    opened: Runnable,
    closed: Runnable
) {
    val dialog: Dialog
    val body: LinearLayout
    private val footer: LinearLayout
    private val panel: LinearLayout
    private val root: FrameLayout
    private val name: TextView
    private val detail: TextView
    private val close: View
    private var busy = false
    private var dismissed = Runnable {}

    init {
        dialog = Dialog(activity, R.style.Theme_Campus)
        dialog.getWindow()!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.getWindow()!!.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.getWindow()!!.setDimAmount(.26f)
        CampusInsets.configure(requireNotNull(dialog.window))
        dialog.window?.setWindowAnimations(R.style.Animation_CampusSheet)
        root = FrameLayout(activity)
        root.setOnClickListener { if (!busy) dismiss() }
        panel = CampusUi.column(activity)
        panel.setPadding(dp(22f), dp(10f), dp(22f), dp(20f))
        panel.setBackground(CampusUi.shape(activity, CampusUi.WHITE, 28f))
        panel.setOnClickListener(View.OnClickListener { v: View? -> })
        val viewport = ScrollView(activity)
        viewport.setFillViewport(false)
        viewport.setBackground(CampusUi.shape(activity, CampusUi.WHITE, 28f))
        viewport.setClipToOutline(true)
        viewport.addView(panel)
        val place = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        place.setMargins(dp(10f), dp(10f), dp(10f), dp(8f))
        root.addView(viewport, place)
        val handle = View(activity)
        handle.setBackground(CampusUi.shape(activity, -0x272015, 2f))
        val handlePlace = LinearLayout.LayoutParams(dp(32f), dp(4f))
        handlePlace.gravity = Gravity.CENTER_HORIZONTAL
        handlePlace.bottomMargin = dp(12f)
        panel.addView(handle, handlePlace)
        val heading = CampusUi.row(activity)
        name = CampusUi.text(activity, title, 22, CampusUi.INK)
        CampusUi.medium(name)
        heading.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        close = CampusUi.iconButton(activity, "close", "关闭", Runnable { dismiss() })
        heading.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        panel.addView(heading)
        detail = CampusUi.text(activity, description, 13, CampusUi.MUTED).apply {
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(4f), 0, dp(16f))
            visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        panel.addView(detail)
        body = CampusUi.column(activity)
        panel.addView(body, LinearLayout.LayoutParams(-1, -2))
        footer = CampusUi.row(activity)
        val foot = LinearLayout.LayoutParams(-1, -2)
        foot.topMargin = dp(18f)
        panel.addView(footer, foot)
        dialog.setContentView(root)
        // The entire panel can scroll, including actions: large text, landscape and IME
        // cannot strand controls below a separately measured fixed header/footer.
        CampusInsets.attach(
            root,
            true,
            CampusInsets.Listener { left: Int, top: Int, right: Int, bottom: Int ->
                root.setPadding(
                    left,
                    top,
                    right,
                    bottom
                )
            })
        dialog.setOnShowListener(OnShowListener { d: DialogInterface? ->
            opened.run()
            root.requestApplyInsets()
        })
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
            dismissed.run()
            closed.run()
        })
    }

    internal fun replace(title: String, description: String, loading: Boolean = false) {
        if (isShowing) TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(220))
        setWarning(false)
        busy = loading
        dialog.setCancelable(!loading)
        close.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        name.text = title
        detail.text = description
        detail.visibility = if (description.isEmpty()) View.GONE else View.VISIBLE
        body.removeAllViews()
        footer.removeAllViews()
        if (loading) {
            val indicator = CircularProgressIndicator(activity).apply {
                isIndeterminate = true
                setIndicatorColor(CampusUi.PRIMARY)
            }
            body.addView(indicator, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12f)
                bottomMargin = dp(12f)
            })
        }
        name.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    internal fun setWarning(warning: Boolean) {
        name.setTextColor(if (warning) CampusUi.ERROR else CampusUi.INK)
        detail.setTextColor(if (warning) CampusUi.ERROR else CampusUi.MUTED)
    }

    private fun dp(value: Float): Int {
        return CampusUi.dp(activity, value)
    }

    fun onDismiss(action: Runnable) {
        dismissed = action
    }

    val isShowing: Boolean
        get() = dialog.isShowing()

    fun show() {
        dialog.show()
        dialog.getWindow()!!.setLayout(-1, -1)
        root.requestApplyInsets()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun action(label: String?, primary: Boolean, danger: Boolean, action: Runnable): TextView {
        val button = CampusUi.button(activity, label, primary, danger, action)
        val params = LinearLayout.LayoutParams(0, -2, 1f)
        if (footer.getChildCount() > 0) params.leftMargin = dp(10f)
        footer.addView(button, params)
        return button
    }

    fun paragraph(message: String?, color: Int): TextView {
        val v = CampusUi.text(activity, message, 14, color)
        v.setLineSpacing(dp(4f).toFloat(), 1f)
        v.setPadding(0, dp(8f), 0, dp(8f))
        body.addView(v)
        return v
    }

    fun field(view: View?) {
        val place = LinearLayout.LayoutParams(-1, -2)
        place.topMargin = dp(4f)
        place.bottomMargin = dp(8f)
        body.addView(view, place)
    }

    internal fun status(icon: String, title: String, subtitle: String, active: Boolean) {
        val card = CampusUi.row(activity).apply {
            setPadding(dp(14f), dp(15f), dp(14f), dp(15f))
            background = CampusUi.shape(activity, if (active) CampusUi.TINT else CampusUi.BG, 18f)
        }
        val leading = CampusUi.icon(activity, icon, if (active) CampusUi.PRIMARY else CampusUi.MUTED)
        card.addView(leading, LinearLayout.LayoutParams(dp(26f), dp(26f)))
        val words = CampusUi.column(activity)
        words.addView(CampusUi.text(activity, title, 17, CampusUi.INK).also(CampusUi::medium))
        if (subtitle.isNotEmpty()) words.addView(CampusUi.text(activity, subtitle, 13, CampusUi.MUTED).apply {
            setPadding(0, dp(4f), 0, 0)
        })
        card.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f) })
        field(card)
    }

    /** True accepts the change; false puts the switch back without firing again. */
    fun toggle(title: String, subtitle: String, checked: Boolean, changed: Predicate<Boolean>) {
        val line = CampusUi.row(activity).apply {
            background = CampusUi.shape(activity, CampusUi.BG, 16f)
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
        }
        val words = CampusUi.column(activity)
        words.addView(CampusUi.text(activity, title, 16, CampusUi.INK).also(CampusUi::medium))
        if (subtitle.isNotEmpty()) {
            words.addView(CampusUi.text(activity, subtitle, 12, CampusUi.MUTED).apply {
                setPadding(0, dp(5f), dp(8f), 0)
            })
        }
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val control = CampusUi.toggle(activity, title, checked)
        var restoring = false
        control.setOnCheckedChangeListener { _, value ->
            if (!restoring && !changed.test(value)) {
                restoring = true
                control.isChecked = !value
                restoring = false
            }
        }
        line.addView(control, LinearLayout.LayoutParams(-2, -2))
        body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
    }

    fun row(
        icon: String,
        title: String?,
        subtitle: String?,
        selected: Boolean,
        danger: Boolean,
        action: Runnable
    ) {
        val line = CampusUi.row(activity)
        line.setPadding(dp(10f), dp(13f), dp(10f), dp(13f))
        line.setMinimumHeight(dp(66f))
        val accent = if (danger) CampusUi.ERROR else CampusUi.PRIMARY
        val leading: View = CampusUi.icon(activity, icon, accent)
        leading.setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        leading.setBackground(
            CampusUi.shape(
                activity,
                if (danger) CampusUi.ERROR_TINT else CampusUi.TINT,
                14f
            )
        )
        line.addView(leading, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        val words = CampusUi.column(activity)
        val label = CampusUi.text(activity, title, 16, if (danger) CampusUi.ERROR else CampusUi.INK)
        CampusUi.medium(label)
        words.addView(label)
        if (subtitle != null && !subtitle.isEmpty()) {
            val detail = CampusUi.text(activity, subtitle, 12, CampusUi.MUTED)
            detail.setPadding(0, dp(5f), 0, 0)
            detail.setLineSpacing(dp(2f).toFloat(), 1f)
            words.addView(detail)
        }
        val wordPlace = LinearLayout.LayoutParams(0, -2, 1f)
        wordPlace.leftMargin = dp(14f)
        wordPlace.rightMargin = dp(8f)
        line.addView(words, wordPlace)
        line.addView(
            CampusUi.icon(
                activity,
                if (selected) "check" else "chevron",
                if (selected) CampusUi.PRIMARY else -0x675c4b
            ), LinearLayout.LayoutParams(dp(20f), dp(20f))
        )
        line.setBackground(
            CampusUi.ripple(
                activity,
                if (selected) CampusUi.TINT else CampusUi.BG,
                16f
            )
        )
        line.setOnClickListener(View.OnClickListener { v: View? -> action.run() })
        line.setFocusable(true)
        if (selected) line.setContentDescription(title + "，已选择")
        val place = LinearLayout.LayoutParams(-1, -2)
        place.topMargin = dp(8f)
        body.addView(line, place)
    }
}
