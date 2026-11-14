package dev.codex.hfutcampus

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Read-only fee protocol. A missing amount never becomes a zero balance. */
object CampusFees {
    enum class Kind(@JvmField val key: String, @JvmField val title: String, @JvmField val icon: String) {
        CARD("card", "校园卡余额", "wallet"),
        ELECTRIC("electric", "电费", "power"),
        NETWORK("network", "校园网", "network"),
        WATER("water", "水费 / 洗浴", "water"),
        LAUNDRY("laundry", "洗衣", "water");
    }

    data class Profile(
        val campus: String, val room: String, val building: String, val roomLabel: String,
        val phone: String, val networkAccount: String = "", val hefeiMeters: HefeiMeters? = null
    ) {
        fun xuancheng() = campus == "xc"
        fun hefei() = campus == "hf"

        fun cards(): List<Kind> = if (hefei()) listOf(Kind.CARD, Kind.ELECTRIC)
            else listOf(Kind.CARD, Kind.ELECTRIC, Kind.NETWORK, Kind.WATER)

        fun dualElectric() = xuancheng() || hefeiMeters != null

        fun hasDorm() = meter(false).isNotEmpty() && (!hefei() || meterBuilding(false).isNotEmpty()) ||
            hefeiMeters?.ac != null

        fun unavailable(kind: Kind, ac: Boolean = false): String = when {
            kind == Kind.CARD -> ""
            !xuancheng() && !hefei() -> "请设置校区"
            kind == Kind.ELECTRIC -> when {
                !hasDorm() -> "请选择宿舍"
                meter(ac).isEmpty() -> "暂无这路电表"
                else -> ""
            }
            hefei() -> when (kind) {
                Kind.NETWORK -> "校园网账号资费"
                Kind.WATER -> "水控余额暂不可查"
                else -> "洗衣余额暂不可查"
            }
            kind == Kind.LAUNDRY -> "该校区暂无余额查询"
            kind == Kind.WATER && phone.isEmpty() -> "请设置洗浴手机号"
            else -> ""
        }

        fun meter(ac: Boolean): String = when {
            hefeiMeters != null -> hefeiMeters.get(ac)?.room.orEmpty()
            hefei() -> if (ac) "" else room
            xcCode.matches(room) -> normalizeXcRoom(room).dropLast(1) + if (ac) "2" else "1"
            else -> ""
        }

        fun meterBuilding(ac: Boolean) = if (hefeiMeters != null) hefeiMeters.get(ac)?.building.orEmpty() else building
    }

    data class Reading(
        val primary: String,
        val secondary: String = "",
        val tertiary: String = "",
        val detail: Map<String, String> = emptyMap(),
        val available: Boolean = true,
        val network: NetworkUsage? = null
    ) {
        companion object {
            @JvmStatic fun status(value: String) = Reading(value, available = false)
        }
    }

    data class UsageMeter(
        val used: BigDecimal?, val limit: BigDecimal,
        val remaining: BigDecimal? = used?.let { (limit - it).max(BigDecimal.ZERO) }
    ) {
        val progress: Int get() = used?.takeIf { limit.signum() > 0 }
            ?.multiply(BigDecimal.valueOf(1000))?.divide(limit, 0, RoundingMode.HALF_UP)
            ?.coerceIn(BigDecimal.ZERO, BigDecimal.valueOf(1000))?.toInt() ?: 0
        val text: String get() = if (used == null) compact(limit) + " GiB"
            else compact(used) + " / " + compact(limit) + " GiB"
        val remainingText: String? get() = remaining?.let { compact(it) + " GiB" }
    }

    data class NetworkUsage(
        val monthlyFree: UsageMeter, val monthlyTotal: UsageMeter, val exhausted: Boolean,
        val estimated: Boolean, val balance: String?
    )

    // Published by the Xuancheng campus network service guide.
    const val XC_FREE_GIB = "200"
    const val XC_MONTHLY_LIMIT_GIB = "400"

