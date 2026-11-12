package dev.codex.hfutcampus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.MaterialShapeDrawable
import kotlin.math.abs
import kotlin.math.roundToInt

/** A page and its entry share one rounded container throughout the transition. */
class CampusNavigation(
    private val activity: Activity,
    private val host: FrameLayout,
    first: View,
    private val changed: Runnable
) {
    private data class Anchor(val bounds: Rect, val sourceBounds: Rect, val color: Int, val corner: Float)
    private data class Page(val view: View, val layer: View, val source: View?, val anchor: Anchor?, val popped: Runnable)
    private val pages = mutableListOf(Page(first, first, null, null, Runnable {}))
    private var activeAnchor: Anchor? = null
    private var entry: EntryImage? = null
    private val colors = ArgbEvaluator()
    private var backCallback: Any? = null
    private var animation: ValueAnimator? = null
    private var open = 1f
    private var closing = false
    private var preview = false
    var onRevealed: ((View) -> Unit)? = null

    fun home() = pages.size == 1
    fun current() = pages.last().view
    fun top(view: View) = pages.last().view == view

    private class EntryImage(
        activity: Activity, val source: View, val sourceAlpha: Float, private val bitmap: Bitmap
    ) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun onDraw(canvas: Canvas) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
    }

    private class Surface(activity: Activity) : FrameLayout(activity) {
        private var visibleHeight = Int.MAX_VALUE
        private var corner = 0f
        private val fill = ColorDrawable(CampusUi.BG)
        init {
            background = fill
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (width > 0 && height > 0) outline.setRoundRect(
                        0, 0, width, visibleHeight.coerceIn(1, height), corner)
                }
            }
        }
        fun shape(height: Int, rounding: Float, color: Int) {
            fill.color = color
            if (visibleHeight != height || corner != rounding) {
                visibleHeight = height
                corner = rounding
                invalidateOutline()
            }
        }
    }

    private fun anchor(source: View?, previous: View): Anchor? {
        if (source == null || source.width <= 0 || source.height <= 0 || !source.isAttachedToWindow) return null
        var parent: View? = source
        while (parent != null && parent !== previous) parent = parent.parent as? View
        if (parent == null) return null
        val rect = Rect(0, 0, source.width, source.height)
        host.offsetDescendantRectToMyCoords(source, rect)
        val bounds = Rect(host.paddingLeft, host.paddingTop, host.width - host.paddingRight, host.height - host.paddingBottom)
        val original = Rect(rect)
        if (!rect.intersect(bounds) || rect.height() < CampusUi.dp(activity, 12f)) return null
        val bg = source.background
        val shape = (if (bg is RippleDrawable && bg.numberOfLayers > 0) bg.getDrawable(0) else bg) as? MaterialShapeDrawable
        val button = source as? MaterialButton
        val model = button?.shapeAppearanceModel ?: shape?.shapeAppearanceModel
        val radius = model?.topLeftCornerSize?.getCornerSize(RectF(rect)) ?: CampusUi.dp(activity, 18f).toFloat()
        val tint = button?.backgroundTintList?.defaultColor ?: shape?.fillColor?.defaultColor ?: CampusUi.BG
        return Anchor(rect, original, tint, radius)
    }

    private fun prepareEntry(source: View?, anchor: Anchor?) {
        clearEntry()
        if (source == null || anchor == null || source.alpha <= 0f) return
        val bounds = anchor.bounds
        val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.translate((anchor.sourceBounds.left - bounds.left).toFloat(),
            (anchor.sourceBounds.top - bounds.top).toFloat())
        try {
            source.draw(canvas)
        } catch (_: IllegalArgumentException) {
            // A hardware-only drawable cannot be captured on a software canvas.
            return
        }
        entry = EntryImage(activity, source, source.alpha, image).apply {
            alpha = 0f
            layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            host.overlay.add(this)
        }
    }

    private fun clearEntry() {
        entry?.let {
            it.source.alpha = it.sourceAlpha
            host.overlay.remove(it)
        }
        entry = null
    }

    fun push(content: View, source: View?, popped: Runnable): Boolean {
        if (closing || preview) return false
        settle()
        val prior = pages.last().layer
        activeAnchor = anchor(source, prior)
        prepareEntry(source, activeAnchor)
        prior.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val surface = Surface(activity)
        surface.addView(content, FrameLayout.LayoutParams(-1, -1))
        surface.elevation = CampusUi.dp(activity, 3f).toFloat()
        host.addView(surface, FrameLayout.LayoutParams(-1, -1))
        pages.add(Page(content, surface, source, activeAnchor, popped))
        syncBack()
        changed.run()
        apply(0f)
        animate(1f, false)
        return true
    }

    fun pop(): Boolean {
        if (home()) return false
        if (!closing) {
            if (!preview && open == 1f) chooseReturnAnchor()
            preview = false
            animate(0f, true)
        }
        return true
    }

    fun resetToHome() {
        stop()
        clearEntry()
        while (!home()) {
            val page = pages.removeAt(pages.lastIndex)
            host.removeView(page.layer)
            page.popped.run()
        }
        closing = false
        preview = false
        open = 1f
        activeAnchor = null
        val home = pages.first().layer
        reset(home)
        home.visibility = View.VISIBLE
        home.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        syncBack()
    }

    fun replace(content: View, popped: Runnable): Boolean {
        if (home() || closing || preview) return false
        settle()
        val page = pages.last()
        val surface = page.layer as Surface
        surface.removeView(page.view)
        surface.addView(content, FrameLayout.LayoutParams(-1, -1))
        pages[pages.lastIndex] = page.copy(view = content, popped = popped)
        page.popped.run()
        changed.run()
        content.alpha = .55f
        content.animate().alpha(1f).setDuration(160).start()
        return true
    }

    private fun chooseReturnAnchor() {
        val page = pages.last()
        val previous = pages[pages.lastIndex - 1].layer
        val anchor = anchor(page.source, previous)
        activeAnchor = anchor ?: page.anchor
        prepareEntry(page.source, anchor)
    }

    private fun apply(fraction: Float) {
        if (home()) return
        open = fraction
        val page = pages.last()
        val out = pages[pages.lastIndex - 1].layer
        val incoming = page.layer as Surface
        val width = (host.width - host.paddingLeft - host.paddingRight).coerceAtLeast(1)
        val height = (host.height - host.paddingTop - host.paddingBottom).coerceAtLeast(1)
        out.visibility = View.VISIBLE
        out.pivotX = out.width / 2f
        out.pivotY = out.height / 2f
        out.scaleX = 1f - .12f * fraction
        out.scaleY = out.scaleX
        out.alpha = 1f - .18f * fraction
        if (Build.VERSION.SDK_INT >= 31) setBlur(out, (fraction * 4).roundToInt() * 3)
        incoming.pivotX = 0f
        incoming.pivotY = 0f
        val anchor = activeAnchor
        if (anchor != null) {
            val start = anchor.bounds
            val entry = entry
            val startX = start.exactCenterX()
            val startY = start.exactCenterY()
            val endX = host.paddingLeft + width / 2f
            val endY = host.paddingTop + height / 2f
            val lift = if (entry != null) smooth(fraction / .28f) else 0f
            val growth = if (entry != null) smooth((fraction - .24f) / .76f) else fraction
            val liftedScale = 1f + lift * (if (start.width() < CampusUi.dp(activity, 64f)) .24f else .13f)
            val liftDistance = CampusUi.dp(activity, 18f).toFloat()
            val x = lerp(startX + (endX - startX).coerceIn(-liftDistance, liftDistance) * lift, endX, growth)
            val y = lerp(startY + (endY - startY).coerceIn(-liftDistance, liftDistance) * lift, endY, growth)
            val visibleWidth = lerp(start.width() * liftedScale, width.toFloat(), growth)
            val visibleHeight = lerp(start.height() * liftedScale, height.toFloat(), growth)
            val scale = visibleWidth / width
            incoming.scaleX = scale; incoming.scaleY = scale
            incoming.translationX = x - visibleWidth / 2f - host.paddingLeft
            incoming.translationY = y - visibleHeight / 2f - host.paddingTop
            val content = if (entry != null) smooth((fraction - .38f) / .34f) else smooth((fraction - .21f) / .52f)
            incoming.shape((visibleHeight / scale).roundToInt(),
                lerp(anchor.corner * liftedScale, 0f, growth) / scale,
                colors.evaluate(content, anchor.color, CampusUi.BG) as Int)
            incoming.alpha = if (entry != null) smooth((fraction - .21f) / .16f) else smooth(fraction / .14f)
            page.view.alpha = content
            if (entry != null) {
                val alpha = 1f - smooth((fraction - .36f) / .24f)
                entry.source.alpha = if (alpha > 0f) 0f else entry.sourceAlpha
                entry.alpha = alpha * entry.sourceAlpha
                entry.scaleX = liftedScale + .36f * growth
                entry.scaleY = entry.scaleX
                entry.translationX = x - startX
                entry.translationY = y - startY
            }
        } else {
            val scale = lerp(.93f, 1f, fraction)
            incoming.scaleX = scale
            incoming.scaleY = scale
            incoming.translationX = width * (1f - scale) / 2
            incoming.translationY = height * (1f - scale) / 2
            incoming.shape(height, CampusUi.dp(activity, 20f) * (1f - fraction), CampusUi.BG)
            incoming.alpha = smooth(fraction)
            page.view.alpha = 1f
        }
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun animate(target: Float, remove: Boolean) {
        stop()
        closing = remove
        val value = ValueAnimator.ofFloat(open, target)
        animation = value
        value.duration = (120 + (if (remove) 300 else 340) * abs(target - open)).toLong()
        value.interpolator = if (target > open && open == 0f && entry != null)
            PathInterpolator(.31f, .15f, .32f, 1f) else PathInterpolator(.22f, .62f, .33f, 1f)
        value.addUpdateListener { apply(it.animatedValue as Float) }
        value.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                if (animation !== value) return
                animation = null
                if (remove) {
                    clearEntry()
                    val gone = pages.removeAt(pages.lastIndex)
                    host.removeView(gone.layer)
                    open = 1f
                    val shown = pages.last().layer
                    reset(shown)
                    shown.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    closing = false
                    activeAnchor = null
                    syncBack()
                    gone.popped.run()
                    changed.run()
                    onRevealed?.invoke(current())
                } else settle()
            }
        })
        value.start()
    }

    private fun settle() {
        stop()
        if (!home()) {
            apply(1f)
            val prior = pages[pages.lastIndex - 1].layer
            prior.visibility = View.INVISIBLE
            reset(prior)
        }
        clearEntry()
        open = 1f
    }

    private fun reset(view: View) {
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
        view.translationX = 0f; view.translationY = 0f
        if (Build.VERSION.SDK_INT >= 31) setBlur(view, 0)
        view.invalidateOutline()
    }

    private fun setBlur(view: View, dp: Int) {
        if (Build.VERSION.SDK_INT < 31 || view.getTag(R.id.campus_navigation_blur) == dp) return
        val radius = CampusUi.dp(activity, dp.toFloat()).toFloat()
        view.setRenderEffect(if (dp == 0) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        view.setTag(R.id.campus_navigation_blur, dp)
    }

    private fun stop() {
        val running = animation
        animation = null
        running?.cancel()
    }

    private fun syncBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = activity.onBackInvokedDispatcher
        val current = backCallback as OnBackInvokedCallback?
        if (home()) {
            current?.let(dispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
        } else if (current == null) {
            val callback = if (Build.VERSION.SDK_INT >= 34) animationCallback() else OnBackInvokedCallback { pop() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    private fun animationCallback(): OnBackInvokedCallback = object : OnBackAnimationCallback {
        override fun onBackStarted(event: BackEvent) {
            if (closing || home()) return
            settle()
            chooseReturnAnchor()
            preview = true
            apply(1f)
        }
        override fun onBackProgressed(event: BackEvent) {
            if (preview) apply(1f - .25f * event.progress)
        }
        override fun onBackCancelled() {
            if (!preview) return
            preview = false
            animate(1f, false)
        }
        override fun onBackInvoked() { pop() }
    }

    fun destroy() {
        stop()
        clearEntry()
        if (Build.VERSION.SDK_INT >= 33) (backCallback as OnBackInvokedCallback?)?.let {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        backCallback = null
    }
}
