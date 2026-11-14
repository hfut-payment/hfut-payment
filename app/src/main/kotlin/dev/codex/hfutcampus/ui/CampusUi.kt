package dev.codex.hfutcampus

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.CompoundButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Shared visual primitives for pages and every application-owned modal.  */
internal object CampusUi {
    const val BG: Int = -0xa0805
    const val WHITE: Int = -0x1
    const val INK: Int = -0xe6dcc9
    const val MUTED: Int = -0x968a76
    const val PRIMARY: Int = -0xc3a246
    const val TINT: Int = -0x150f01
    const val LINE: Int = -0x1e1810
    const val ERROR: Int = -0x4cd9e2
    const val ERROR_TINT: Int = -0x1217
    fun dp(context: Context, value: Float): Int {
        return Math.round(value * context.getResources().getDisplayMetrics().density)
    }

    fun column(c: Context?): LinearLayout {
        val v = LinearLayout(c)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    fun row(c: Context?): LinearLayout {
        val v = LinearLayout(c)
        v.setOrientation(LinearLayout.HORIZONTAL)
        v.setGravity(Gravity.CENTER_VERTICAL)
        return v
    }

    fun text(c: Context?, value: String?, sp: Int, color: Int): TextView {
        val v = TextView(c)
        v.setText(value)
        v.setTextSize(sp.toFloat())
        v.setTextColor(color)
        v.setFontFeatureSettings("kern")
        v.setIncludeFontPadding(false)
        return v
    }

    fun medium(v: TextView) {
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    private fun corners(c: Context, radius: Float): ShapeAppearanceModel =
        ShapeAppearanceModel.builder().setAllCornerSizes(dp(c, radius).toFloat()).build()

    fun shape(c: Context, color: Int, radius: Float): MaterialShapeDrawable =
        MaterialShapeDrawable(corners(c, radius)).apply { fillColor = ColorStateList.valueOf(color) }

    fun ripple(c: Context, color: Int, radius: Float): Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(0x14355dce),
            shape(c, color, radius),
            shape(c, WHITE, radius)
        )
    }

    fun button(
        c: Context,
        label: String?,
        primary: Boolean,
        danger: Boolean,
        action: Runnable
    ): TextView {
        val color = if (danger) ERROR else PRIMARY
        return materialButton(c, label, if (primary) color else if (danger) ERROR_TINT else TINT,
            if (primary) WHITE else color, 18f).apply { setOnClickListener { action.run() } }
    }

    private fun materialButton(c: Context, label: String?, fill: Int, ink: Int, radius: Float) =
        MaterialButton(c).apply {
            text = label
            textSize = 15f
            setTextColor(ink)
            isAllCaps = false
            includeFontPadding = false
            medium(this)
            gravity = Gravity.CENTER
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(c, 50f)
            minimumWidth = 0
            minWidth = 0
            shapeAppearanceModel = corners(c, radius)
            backgroundTintList = ColorStateList.valueOf(fill)
            rippleColor = ColorStateList.valueOf(if (ink == WHITE) 0x24ffffff else 0x14355dce)
            setPadding(dp(c, 14f), dp(c, 10f), dp(c, 14f), dp(c, 10f))
        }

    fun toggle(c: Context, description: String, checked: Boolean): CompoundButton =
        MaterialSwitch(c).apply {
            contentDescription = description
            isChecked = checked
            minimumHeight = dp(c, 48f)
            setPadding(dp(c, 6f), 0, dp(c, 6f), 0)
        }

    fun campusSelector(c: Context, hefei: Boolean, changed: (Boolean) -> Unit): View =
        MaterialButtonToggleGroup(c).apply {
            isSingleSelection = true
            isSelectionRequired = true
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            fun segment(label: String) = materialButton(c, label, WHITE, INK, 16f).apply {
                id = View.generateViewId()
                backgroundTintList = ColorStateList(states, intArrayOf(TINT, WHITE))
                setTextColor(ColorStateList(states, intArrayOf(PRIMARY, MUTED)))
                strokeColor = ColorStateList.valueOf(LINE)
                strokeWidth = dp(c, 1f)
            }
            val xc = segment("宣城校区")
            val hf = segment("合肥校区")
            addView(xc, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hf, LinearLayout.LayoutParams(0, -2, 1f))
            check(if (hefei) hf.id else xc.id)
            addOnButtonCheckedListener { _, id, checked -> if (checked) changed(id == hf.id) }
        }

