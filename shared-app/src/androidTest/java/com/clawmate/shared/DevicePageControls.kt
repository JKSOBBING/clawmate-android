package com.clawmate.shared

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Locate a rendered ArkUI row by its own text, including on devices with sparse accessibility nodes. */
internal object DevicePageControls {
    fun open(label: String) = locate(label, tap = true)
    fun expect(label: String) = locate(label, tap = false)
    private fun locate(requestedLabel: String, tap: Boolean) {
        val label = requestedLabel.filterNot(Char::isWhitespace)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val hostPackage = instrumentation.targetContext.packageName
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        fun checkHost() {
            check(ui.rootInActiveWindow?.packageName?.toString() == hostPackage) {
                "Test navigation lost the ClawMate window"
            }
        }
        fun shell(command: String) {
            checkHost()
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            repeat(10) { attempt ->
                checkHost()
                val image = ui.takeScreenshot() ?: error("Missing test window")
                try {
                    val lines = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS)
                        .textBlocks.flatMap { it.lines }
                    val boxes = mutableListOf<Rect>()
                    val fragments = lines.mapNotNull { line ->
                        line.boundingBox?.let { line.text.filter(Char::isLetterOrDigit) to it }
                    }
                    for ((first, bounds) in fragments) {
                        if (first.isEmpty()) continue
                        if (first == label) boxes.add(Rect(bounds))
                        if (first.length == label.length + 1 && (first.endsWith(label) || first.startsWith(label))) boxes.add(Rect(bounds))
                        if (!label.startsWith(first) || first == label) continue
                        for ((second, next) in fragments) {
                            val nearby = next.top in (bounds.bottom - bounds.height() / 2)..(bounds.bottom + bounds.height())
                            val aligned = next.centerX() in bounds.left..bounds.right || bounds.centerX() in next.left..next.right
                            if (nearby && aligned && first + second == label) boxes.add(Rect(bounds).apply { union(next) })
                        }
                    }
                    for (line in lines) {
                        if (line.text.filterNot(Char::isWhitespace) == label) line.boundingBox?.let(boxes::add)
                        for (start in line.elements.indices) {
                            val bounds = Rect()
                            var text = ""
                            for (part in line.elements.drop(start)) {
                                text += part.text.filterNot(Char::isWhitespace)
                                part.boundingBox?.let(bounds::union)
                                if (text == label && !bounds.isEmpty) boxes.add(Rect(bounds))
                                if (!label.startsWith(text) || text == label) break
                            }
                        }
                    }
                    // Coloured buttons occasionally yield one incorrect glyph in local Chinese OCR.
                    // Prefer any exact match and require a single unambiguous full-length candidate.
                    val bounds = boxes.firstOrNull() ?: accessibleBounds(ui.rootInActiveWindow, label, image.width, image.height) ?: fragments.singleOrNull { (word, _) ->
                        word.length == label.length && (label.length >= 4 ||
                            (!tap && label.length == 3 && word.first() == label.first() && word.last() == label.last())) &&
                            word.indices.count { word[it] != label[it] } == 1
                    }?.second ?: fragments.mapNotNull { (word, line) ->
                        // Adjacent Chinese tabs are sometimes returned as one unspaced line.
                        val start = word.indexOf(label)
                        if (label.length < 3 || start < 0 || start != word.lastIndexOf(label)) return@mapNotNull null
                        Rect(line.left + line.width() * start / word.length, line.top,
                            line.left + line.width() * (start + label.length) / word.length, line.bottom)
                    }.singleOrNull()
                    if (bounds != null) {
                        if (tap) {
                            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                            Thread.sleep(400)
                        }
                        return
                    }
                    if (attempt >= 2) {
                        val x = image.width / 2
                        shell("input swipe $x ${image.height * 3 / 4} $x ${image.height * 2 / 5} 350")
                    }
                } finally { image.recycle() }
                Thread.sleep(380)
            }
            error("Rendered page control missing: $label")
        } finally { recognizer.close() }
    }

    private fun accessibleBounds(node: AccessibilityNodeInfo?, label: String, width: Int, height: Int): Rect? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString()?.filterNot(Char::isWhitespace) == label ||
                node.contentDescription?.toString()?.filterNot(Char::isWhitespace) == label)) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.intersect(0, 0, width, height) && !bounds.isEmpty) return bounds
        }
        for (i in 0 until node.childCount) accessibleBounds(node.getChild(i), label, width, height)?.let { return it }
        return null
    }
}
