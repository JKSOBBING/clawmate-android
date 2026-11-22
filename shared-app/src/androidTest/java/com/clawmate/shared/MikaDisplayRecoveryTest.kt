package com.clawmate.shared

import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.BackgroundDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device regression for the authorized Weibo test task. Requires the USB display helper. */
class MikaDisplayRecoveryTest {
    @Test fun coldLaunchStaysOnTheExistingVirtualDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!BackgroundDisplay.available && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("display helper connected", BackgroundDisplay.available)
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage("com.sina.weibo"))
        val ui = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        fun mainTasks() = shell("cmd activity stack list").split("RootTask ").filter { it.lineSequence().first().contains("displayId=0 ") }
            .flatMap { root -> Regex("""taskId=(\d+): com\.sina\.weibo/""").findAll(root).map { it.groupValues[1] }.toList() }.toSet()
        fun recentTasks() = shell("dumpsys activity recents").lineSequence().filter { "* Recent #" in it && "com.sina.weibo" in it }
            .mapNotNull { Regex("""Task\{\w+ #(\d+)""").find(it)?.groupValues?.get(1) }.toSet()
        val before = mainTasks() to recentTasks()
        val display = BackgroundDisplay.open(intent)
        try {
            assertTrue(display.id > 0)
            assertFalse(display.relaunch("com.android.settings"))
            assertTrue(display.relaunch("com.sina.weibo"))
            val activity = shell("dumpsys activity activities")
            val section = activity.substringAfter("Display #${display.id} ").substringBefore("Display #")
            assertTrue("Weibo task remains on the same display", section.contains("com.sina.weibo"))
        } finally {
            display.close()
        }
        // Allow ActivityTaskManager to finish removal and Weibo's queued splash redirects to arrive.
        Thread.sleep(2_000)
        assertEquals("No redirected Weibo task on the main display or in recents", before, mainTasks() to recentTasks())
    }
}
