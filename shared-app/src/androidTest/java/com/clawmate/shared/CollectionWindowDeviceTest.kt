package com.clawmate.shared

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.BackgroundDisplay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionWindowDeviceTest {
    @Test fun repeatedVirtualCollectionsRemoveOnlyTheirOwnCalculatorTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("calculatorCleanup") == "true")
        val pkg = "com.coloros.calculator"
        val launch = requireNotNull(instrumentation.targetContext.packageManager.getLaunchIntentForPackage(pkg))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        fun tasks(): Map<Int, Int> {
            val owners = mutableMapOf<Int, Int>()
            var display = -1
            for (line in shell("cmd activity stack list").lineSequence()) {
                if (line.startsWith("RootTask")) display = Regex("displayId=(\\d+)").find(line)!!.groupValues[1].toInt()
                Regex("""^\s*taskId=(\d+): com\.coloros\.calculator/""").find(line)?.let { owners[it.groupValues[1].toInt()] = display }
            }
            return owners
        }
        fun recents() = shell("dumpsys activity recents").lineSequence().filter { "* Recent #" in it && pkg in it }
            .mapNotNull { Regex("""Task\{\w+ #(\d+)""").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        fun await(label: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { label }; SystemClock.sleep(150) }
        }
        await("test display helper") { BackgroundDisplay.available }
        val existing = tasks()
        check(existing.values.any { it == 0 }) { "Open a calculator on the main display before running the probe" }
        val before = recents()
        repeat(2) { index ->
            val screen = BackgroundDisplay.open(launch)
            try {
                await("isolated calculator window") { screen.id in tasks().values }
                assertTrue("original window kept", tasks().entries.containsAll(existing.entries))
                val owned = tasks().filterValues { it == screen.id }.keys
                if (index == 1) {
                    assertTrue(screen.relaunch(pkg))
                    await("restarted capture window") { tasks().any { it.value == screen.id && it.key !in owned } }
                }
            } finally { screen.close() }
            await("owned tasks removed and main calculator preserved") { tasks() == existing && recents() == before }
        }
    }
}
