package dev.codex.hfutcampus

import android.view.View
import java.io.IOException
import org.json.JSONObject

/** Optional, bounded links from a fee detail into the existing plugin UI protocol. */
internal class FeePluginEntries(shortcuts: JSONObject?) {
    data class Extra(
        val id: String, val title: String, val text: String, val icon: String,
        val campuses: Set<String>, val slot: String
    )

    private val kinds = CampusFees.Kind.entries.map { it.key }.toSet()
    private val entries: Map<String, PluginBundle.Shortcut> = buildMap {
        val raw = shortcuts?.opt("fees") ?: return@buildMap
        if (raw !is JSONObject) throw IOException("费用入口格式无效")
        for (key in raw.keys()) {
            if (key !in kinds) throw IOException("未知费用入口")
            put(key, PluginBundle.Shortcut(raw.getJSONObject(key)))
        }
    }
    fun find(kind: String): PluginBundle.Shortcut? = entries[kind]

    private val extras = buildMap {
        if (shortcuts?.has("feeExtras") != true) return@buildMap
        val groups = shortcuts.getJSONObject("feeExtras")
        for (kind in groups.keys()) {
            require(kind in kinds) { "未知费用卡片" }
            val rows = groups.getJSONArray(kind)
            require(rows.length() in 1..4)
            val items = (0 until rows.length()).map { i ->
                val item = rows.getJSONObject(i)
                val id = item.getString("id")
                val title = item.getString("title").trim()
                val glyph = item.optString("text")
                val icon = item.optString("icon")
                val slot = item.optString("slot")
                val campuses = item.optJSONArray("campuses")?.let { values ->
                    (0 until values.length()).map { values.getString(it) }.toSet()
                }.orEmpty()
                require(CommunitySpec.KEY.matches(id) && title.length in 1..60 &&
                    glyph.codePointCount(0, glyph.length) <= 4 && glyph.none(Char::isISOControl) &&
                    (icon.isEmpty() || icon.matches(Regex("[a-z]{1,20}"))) &&
                    (glyph.isNotBlank() || icon.isNotEmpty()) && campuses.all { it in setOf("xc", "hf") } &&
                    (slot.isEmpty() || kind == "electric" && slot in setOf("regular", "ac")))
                Extra(id, title, glyph, icon, campuses, slot)
            }
            require(items.map(Extra::id).distinct().size == items.size)
            put(kind, items)
        }
    }

    fun extras(kind: String, campus: String) =
        extras[kind].orEmpty().filter {
            // The host owns this action; older manifests can still be loaded.
            (kind != "card" || it.id != "topup") && (it.campuses.isEmpty() || campus in it.campuses)
        }

    companion object {
        @JvmStatic fun context(kind: String, campus: String): JSONObject =
            JSONObject().put("kind", kind).put("campus", campus.takeIf { it == "hf" || it == "xc" }.orEmpty())

        @JvmStatic fun show(activity: MainActivity, nav: CampusNavigation, plugin: PluginBundle,
                            response: JSONObject, source: View?, event: java.util.function.Consumer<String>) {
            fun field(obj: JSONObject, key: String, fallback: String = "") =
                (obj.opt(key) as? String)?.take(240)?.ifBlank { fallback } ?: fallback
            val page = CampusFeePages.Screen(activity, nav, field(response, "title", plugin.name))
            val detail = field(response, "detail")
            if (detail.isNotEmpty()) {
                page.body.addView(CampusUi.text(activity, detail, 14, CampusUi.MUTED).apply {
                    val gap = CampusUi.dp(activity, 12f)
                    setPadding(gap, gap, gap, gap)
                })
            }
            val rows = response.optJSONArray("rows")
            for (i in 0 until minOf(rows?.length() ?: 0, 8)) {
                val row = rows?.optJSONObject(i) ?: continue
                val title = field(row, "title")
                val action = field(row, "event")
                if (title.isEmpty() || !action.matches(Regex("[a-z][a-z0-9.-]{0,39}")) || action in listOf("page", "fee.page")) continue
                val summary = field(row, "detail")
                val label = if (summary.isEmpty()) title else "$title\n$summary"
                page.body.addView(CampusUi.button(activity, label, false, false) {
                    if (LocalPlugins.get(activity).active(plugin)) event.accept(action)
                }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = CampusUi.dp(activity, 12f) })
            }
            nav.push(page, source) {}
        }
    }
}
