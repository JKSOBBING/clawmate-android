package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One explicit gesture captures one focused application window, using the OS secure-window check. */
@RequiresApi(34)
internal class FloatingPageCapture(private val service: AccessibilityService) {
    private data class Page(val id: Int, val pkg: String, val root: AccessibilityNodeInfo, val bounds: Rect, val content: Rect) {
        fun sameWindow(other: Page) = id == other.id && pkg == other.pkg && bounds == other.bounds && content == other.content

        fun cropContent(image: Bitmap): Bitmap {
            val region = content
            val window = bounds
            val scaleX = image.width.toDouble() / window.width()
            val scaleY = image.height.toDouble() / window.height()
            val left = kotlin.math.ceil((region.left - window.left) * scaleX).toInt()
            val top = kotlin.math.ceil((region.top - window.top) * scaleY).toInt()
            val right = ((region.right - window.left) * scaleX).toInt().coerceAtMost(image.width)
            val bottom = ((region.bottom - window.top) * scaleY).toInt().coerceAtMost(image.height)
            check(right > left && bottom > top) { "当前页面没有可采集的区域" }
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }
    }
    private fun page(): Page {
        check(!service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
        val windows = service.windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty()
        val window = windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: error("请先打开要采集的应用页面")
        val root = window.root ?: error("当前页面暂时无法读取")
        val pkg = root.packageName?.toString() ?: error("当前应用暂时无法读取")
        val bounds = Rect().also(window::getBoundsInScreen)
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content = Rect(metrics.bounds).apply { inset(insets) }
        check(content.intersect(bounds)) { "当前页面没有可采集的区域" }
        check(windows.none { other -> other.layer > window.layer && other.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            Rect.intersects(content, Rect().also(other::getBoundsInScreen)) }) { "请收起键盘、通知或系统弹窗后再采集" }
        val pm = service.packageManager
        val isLaunchable = pm.getLaunchIntentForPackage(pkg) != null || pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName == pkg
        check(pkg !in setOf("com.android.settings", "com.android.systemui", "android") &&
            !pkg.contains("permissioncontroller") && isLaunchable) {
            "请在要采集的应用中双击悬浮窗"
        }
        MikaAppAccess.requireAllowed(service, pkg)
        return Page(window.id, pkg, root, bounds, content)
    }

    private suspend fun snapshot(id: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!continuation.isActive) return
                    runCatching {
                        check(buffer.width.toLong() * buffer.height in 1L..16_000_000L) { "页面图像过大" }
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("当前页面无法截图")
                        try { wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: error("页面图像不可读") }
                        finally { wrapped.recycle() }
                    }.onSuccess { bitmap -> continuation.resume(bitmap) { bitmap.recycle() } }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("当前页面受保护或暂时无法采集"))
            }
        })
    }

    private data class Layout(val masks: List<Rect>, val buttons: Set<String>, val visual: Boolean, val complete: Boolean, val body: Rect?, val expression: String?)

    private fun layout(page: Page): Layout {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(page.root) }
        val masked = mutableListOf<Rect>()
        val buttons = mutableSetOf<String>()
        val colorOsCalculator = page.pkg == "com.coloros.calculator"
        var expression: String? = null
        var body: Rect? = null
        var visual = false
        var inspected = 0
        while (queue.isNotEmpty()) {
            if (++inspected > 1024) return Layout(masked, buttons, visual, false, body, expression)
            val node = queue.removeFirst()
            if (!node.isVisibleToUser) continue
            val box = Rect().also(node::getBoundsInScreen)
            val id = node.viewIdResourceName.orEmpty().substringAfter('/')
            val role = node.className?.toString().orEmpty()
            if (colorOsCalculator && node.packageName?.toString() == page.pkg && !node.isPassword && !node.isAccessibilityDataSensitive) {
                if (id in setOf("calculator_frame", "formula_container", "result") && Rect.intersects(box, page.content)) {
                    val area = Rect(box).apply { intersect(page.content) }
                    if (body == null) body = area else body.union(area)
                }
                // ColorOS exposes the visible arithmetic display as an editable TextView.
                // Keep its pixels masked and admit only the calculator widget's complete numeric expression.
                if (id == "formula" && role == "android.widget.TextView" && Rect.intersects(box, page.content)) {
                    expression = FloatingExcerpts.arithmetic(node.text?.toString())
                }
            }
            if (node.isPassword) return Layout(masked, buttons, visual, false, body, expression)
            if (node.isEditable || node.isAccessibilityDataSensitive || node.packageName?.toString() != page.pkg) {
                if (box.isEmpty) return Layout(masked, buttons, visual, false, body, expression)
                if (box.intersect(page.content)) masked.add(box)
                continue
            }
            val candidate = role.endsWith("WebView")
            if (candidate && box.intersect(page.content) && (body == null || box.width().toLong() * box.height() > body.width().toLong() * body.height())) body = Rect(box)
            if (role.endsWith("Button")) {
                listOf(node.text, node.contentDescription).mapNotNullTo(buttons) { it?.toString()?.trim() }
            }
            if ((role.endsWith("WebView") || role.endsWith("ImageView") || role.endsWith("SurfaceView") ||
                    role.endsWith("TextureView") || role.endsWith("View") && node.childCount == 0 && node.text.isNullOrBlank()) &&
                box.width() >= page.content.width() / 3 && box.height() >= page.content.height() / 12) visual = true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: return Layout(masked, buttons, visual, false, body, expression)
                queue.addLast(child)
            }
        }
        return Layout(masked, buttons, visual, true, body, expression)
    }

    private fun recognize(page: Page, image: Bitmap, masks: List<Rect>): List<String> {
        val mutable = image.copy(Bitmap.Config.ARGB_8888, true) ?: error("无法读取截图")
        try {
            val canvas = Canvas(mutable)
            val paint = Paint().apply { color = Color.BLACK }
            val sx = mutable.width.toFloat() / page.bounds.width()
            val sy = mutable.height.toFloat() / page.bounds.height()
            for (rect in masks) {
                canvas.drawRect((rect.left - page.bounds.left) * sx - 2, (rect.top - page.bounds.top) * sy - 2,
                    (rect.right - page.bounds.left) * sx + 2, (rect.bottom - page.bounds.top) * sy + 2, paint)
            }
            val cropped = page.cropContent(mutable)
            try {
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                try {
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(cropped, 0)), 10, TimeUnit.SECONDS)
                    return result.textBlocks.flatMap { block -> block.lines.map { it.text.trim() } }.filter(String::isNotEmpty)
                } finally { recognizer.close() }
            } finally { if (cropped !== mutable) cropped.recycle() }
        } finally { mutable.recycle() }
    }

    suspend fun save(scope: CoroutineScope, stillIdle: () -> Boolean): GuiCollection {
        val before = page()
        val initial = layout(before)
        val image = snapshot(before.id)
        val record = try {
            val current = page()
            check(stillIdle() && before.sameWindow(current)) { "页面已切换，请重试" }
            val label = service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(current.pkg, 0)).toString()
            val visible = GuiCollection.read(scope, current.root, current.content, current.pkg, label, null).record
            val info = layout(current)
            val body = info.body
            val expression = info.expression?.takeIf { it == initial.expression }
            val bodyLines = listOfNotNull(expression) + if (body == null) visible.lines else
                GuiCollection.read(scope, current.root, body, current.pkg, label, null).record.lines
            val accessible = FloatingExcerpts.content(bodyLines, emptyList(), info.buttons)
            val needsOcr = visible.partial || info.visual || accessible.count { it.length >= 16 } < 3 ||
                accessible.size * 2 <= visible.lines.size
            val masks = initial.masks + info.masks
            var status = "无需补读"
            var ocr = emptyList<String>()
            if (needsOcr) {
                if (!initial.complete || !info.complete || masks.any { it.contains(current.content) }) status = "隐私区域已跳过"
                else {
                    try {
                        ocr = withContext(Dispatchers.IO) { recognize(if (body == null) current else current.copy(content = body), image, masks) }
                        status = if (ocr.isEmpty()) "未识别到文字" else "已补读"
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { status = "补读失败" }
                }
            }
            var truncated = visible.partial
            var chars = 0
            val original = mutableListOf<String>()
            for (line in ocr) {
                if (original.size >= 80 || chars >= 6000) { truncated = true; break }
                val part = line.take(minOf(600, 6000 - chars))
                if (part.length < line.length) truncated = true
                chars += part.length
                original.add(part)
            }
            val useful = FloatingExcerpts.content(bodyLines, original, info.buttons)
            check(visible.lines.isNotEmpty() || original.isNotEmpty()) { "当前页没有读到可保存的文字" }
            val lookup = withContext(Dispatchers.IO) { runCatching { FloatingExcerpts.matches(service, current.pkg) } }
            val tasks = lookup.getOrDefault(emptyList())
            val excerpt = FloatingExcerpt(useful, original, status, FloatingExcerpts.scene(current.pkg, useful, tasks), tasks.map { it.first }, lookup.isSuccess)
            visible.copy(lines = (listOfNotNull(expression) + visible.lines).distinct().take(80), summary = floatingSummary(useful).ifBlank { "仅采集到界面控件" }, partial = truncated, floating = excerpt)
        } finally { image.recycle() }
        val finalPage = page()
        check(stillIdle() && before.sameWindow(finalPage)) { "页面已切换，请重试" }
        withContext(Dispatchers.IO) { GuiCollectionHistory.save(service, record) }
        return record
    }
}

internal fun floatingSummary(lines: List<String>): String {
    val fragments = lines.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.distinct()
    return fragments.filter { it.length >= 4 }.ifEmpty { fragments }.take(4).joinToString(" · ") { it.take(72) }
}
