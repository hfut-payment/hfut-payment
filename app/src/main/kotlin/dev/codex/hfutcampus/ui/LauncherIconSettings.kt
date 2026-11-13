package dev.codex.hfutcampus

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

internal object LauncherIconSettings {
    fun create(context: Context): View {
        val icon = LauncherIcon(context)
        val container = CampusUi.column(context)
        val title = context.getString(R.string.launcher_icon_title)
        val heading = CampusUi.row(context).apply {
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            minimumHeight = dp(context, 56)
            background = CampusUi.ripple(context, CampusUi.WHITE, 18f)
            isFocusable = true
        }
        val label = CampusUi.text(context, title, 15, CampusUi.INK).also(CampusUi::medium)
        heading.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val chevron = CampusUi.icon(context, "chevron", CampusUi.MUTED).apply {
            rotation = 90f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(chevron, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)))
        container.addView(heading)

        val expanded = CampusUi.column(context).apply {
            visibility = View.GONE
            setPadding(dp(context, 14), 0, dp(context, 14), dp(context, 14))
        }
        val preview = ImageView(context).apply {
            setImageResource(icon.currentIcon)
            contentDescription = context.getString(R.string.launcher_icon_preview)
        }
        expanded.addView(preview, LinearLayout.LayoutParams(dp(context, 64), dp(context, 64)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 10)
        })
        expanded.addView(CampusUi.text(context, context.getString(R.string.launcher_icon_detail), 13, CampusUi.MUTED))

        fun apply(change: () -> Unit) {
            try {
                change()
                preview.setImageResource(icon.currentIcon)
                Toast.makeText(context, R.string.launcher_icon_changed, Toast.LENGTH_SHORT).show()
            } catch (_: RuntimeException) {
                Toast.makeText(context, R.string.launcher_icon_error, Toast.LENGTH_SHORT).show()
            }
        }
        val buttons = CampusUi.row(context)
        buttons.addView(CampusUi.button(context, context.getString(R.string.launcher_icon_shuffle), true, false,
            Runnable { apply(icon::shuffle) }), LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(CampusUi.button(context, context.getString(R.string.launcher_icon_restore), false, false,
            Runnable { apply(icon::restore) }), LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(context, 8)
        })
        expanded.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 12) })
        container.addView(expanded)
        fun updateHeading(open: Boolean) {
            chevron.rotation = if (open) -90f else 90f
            heading.contentDescription = context.getString(
                if (open) R.string.launcher_icon_collapse else R.string.launcher_icon_expand, title
            )
        }
        updateHeading(false)
        heading.setOnClickListener {
            val open = expanded.visibility != View.VISIBLE
            expanded.visibility = if (open) View.VISIBLE else View.GONE
            updateHeading(open)
        }
        return container
    }

    private fun dp(context: Context, value: Int) = CampusUi.dp(context, value.toFloat())
}