    data class RoomOption(val name: String, val id: String)
    data class HefeiMeter(val building: String, val room: String)
    data class HefeiMeters(val light: HefeiMeter?, val ac: HefeiMeter?) {
        fun get(air: Boolean) = if (air) ac else light
    }
    data class HefeiBuilding(
        val label: String, val light: RoomOption? = null, val ac: RoomOption? = null, val unclassified: RoomOption? = null
    )
    data class HefeiRoom(val name: String, val meters: HefeiMeters)

    private val separators = Regex("[\\s·/：:()（）\\[\\]【】]+")
    private val circuitName = Regex("(照明|常规|空调)(?:用电|电费|电表|回路)?$")
    private fun compactName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(separators, "")
    fun hefeiCircuit(name: String): Boolean? = circuitName.find(compactName(name))?.groupValues?.get(1)?.let { it == "空调" }

    private fun hefeiPlaceName(name: String) = compactName(name)
        .replace(Regex("^(屯溪路|翡翠湖|六安路)校区"), "$1")
        .replace(Regex("(\\d+)号?(?:栋|幢)"), "$1号楼")

    private fun hefeiBuildingName(name: String): String {
        val label = hefeiPlaceName(name).replace(circuitName, "").trimEnd('-', '_')
        return label.replace(Regex("([南北中])$"), "$1楼")
    }

    fun hefeiBuildings(options: List<RoomOption>): List<HefeiBuilding> {
        val groups = options.distinct().groupBy { option ->
            if (hefeiCircuit(option.name) == null) option.id to option.name
            else "" to hefeiBuildingName(option.name)
        }
        return groups.flatMap { (key, entries) ->
            val label = key.second
            val light = entries.filter { hefeiCircuit(it.name) == false }
            val ac = entries.filter { hefeiCircuit(it.name) == true }
            if (key.first.isNotEmpty() || light.size > 1 || ac.size > 1 ||
                light.firstOrNull()?.id == ac.firstOrNull()?.id) {
                entries.map { HefeiBuilding(it.name, unclassified = it) }
            } else listOf(HefeiBuilding(label, light.singleOrNull(), ac.singleOrNull()))
        }
    }

    fun hefeiRoomName(room: RoomOption, building: RoomOption): String {
        val full = hefeiPlaceName(room.name)
        val buildingLabel = hefeiBuildingName(building.name)
        val fullBuilding = hefeiPlaceName(building.name)
        val rawBuilding = fullBuilding.replace(circuitName, "").trimEnd('-', '_')
        val prefixes = listOf(fullBuilding, rawBuilding, buildingLabel)
        val suffix = prefixes.filter(String::isNotEmpty).sortedByDescending(String::length)
            .firstOrNull(full::startsWith)?.let {
                full.removePrefix(it).trimStart('-', '_')
                    .replace(Regex("^(?:照明|常规|空调)(?:用电|电费|电表|回路)?"), "").trimStart('-', '_')
            } ?: full
        return suffix.replace(Regex("^(?:房间号?|寝室号?|宿舍号?)"), "")
            .replace(circuitName, "")
            .replace(Regex("(?:号?(?:寝室|房间|宿舍|室)|号)$"), "").trimEnd('-', '_').ifBlank { room.name }
    }

