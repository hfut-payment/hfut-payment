package dev.codex.hfutcampus

import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import org.json.JSONArray
import org.json.JSONObject

/** Native controls expose structured values. Passwords stay separate from ordinary input. */
internal class CommunityForm(private val body: LinearLayout, definitions: JSONArray?, restored: JSONObject? = null) {
    private val context = body.context
    private val values = JSONObject()
    private val fields = linkedMapOf<String, EditText>()
    private val passwords = linkedMapOf<String, EditText>()
    private var error: android.widget.TextView? = null
    private var changed: (() -> Unit)? = null
    val sensitive: Boolean get() = passwords.isNotEmpty()

    init {
        for (i in 0 until minOf(definitions?.length() ?: 0, 12)) {
            val field = definitions?.optJSONObject(i) ?: continue
            val key = field.optString("key")
            val label = field.optString("title").take(60)
            val value = restored?.opt(key) ?: field.opt("value")
            if (!CommunitySpec.KEY.matches(key) || label.isBlank() || values.has(key) ||
                key in this.fields || key in passwords) continue
            when (val type = field.optString("type")) {
                "switch" -> {
                    val checked = value == true
                    values.put(key, checked)
                    val line = CampusUi.row(context)
                    val labels = CampusUi.column(context)
                    labels.addView(CampusUi.text(context, label, 15, CampusUi.INK).also(CampusUi::medium))
                    detail(labels, field.optString("detail").take(120))
                    line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    line.addView(CampusUi.toggle(context, label, checked).apply {
                        setOnCheckedChangeListener { _, value -> values.put(key, value); changed?.invoke() }
                    })
                    place(line)
                }
                "choice" -> {
                    val options = field.optJSONArray("options") ?: continue
                    val items = (0 until minOf(options.length(), 8)).mapNotNull { index ->
                        val option = options.optJSONObject(index) ?: return@mapNotNull null
                        val title = option.optString("title").take(60)
                        val value = option.optString("value").take(120)
                        if (title.isBlank() || value.isBlank()) null else value to title
                    }.distinctBy { it.first }
                    if (items.isEmpty()) continue
                    label(label)
                    val initial = (value as? String)?.takeIf { v -> items.any { it.first == v } } ?: items.first().first
                    values.put(key, initial)
                    val choices = RadioGroup(context)
                    items.forEach { (value, title) ->
                        choices.addView(MaterialRadioButton(context).apply {
                            id = View.generateViewId()
                            text = title
                            textSize = 15f
                            setTextColor(CampusUi.INK)
                            buttonTintList = ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(CampusUi.PRIMARY, CampusUi.MUTED)
                            )
                            isChecked = value == initial
                            setOnCheckedChangeListener { _, checked -> if (checked) {
                                values.put(key, value)
                                changed?.invoke()
                            } }
                        })
                    }
                    place(choices)
                }
                "text", "number", "password" -> {
                    val padding = CampusUi.dp(context, 14f)
                    label(label)
                    val input = CampusUi.edit(context).apply {
                        id = View.generateViewId()
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        isSaveEnabled = false
                        hint = field.optString("hint").take(80)
                        setSingleLine()
                        setPadding(padding, padding, padding, padding)
                        filters = arrayOf(InputFilter.LengthFilter(if (type == "password") 128 else 240))
                        inputType = when (type) {
                            "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            "password" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        }
                        if (type != "password") setText(value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty().take(240))
                    }
                    place(input)
                    if (type == "password") passwords[key] = input else this.fields[key] = input
                }
            }
        }
    }

    private fun label(value: String) {
        val title = CampusUi.text(context, value, 14, CampusUi.INK).also(CampusUi::medium)
        place(title, 12)
    }

    private fun detail(parent: LinearLayout, value: String) {
        if (value.isNotBlank()) parent.addView(CampusUi.text(context, value, 12, CampusUi.MUTED).apply {
            setPadding(0, CampusUi.dp(context, 4f), 0, 0)
        })
    }

    private fun place(view: View, top: Int = 6) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = CampusUi.dp(context, top.toFloat())
        })
    }

    fun values(): JSONObject = JSONObject(values.toString()).apply {
        fields.forEach { (key, input) -> put(key, input.text?.toString().orEmpty()) }
    }

    fun showError(message: String) {
        showStatus(message, true)
    }

    fun showStatus(message: String, failed: Boolean = false) {
        val label = error ?: CampusUi.text(context, "", 13, CampusUi.ERROR).also {
            error = it
            it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            place(it, 12)
        }
        label.setTextColor(if (failed) CampusUi.ERROR else CampusUi.MUTED)
        label.text = message
    }

    fun watchChanges(listener: () -> Unit) {
        changed = listener
        fields.values.forEach { input -> input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { changed?.invoke() }
        }) }
    }

    fun takePasswords(): Map<String, String> = passwords.mapValues { (_, input) ->
        input.text?.toString().orEmpty().also { input.text?.clear() }
    }
}