    fun progress(c: Context, compact: Boolean = false): LinearProgressIndicator = LinearProgressIndicator(c).apply {
        trackThickness = dp(c, if (compact) 2f else 5f)
        trackCornerRadius = dp(c, if (compact) 1f else 3f)
        if (compact) {
            indicatorTrackGapSize = 0
            trackStopIndicatorSize = 0
        }
        trackColor = LINE
        setIndicatorColor(PRIMARY)
        max = 1000
        isIndeterminate = false
    }

    fun edit(c: Context): TextInputEditText = TextInputEditText(c).apply {
        background = shape(c, BG, 14f)
        setTextColor(INK)
        setHintTextColor(MUTED)
    }

    fun gap(c: Context, height: Int): View {
        val v = View(c)
        v.setLayoutParams(LinearLayout.LayoutParams(1, dp(c, height.toFloat())))
        return v
    }

    fun icon(c: Context?, kind: String, color: Int): Icon {
        return Icon(c, kind, color)
    }

    fun iconButton(c: Context, kind: String, description: String?, action: Runnable): View {
        val v = icon(c, kind, INK)
        v.setContentDescription(description)
        v.setBackground(ripple(c, WHITE, 24f))
        v.setOnClickListener(View.OnClickListener { w: View? -> action.run() })
        v.setFocusable(true)
        v.setPadding(dp(c, 13f), dp(c, 13f), dp(c, 13f), dp(c, 13f))
        return v
    }