    fun hefeiRooms(building: HefeiBuilding, light: List<RoomOption>, ac: List<RoomOption>): List<HefeiRoom> {
        fun index(zone: RoomOption?, options: List<RoomOption>): Map<String, List<HefeiMeter>> =
            if (zone == null) emptyMap() else options.distinctBy { it.id }.groupBy(
                { hefeiRoomName(it, zone) }, { HefeiMeter(zone.id, it.id) })
        val byLight = index(building.light, light)
        val byAc = index(building.ac, ac)
        return (byLight.keys + byAc.keys).sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it }).mapNotNull { name ->
            val lightMeter = byLight[name]?.singleOrNull()
            val airMeter = byAc[name]?.singleOrNull()
            if (lightMeter == null && airMeter == null) null
            else HefeiRoom(name, HefeiMeters(lightMeter, airMeter))
        }
    }

    private val xcCode = Regex("300([1-9]|10)([0-9]{3})([12])[12]")
    private val choiceId = Regex("[A-Za-z0-9_-]{1,80}")

    fun xcHasWings(building: Int): Boolean = building != 7 && building != 8

    fun xcRoom(building: Int, number: String, south: Boolean): String =
        if (building in 1..10 && number.matches(Regex("[0-9]{3}"))) {
            "300" + building + number + if (south || !xcHasWings(building)) "11" else "21"
        } else ""

    fun normalizeXcRoom(room: String): String {
        val parts = xcCode.matchEntire(room)?.groupValues ?: return room
        return if (xcHasWings(parts[1].toInt()) || parts[3] == "1") room
            else room.replaceRange(room.length - 2, room.length - 1, "1")
    }

    fun xcRoomLabel(room: String): String {
        val parts = xcCode.matchEntire(room)?.groupValues ?: return ""
        return "${xcBuildingLabel(parts[1].toInt(), parts[3] == "1")} / ${parts[2]}"
    }

    fun xcBuilding(building: Int) = (if (building <= 5) "北区" else "南区") + building + "号楼"

    fun xcBuildingLabel(building: Int, south: Boolean) = xcBuilding(building) + when {
        !xcHasWings(building) -> ""
        south -> " / 南楼"
        else -> " / 北楼"
    }

    @JvmStatic fun roomFields(building: String): Map<String, String> =
        linkedMapOf("type" to "select", "feeitemid" to "1", "campus" to "1sh",
            "level" to if (building.isEmpty()) "1" else "2").apply {
            if (building.isNotEmpty()) {
                require(choiceId.matches(building))
                put("building", building)
            }
        }

    @JvmStatic @Throws(CampusApi.Failure::class)
    fun rooms(response: JSONObject): List<RoomOption> {
        val items = response.optJSONObject("map")?.optJSONArray("data") ?: throw unavailable()
        return (0 until minOf(items.length(), 1500)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val id = item.optString("value")
            if (name.length in 1..80 && choiceId.matches(id)) RoomOption(name, id) else null
        }.ifEmpty { throw unavailable() }
    }

    @JvmStatic fun fields(kind: Kind, profile: Profile, ac: Boolean): Map<String, String> =
        linkedMapOf("type" to "IEC").apply {
            when (kind) {
                Kind.NETWORK -> { put("feeitemid", "281"); put("level", "0") }
                Kind.WATER -> { put("feeitemid", "223"); put("level", "1"); put("telPhone", profile.phone) }
                Kind.ELECTRIC -> {
                    put("feeitemid", if (profile.xuancheng()) "261" else "1")
                    put("room", profile.meter(ac))
                    if (profile.hefei()) {
                        put("campus", "1sh"); put("building", profile.meterBuilding(ac)); put("level", "1")
                    }
                }
                else -> error("Not a verified fee form")
            }
        }

    private const val NUMBER = "-?[0-9]{1,14}(?:\\.[0-9]{1,8})?"
    private val amount = Regex("(?:[¥￥]\\s*)?($NUMBER)\\s*(元|kWh|度|千瓦时)?", RegexOption.IGNORE_CASE)
    private val trafficAmount = Regex("($NUMBER)\\s*(B|KB|MB|GB|KiB|MiB|GiB)?", RegexOption.IGNORE_CASE)
    private val centsPattern = Regex(NUMBER)
    private val annotations = Regex("[（(]")
    private val binaryGiB = BigDecimal.valueOf(1073741824)

    @JvmStatic @Throws(CampusApi.Failure::class)
    fun parse(kind: Kind, response: JSONObject, selected: String, hefei: Boolean = false): Reading {
        if (kind == Kind.CARD) return card(response, selected)
        val map = response.optJSONObject("map") ?: throw unavailable()
        if (kind == Kind.WATER) {
            val data = map.optJSONObject("data") ?: throw unavailable()
            val value = money(number(data.opt("accountMoney")).movePointLeft(3), 2)
            val gift = data.opt("accountGivenMoney")?.takeUnless { it == JSONObject.NULL }
                ?.let { money(number(it).movePointLeft(3), 2) }
            val detail = linkedMapOf("洗浴余额" to value)
            if (gift != null) detail["赠送余额"] = gift
            return Reading(value, gift?.let { "赠送 $it" }.orEmpty(), detail = detail)
        }
        val display = map.optJSONObject("showData") ?: throw unavailable()
        return when (kind) {
            Kind.NETWORK -> network(display)
            Kind.ELECTRIC -> electric(display, hefei)
            else -> throw unavailable()
        }
    }

    private val moneyKeys = listOf("剩余金额", "剩余电费", "电费余额", "当前余额", "账户余额", "余额")
    private val energyKeys = listOf("剩余电量", "当前剩余电量", "可用电量")

    private fun electric(display: JSONObject, hefei: Boolean): Reading {
        val detail = linkedMapOf<String, String>()
        for (key in moneyKeys + energyKeys) {
            // Some meters include “房间号:… 剩余金额:…” in a single field.
            val combined = Regex(Regex.escape(key) + "\\s*[:：]\\s*((?:[¥￥]\\s*)?$NUMBER" +
                "(?:\\s*(?:元|kWh|度|千瓦时))?)(?![\\d.])", RegexOption.IGNORE_CASE)
            val direct = clean(label(display, key)).trimStart(':', '：').trim()
            val match = amount.matchEntire(direct)
                ?: display.keys().asSequence()
                    .mapNotNull { combined.find(label(display, it))?.groupValues?.get(1) }
                    .mapNotNull { amount.matchEntire(it.trim()) }.firstOrNull()
                ?: continue
            val n = match.groupValues[1].toBigDecimal()
            val unit = match.groups[2]?.value
            val energy = if (unit == null) key in energyKeys && '¥' !in match.value && '￥' !in match.value else unit != "元"
            if (energy) detail.putIfAbsent("剩余电量", decimal(n, 2) + " kWh")
            else detail.putIfAbsent("剩余金额", money(n, 2))
        }
        if (detail.isEmpty() && hefei) {
            val metadata = Regex("房间|房号|楼栋|楼号|校区|编号|电表号|表号|单价|费率|倍率|已用|累计|时间|日期|状态|^id$", RegexOption.IGNORE_CASE)
            val match = display.keys().asSequence().filterNot(metadata::containsMatchIn)
                .mapNotNull { amount.matchEntire(clean(label(display, it))) }.toList().singleOrNull()
            if (match != null) {
                val value = match.groupValues[1].toBigDecimal()
                val unit = match.groups[2]?.value
                if (unit == null || unit == "元") detail["剩余金额"] = money(value, 2)
                else detail["剩余电量"] = decimal(value, 2) + " kWh"
            }
        }
        return Reading(detail.values.firstOrNull() ?: throw unavailable(), detail = detail)
    }

    private fun network(display: JSONObject): Reading {
        val used = traffic(display, "本期已使用流量", "本月已使用流量")
        var free = traffic(display, "本期免费剩余流量", "本期剩余免费流量", "本月剩余免费流量", "剩余免费流量", "免费剩余流量")
        val monthlyReported = traffic(display, "本期免费流量", "本月免费额度", "每月免费流量", "月免费流量", "免费流量额度")
        val monthly = monthlyReported ?: XC_FREE_GIB.toBigDecimal()
        val estimated = free == null && used != null
        if (estimated) free = (monthly - used).max(BigDecimal.ZERO)
        val exhausted = free?.signum() == 0 || monthly.signum() == 0
        val cash = amount.matchEntire(clean(label(display, "储值余额")))
            ?.takeIf { it.groups[2]?.value.let { unit -> unit == null || unit == "元" } }
            ?.let { money(it.groupValues[1].toBigDecimal(), 2) }
        val detail = linkedMapOf<String, String>()
        if (used != null) detail["本期已用"] = gib(used)
        if (free != null) detail["本期免费剩余"] = gib(free)
        detail["每月免费额度"] = gib(monthly)
        val monthlyLimit = traffic(display, "每月总量", "每月总上限", "本月限额", "本月流量上限", "每月流量上限", "月流量限额")
        val total = monthlyLimit ?: XC_MONTHLY_LIMIT_GIB.toBigDecimal()
        detail["每月总上限"] = gib(total)
        if (cash != null) detail["储值余额"] = cash
        val (primary, subtitle) = when {
            used != null -> gib(used) to "本期已用"
            free != null -> gib(free) to "免费剩余"
            cash != null -> cash to "储值余额"
            else -> throw unavailable()
        }
        val extra = when {
            used != null && free != null -> (if (estimated) "估算剩余 " else "免费剩余 ") + gib(free)
            else -> ""
        }
        val usage = NetworkUsage(
            UsageMeter(free?.let { (monthly - it).max(BigDecimal.ZERO) }, monthly, free),
            UsageMeter(used, total),
            exhausted, estimated, cash
        )
        return Reading(primary, subtitle, extra, detail, network = usage)
    }

    private fun traffic(display: JSONObject, vararg names: String): BigDecimal? {
        for (key in names) {
            val match = trafficAmount.matchEntire(clean(label(display, key))) ?: continue
            val n = match.groupValues[1].toBigDecimal()
            if (n.signum() < 0) continue
            // HuiXin's bare flow counter is MiB; explicit SI prefixes retain SI meaning.
            return when (match.groups[2]?.value?.uppercase(Locale.ROOT) ?: "MIB") {
                "GIB" -> n
                "MIB" -> n.divide(BigDecimal.valueOf(1024))
                "KIB" -> n.divide(BigDecimal.valueOf(1048576))
                "GB" -> n.movePointRight(9).divide(binaryGiB)
                "MB" -> n.movePointRight(6).divide(binaryGiB)
                "KB" -> n.movePointRight(3).divide(binaryGiB)
                else -> n.divide(binaryGiB)
            }
        }
        return null
    }

    private fun gib(n: BigDecimal) = decimal(n, 2) + " GiB"
    private fun compact(n: BigDecimal) = n.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun card(response: JSONObject, selected: String): Reading {
        val cards = response.optJSONObject("data")?.optJSONArray("card") ?: throw unavailable()
        val eligible = (0 until cards.length()).mapNotNull { cards.optJSONObject(it) }
            .filter { it.optString("account").isNotEmpty() && it.optInt("lostflag", 0) != 1 }
        val row = eligible.firstOrNull { "card:" + it.optString("account") == selected }
            ?: eligible.singleOrNull()?.takeUnless { selected.startsWith("card:") }
            ?: if (eligible.isEmpty()) throw unavailable()
            else throw CampusApi.Failure("fee_account", "请在账户里选定实体卡", false)
        val balance = cents(row.opt("db_balance"))
        val pending = row.opt("unsettle_amount")?.takeUnless { it == JSONObject.NULL }?.let(::cents)
        val primary = money(if (pending != null) balance + pending else balance, 2)
        val detail = linkedMapOf("实际余额" to money(balance, 2), "待圈存" to (pending?.let { money(it, 2) } ?: "未返回"))
        if (pending != null) detail["圈存后余额"] = primary
        val secondary = when {
            pending == null -> "待圈存未返回"
            pending.signum() == 0 -> "无待圈存金额"
            else -> money(balance, 2) + (if (pending.signum() < 0) " − " else " + ") + money(pending.abs(), 2)
        }
        return Reading(primary, secondary, detail = detail)
    }

    private fun cents(input: Any?): BigDecimal = number(input).movePointLeft(2)

    private fun number(input: Any?): BigDecimal {
        val raw = input?.toString() ?: throw unavailable()
        if (!centsPattern.matches(raw)) throw unavailable()
        return raw.toBigDecimal()
    }
    private fun money(n: BigDecimal, digits: Int) = "¥" + decimal(n, digits)
    private fun decimal(n: BigDecimal, digits: Int) = n.setScale(digits, RoundingMode.HALF_UP).toPlainString()
    private fun clean(text: String) = text.split(annotations, limit = 2)[0].trim()
    private fun label(obj: JSONObject, key: String) = when (val value = obj.opt(key)) {
        is String, is Number -> value.toString().trim().takeIf { it.length <= 240 }.orEmpty()
        else -> ""
    }
    private fun unavailable() = CampusApi.Failure("fee_unavailable", "暂无可用数据", false)
}
