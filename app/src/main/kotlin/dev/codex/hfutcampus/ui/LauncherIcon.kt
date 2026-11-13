package dev.codex.hfutcampus

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.random.Random

/** Android launchers get their icon from the active launcher alias. */
internal class LauncherIcon(context: Context) {
    private data class Choice(val name: String, val icon: Int)

    private val manager = context.packageManager
    private val packageName = context.packageName

    val currentIcon: Int
        get() = active().lastOrNull()?.icon ?: choices.first().icon

    fun shuffle() {
        val current = active().lastOrNull() ?: choices.first()
        switchTo(choices.drop(1).filterNot { it == current }.random(Random.Default))
    }

    fun restore() = switchTo(choices.first())

    private fun component(choice: Choice) =
        ComponentName(packageName, "${BuildConfig.APPLICATION_ID}.launcher.${choice.name}")

    private fun active(): List<Choice> = choices.filterIndexed { index, choice ->
        when (manager.getComponentEnabledSetting(component(choice))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> index == 0
            else -> false
        }
    }

    private fun switchTo(next: Choice) {
        val current = active()
        if (current == listOf(next)) return

        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= 33) {
            val updates = (current + next).distinct().map { choice ->
                PackageManager.ComponentEnabledSetting(
                    component(choice),
                    if (choice == next) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    flags,
                )
            }
            manager.setComponentEnabledSettings(updates)
        } else {
            // Keep an entry available throughout a switch on older Android versions.
            manager.setComponentEnabledSetting(component(next), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
            try {
                current.filterNot { it == next }.forEach { choice ->
                    manager.setComponentEnabledSetting(component(choice), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
                }
            } catch (failure: RuntimeException) {
                // Preserve a working launcher entry even if the system rejects a change.
                if (current.any { it != next && it in active() }) {
                    runCatching {
                        manager.setComponentEnabledSetting(component(next), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
                    }
                }
                throw failure
            }
        }
    }

    private companion object {
        val choices = listOf(
            Choice("Default", R.mipmap.ic_launcher),
            Choice("Pattern01", R.mipmap.ic_launcher_pattern_01),
            Choice("Pattern02", R.mipmap.ic_launcher_pattern_02),
            Choice("Pattern03", R.mipmap.ic_launcher_pattern_03),
            Choice("Pattern04", R.mipmap.ic_launcher_pattern_04),
            Choice("Pattern05", R.mipmap.ic_launcher_pattern_05),
            Choice("Pattern06", R.mipmap.ic_launcher_pattern_06),
            Choice("Pattern07", R.mipmap.ic_launcher_pattern_07),
            Choice("Pattern08", R.mipmap.ic_launcher_pattern_08),
            Choice("Pattern09", R.mipmap.ic_launcher_pattern_09),
            Choice("Pattern10", R.mipmap.ic_launcher_pattern_10),
            Choice("Pattern11", R.mipmap.ic_launcher_pattern_11),
            Choice("Pattern12", R.mipmap.ic_launcher_pattern_12),
        )
    }
}
