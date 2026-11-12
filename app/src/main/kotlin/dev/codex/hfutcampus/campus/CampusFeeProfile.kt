package dev.codex.hfutcampus

import android.content.Context
import org.json.JSONObject

/** The fee settings and authorised plugins read the same account-scoped configuration. */
internal object CampusFeeProfile {
    val labels = linkedMapOf(
        "campus" to "当前校区",
        "room" to "宿舍与电表编号",
        "phone" to "洗浴绑定手机号",
        "network-account" to "校园网账号",
        "student-account" to "校园卡登录学号"
    )

    fun preferences(context: Context, account: String) =
        context.getSharedPreferences("campus-fee-profile-$account", Context.MODE_PRIVATE)

    fun read(context: Context, account: String, campusOverride: String? = null): CampusFees.Profile {
        val stored = preferences(context, account)
        val current = stored.getString("campus", "").orEmpty()
        val campus = campusOverride ?: current
        fun read(key: String) = stored.getString("$campus.$key", null)
            ?: if (campus == current) stored.getString(key, "").orEmpty() else ""
        val room = read("room")
        val building = read("building")
        val label = if (campus == "xc") CampusFees.xcRoomLabel(room) else read("roomLabel")
        fun meter(building: String, room: String) = if (building.isNotEmpty() && room.isNotEmpty()) {
            CampusFees.HefeiMeter(building, room)
        } else null
        val circuits = if (campus == "hf") {
            val light = meter(read("lightBuilding"), read("lightRoom"))
            val ac = meter(read("acBuilding"), read("acRoom"))
            if (light != null || ac != null) CampusFees.HefeiMeters(light, ac)
            else {
                val legacy = meter(building, room)
                when (CampusFees.hefeiCircuit(label.substringBefore(" · "))) {
                    true -> legacy?.let { CampusFees.HefeiMeters(null, it) }
                    false -> legacy?.let { CampusFees.HefeiMeters(it, null) }
                    null -> null
                }
            }
        } else null
        return CampusFees.Profile(campus, room, building, label, read("phone"), read("networkAccount"), circuits)
    }

    fun forPlugin(context: Context, runtime: CampusRuntime, account: String, fields: Set<String>): JSONObject {
        val data = JSONObject()
        val revision = runtime.configurationRevision()
        if (fields.isEmpty() || account.isBlank() || !runtime.isConfigured || account != runtime.feeProfileKey()) return data
        val profile = read(context, account)
        fun put(key: String, value: String) {
            if (value.isNotBlank()) data.put(key, value)
        }
        if ("campus" in fields) put("campus", profile.campus)
        if ("room" in fields && profile.room.isNotBlank()) {
            data.put("room", JSONObject().put("id", profile.room).put("building", profile.building)
                .put("label", profile.roomLabel.ifBlank { CampusFees.xcRoomLabel(profile.room) })
                .put("regularMeter", profile.meter(false))
                .put("acMeter", if (profile.xuancheng()) profile.meter(true) else ""))
        }
        if ("phone" in fields) put("phone", profile.phone)
        if ("network-account" in fields) put("networkAccount", profile.networkAccount)
        if ("student-account" in fields) put("studentAccount", runtime.studentAccount())
        return if (revision == runtime.configurationRevision()) data else JSONObject()
    }
}
