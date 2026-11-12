package dev.codex.hfutcampus

internal enum class HostDevice(val pluginId: String, val authority: String, val legacyEntry: String) {
    BAND("hfut.band", "", "dev.codex.hfutcampus.BandPlugin"),
    REAR("hfut.rear", "dev.codex.hfutcampus.rear", "dev.codex.hfutcampus.RearPlugin");

    fun create(host: NativePlugin.Host): NativePlugin? = when (this) {
        BAND -> createBandPlugin(host)
        REAR -> RearPlugin(host)
    }
}
