package com.clawmate.shared

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.BackgroundDisplay
import com.clawmate.platform.gui.GuiControlService
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in acceptance on a paired test phone. The argument selects an installed app and real local records. */
class CollectionTaskDeviceTest {
    @Test fun homeReviewPersistsEachEnabledTaskWithoutTappingSave() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("collectionHomeReview") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = File(context.filesDir, "files/workflows")
        val index = File(root, "task-index.json")
        val selected = setOf("default_bilibili_history", "default_weibo_hot_search")
        val original = mutableMapOf<String, Boolean>()
        fun setFlags(edit: (JSONObject) -> Unit) {
            val doc = JSONObject(index.readText())
            val tasks = doc.getJSONArray("tasks")
            for (i in 0 until tasks.length()) edit(tasks.getJSONObject(i))
            val file = android.util.AtomicFile(index)
            val stream = file.startWrite()
            try { stream.write(doc.toString().toByteArray()); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
        val history = File(context.noBackupFilesDir, "gui-collections.json")
        val before = if (history.exists()) JSONArray(history.readText()).length() else 0
        val runs = File(root, "runs")
        val previous = runs.list().orEmpty().toSet()
        setFlags { task ->
            val id = task.getString("id")
            original[id] = task.getBoolean("enabled")
            task.put("enabled", id in selected)
        }
        var host: SharedActivity? = null
        try {
            assertTrue(original.keys.containsAll(selected))
            host = inst.startActivitySync(Intent(context, SharedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SharedActivity
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            try {
                var tapped = false
                val deadline = SystemClock.elapsedRealtime() + 45_000
                while (!tapped && SystemClock.elapsedRealtime() < deadline) {
                    if (host.hasWindowFocus() && BackgroundDisplay.available && GuiControlService.connected != null) {
                        val bitmap = ui.takeScreenshot()
                        if (bitmap != null) try {
                            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                            val result = com.google.android.gms.tasks.Tasks.await(recognizer.process(image), 8, java.util.concurrent.TimeUnit.SECONDS)
                            val button = result.textBlocks.flatMap { it.lines }.firstOrNull {
                                it.text.replace(" ", "").contains("回顾你的一天")
                            }?.boundingBox
                            if (button != null) {
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                    ui.executeShellCommand("input tap ${button.centerX()} ${button.centerY()}")).use { it.readBytes() }
                                tapped = true
                            }
                        } finally { bitmap.recycle() }
                    }
                    SystemClock.sleep(250)
                }
                assertTrue("Home review button was not reached", tapped)
            } finally { recognizer.close() }
            val deadline = SystemClock.elapsedRealtime() + 240_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val records = runs.listFiles().orEmpty().filter { it.name !in previous }
                    .mapNotNull { dir -> File(dir, "run_summary.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) } }
                for (record in records) assertEquals(record.optString("message"), "success", record.getString("status"))
                if (records.size == selected.size) {
                    assertEquals(selected, records.map { File(it.getString("workflow_file")).nameWithoutExtension }.toSet())
                    val day = java.time.LocalDate.now().toString()
                    val saved = JSONArray(history.readText())
                    assertTrue(saved.length() in before..before + selected.size)
                    val today = (0 until saved.length()).map(saved::getJSONObject).filter { it.optString("reviewDay") == day }
                    assertEquals(1, today.count { it.has("weibo") })
                    val identities = mutableSetOf<Pair<String, String>>()
                    for (batch in today.filter { !it.has("weibo") }) {
                        val rows = batch.getJSONArray("lines")
                        for (i in 0 until rows.length()) assertTrue("Repeated reviewed item",
                            identities.add(batch.getString("reviewTask") to rows.getString(i)))
                    }
                    for (run in records) {
                        val task = File(run.getString("workflow_file")).nameWithoutExtension
                        val text = run.optString("result_summary")
                        if (text.isEmpty()) continue
                        val path = File(root, "daily-log/$day/android-$task.md")
                        val log = path.readText().substringAfter("-->").lines().map { it.trim().replaceFirst(Regex("""^\d+\. """), "") }
                        for (line in text.lines().distinct()) assertEquals("Journal must contain each reviewed item once", 1, log.count { it == line })
                    }
                    inst.sendStatus(0, Bundle().apply { putString("review",
                        "2 tasks verified, native batches added=" + (saved.length() - before) +
                            ", items reported=" + records.map { it.optString("result_summary").lineSequence().count(String::isNotBlank) }) })
                    return
                }
                SystemClock.sleep(350)
            }
            fail("Home review failed to persist both enabled tasks")
        } finally {
            inst.runOnMainSync { host?.finish() }
            inst.waitForIdleSync()
            setFlags { task -> original[task.getString("id")]?.let { task.put("enabled", it) } }
        }
    }

    @Test fun taskUsesOneDisplayAndWritesOneRecord() {
        val args = InstrumentationRegistry.getArguments()
        val template = args.getString("collectionTemplate")
        assumeTrue(template in setOf("default_weibo_hot_search", "default_xhs_public_notes", "default_xiaohongshu_history",
            "default_bilibili_history", "default_ele_order"))
        val autoSave = args.getString("autoSave") == "true"
        val controls = args.getString("exerciseControls") == "true"
        val cancelPreview = args.getString("cancelPreview") == "true"
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val host = inst.startActivitySync(Intent(context, SharedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SharedActivity
        val bridge = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }.get(host) as PlatformBridge
        val raw = context.assets.open("arkui-x/entry/resources/rawfile/workflows/configs/$template.json").bufferedReader().use { it.readText() }
        val history = File(context.noBackupFilesDir, "gui-collections.json")
        fun size() = if (history.exists()) JSONArray(history.readText()).length() else 0
        fun node(label: String): AccessibilityNodeInfo? = ui.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName == context.packageName }
            .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
            .firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < end) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            fail("Timed out waiting for $label")
        }
        val before = size()
        await("host and virtual display helper") {
            host.hasWindowFocus() && BackgroundDisplay.available && GuiControlService.connected != null
        }
        val id = bridge.workflowStartCollection(raw, "android-" + System.currentTimeMillis() + "-1", autoSave, "")
        var sawPreview = false
        try {
            if (controls) {
                fun hasLiveFrame(): Boolean {
                    var ready = false
                    inst.runOnMainSync {
                        val service = GuiControlService.connected ?: return@runOnMainSync
                        val card = GuiControlService::class.java.getDeclaredField("displayCard").apply { isAccessible = true }.get(service)
                            ?: return@runOnMainSync
                        val image = card.javaClass.getDeclaredField("image").apply { isAccessible = true }.get(card) as android.widget.ImageView
                        val frame = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        ready = frame != null && frame.width > 0 && frame.height > 0 && image.isShown
                    }
                    return ready
                }

                fun click(label: String) {
                    await(label) { node(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                }
                fun frameWidth(): Int = ui.windows.firstOrNull { it.title?.toString() == "ClawMate 虚拟屏预览" }
                    ?.let { window -> Rect().also(window::getBoundsInScreen).width() } ?: 0
                await("expanded display") { node("放大虚拟屏") != null && frameWidth() > 0 && hasLiveFrame() }
                fun logoBounds(label: String): Rect? = node(label)?.let { Rect().apply { it.getBoundsInScreen(this) } }
                val logo = requireNotNull(logoBounds("收起虚拟屏"))
                val compact = frameWidth()
                click("放大虚拟屏")
                await("larger frame") { node("缩小虚拟屏") != null && frameWidth() > compact }
                assertEquals(logo, logoBounds("收起虚拟屏"))
                click("缩小虚拟屏")
                await("compact frame") { frameWidth() == compact }
                assertEquals(logo, logoBounds("收起虚拟屏"))
                click("隐藏")
                await("floating logo") { node("展开虚拟屏") != null && frameWidth() < compact }
                assertEquals(logo, logoBounds("展开虚拟屏"))
                click("展开虚拟屏")
                await("restored frame") { node("隐藏") != null && frameWidth() == compact }
                assertEquals(logo, logoBounds("收起虚拟屏"))
            }
            val end = SystemClock.elapsedRealtime() + 650_000
            while (SystemClock.elapsedRealtime() < end) {
                val poll = JSONObject(bridge.workflowPoll(id))
                assertFalse("Unexpected cloud request during local collection", poll.optJSONObject("tool") != null)
                if (poll.getString("status") == "done") {
                    val result = JSONObject(poll.getString("result"))
                    assertEquals(result.optString("message"), if (cancelPreview) "cancelled" else "success", result.getString("status"))
                    assertEquals(!autoSave, sawPreview)
                    if (autoSave) assertTrue(size() in before..before+1)
                    else assertEquals(before + if (cancelPreview) 0 else 1, size())
                    val lines = if (cancelPreview || autoSave && size() == before) 0 else JSONArray(history.readText()).getJSONObject(0).getJSONArray("lines").length()
                    if (!cancelPreview && !autoSave) assertTrue(lines > 0)
                    inst.sendStatus(0, Bundle().apply { putString("collection", "$template autoSave=$autoSave controls=$controls preview=$sawPreview savedLines=$lines") })
                    return
                }
                assertEquals("running", poll.getString("status"))
                val save = node("保存到本机")
                if (save != null) {
                    assertFalse("Unexpected per-task confirmation in daily review", autoSave)
                    sawPreview = true
                    val button = if (cancelPreview) node("取消") else save
                    assertTrue(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                }
                SystemClock.sleep(250)
            }
            fail("Collection timed out")
        } finally {
            bridge.workflowCancel(id)
            inst.runOnMainSync { host.finish() }
        }
    }
}
