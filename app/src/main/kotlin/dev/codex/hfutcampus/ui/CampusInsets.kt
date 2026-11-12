package dev.codex.hfutcampus

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/** System bars, cutouts and keyboards are measured, never approximated with a fixed top gap.  */
internal object CampusInsets {
    fun configure(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setStatusBarColor(Color.TRANSPARENT)
        window.setNavigationBarColor(Color.TRANSPARENT)
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else flags =
            flags or (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        window.getDecorView().setSystemUiVisibility(flags)
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val attributes = window.getAttributes()
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.setAttributes(attributes)
        }
        window.setSoftInputMode(if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun attach(root: View, keyboard: Boolean, listener: Listener) {
        root.setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener { view: View?, insets: WindowInsets? ->
            var left: Int
            var top: Int
            var right: Int
            var bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe =
                    insets!!.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = safe.left
                top = safe.top
                right = safe.right
                bottom = safe.bottom
                if (keyboard) bottom =
                    max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                left = insets!!.getSystemWindowInsetLeft()
                top = insets.getSystemWindowInsetTop()
                right = insets.getSystemWindowInsetRight()
                bottom = insets.getSystemWindowInsetBottom()
                if (Build.VERSION.SDK_INT >= 28) {
                    val cutout = insets.getDisplayCutout()
                    if (cutout != null) {
                        left = max(left, cutout.getSafeInsetLeft())
                        top = max(top, cutout.getSafeInsetTop())
                        right = max(right, cutout.getSafeInsetRight())
                        bottom = max(bottom, cutout.getSafeInsetBottom())
                    }
                }
            }
            listener.changed(left, top, right, bottom)
            insets
        })
        root.requestApplyInsets()
    }

    fun page(window: Window, root: View) {
        configure(window)
        attach(
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
    }

    internal fun interface Listener {
        fun changed(left: Int, top: Int, right: Int, bottom: Int)
    }
}