    internal class Icon(c: Context?, kind: String, color: Int) : View(c) {
        var symbol = kind
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.setColor(color)
            paint.setStrokeWidth(1.8f)
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeJoin(Paint.Join.ROUND)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val kind = symbol
            val save = canvas.save()
            canvas.translate(getPaddingLeft().toFloat(), getPaddingTop().toFloat())
            val width = (getWidth() - getPaddingLeft() - getPaddingRight()).toFloat()
            val height = (getHeight() - getPaddingTop() - getPaddingBottom()).toFloat()
            canvas.scale(width / 24f, height / 24f)
            val p = Path()
            if (kind == "back") {
                p.moveTo(14.5f, 5f)
                p.lineTo(7.5f, 12f)
                p.lineTo(14.5f, 19f)
            } else if (kind == "close") {
                p.moveTo(6f, 6f)
                p.lineTo(18f, 18f)
                p.moveTo(18f, 6f)
                p.lineTo(6f, 18f)
            } else if (kind == "chevron") {
                p.moveTo(9f, 6f)
                p.lineTo(15f, 12f)
                p.lineTo(9f, 18f)
            } else if (kind == "eye" || kind == "eyeoff") {
                p.moveTo(2f, 12f)
                p.cubicTo(7f, 4f, 17f, 4f, 22f, 12f)
                p.cubicTo(17f, 20f, 7f, 20f, 2f, 12f)
                canvas.drawCircle(12f, 12f, 2.7f, paint)
                if (kind == "eyeoff") {
                    p.moveTo(4f, 4f)
                    p.lineTo(20f, 20f)
                }
            } else if (kind == "add") {
                p.moveTo(5f, 12f)
                p.lineTo(19f, 12f)
                p.moveTo(12f, 5f)
                p.lineTo(12f, 19f)
            } else if (kind == "more") {
                for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.1f, paint)
            } else if (kind == "check") {
                p.moveTo(5f, 12f)
                p.lineTo(10f, 17f)
                p.lineTo(19f, 7f)
            } else if (kind == "refresh") {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), 40f, 285f, false, paint)
                p.moveTo(17f, 3f)
                p.lineTo(20f, 8f)
                p.lineTo(14f, 8f)
            } else if (kind == "account") {
                canvas.drawCircle(12f, 8f, 3.5f, paint)
                canvas.drawArc(RectF(4f, 12f, 20f, 27f), 185f, 170f, false, paint)
            } else if (kind == "band") {
                canvas.drawRoundRect(7f, 5f, 17f, 19f, 3f, 3f, paint)
                p.moveTo(9f, 5f)
                p.lineTo(9f, 2f)
                p.lineTo(15f, 2f)
                p.lineTo(15f, 5f)
                p.moveTo(9f, 19f)
                p.lineTo(9f, 22f)
                p.lineTo(15f, 22f)
                p.lineTo(15f, 19f)
            } else if (kind == "display") {
                canvas.drawRoundRect(3f, 5f, 21f, 19f, 3f, 3f, paint)
                canvas.drawCircle(7f, 9f, 1.3f, paint)
                p.moveTo(13f, 9f)
                p.lineTo(17f, 9f)
                p.lineTo(17f, 15f)
                p.lineTo(11f, 15f)
                p.lineTo(11f, 11f)
            } else if (kind == "lock") {
                canvas.drawRoundRect(5f, 10f, 19f, 21f, 3f, 3f, paint)
                canvas.drawArc(RectF(8f, 2f, 16f, 16f), 180f, 180f, false, paint)
                p.moveTo(12f, 14f)
                p.lineTo(12f, 17f)
            } else if (kind == "power") {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -50f, 280f, false, paint)
                p.moveTo(12f, 2f)
                p.lineTo(12f, 11f)
            } else if (kind == "network") {
                canvas.drawArc(RectF(2f, 5f, 22f, 23f), 225f, 90f, false, paint)
                canvas.drawArc(RectF(6f, 10f, 18f, 22f), 225f, 90f, false, paint)
                canvas.drawCircle(12f, 18f, 1f, paint)
            } else if (kind == "water") {
                p.moveTo(12f, 3f)
                p.cubicTo(10f, 8f, 5.5f, 11f, 5.5f, 15f)
                p.cubicTo(5.5f, 18.5f, 8.5f, 21f, 12f, 21f)
                p.cubicTo(15.5f, 21f, 18.5f, 18.5f, 18.5f, 15f)
                p.cubicTo(18.5f, 11f, 14f, 8f, 12f, 3f)
                p.close()
            } else if (kind == "receipt") {
                canvas.drawRoundRect(5f, 3f, 19f, 21f, 2f, 2f, paint)
                canvas.drawLine(9f, 8f, 15f, 8f, paint)
                canvas.drawLine(9f, 12f, 15f, 12f, paint)
                canvas.drawLine(9f, 16f, 13f, 16f, paint)
            } else if (kind == "trash") {
                canvas.drawRoundRect(6f, 7f, 18f, 21f, 2f, 2f, paint)
                p.moveTo(4f, 6f)
                p.lineTo(20f, 6f)
                p.moveTo(9f, 6f)
                p.lineTo(9f, 3f)
                p.lineTo(15f, 3f)
                p.lineTo(15f, 6f)
                p.moveTo(10f, 10f)
                p.lineTo(10f, 17f)
                p.moveTo(14f, 10f)
                p.lineTo(14f, 17f)
            } else if (kind == "info" || kind == "error") {
                canvas.drawCircle(12f, 12f, 9f, paint)
                p.moveTo(12f, 6f)
                p.lineTo(12f, 13f)
                p.moveTo(12f, 17f)
                p.lineTo(12f, 17.2f)
            } else if (kind == "settings") {
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    canvas.drawLine(
                        12 + cos(a).toFloat() * 8,
                        12 + sin(a).toFloat() * 8,
                        12 + cos(a).toFloat() * 10,
                        12 + sin(a).toFloat() * 10,
                        paint
                    )
                }
                canvas.drawCircle(12f, 12f, 7.8f, paint)
                canvas.drawCircle(12f, 12f, 3f, paint)
            } else {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, paint)
                p.moveTo(3f, 9f)
                p.lineTo(21f, 9f)
                p.moveTo(7f, 15f)
                p.lineTo(11f, 15f)
            }
            canvas.drawPath(p, paint)
            canvas.restoreToCount(save)
        }
    }
}
