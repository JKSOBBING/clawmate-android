package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.VisibleTextLine

/** Explicit dismissal labels from an app's launch advertisement, including a changing countdown. */
internal object CollectionSplash {
    private val skip = Regex("""(?:(?:\d{1,2}(?:秒|s)?)[|·]?)?(?:跳过(?:广告)?|关闭广告|skip(?:ad)?)(?:[|·]?(?:\d{1,2}(?:秒|s)?))?""", RegexOption.IGNORE_CASE)

    fun inCorner(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        right > left && bottom > top && right - left <= 360 && bottom - top <= 180 &&
            (left >= 600 || right <= 400) && (bottom <= 270 || top >= 745)

    fun isSkip(text: String): Boolean = skip.matches(text.filterNot { it.isWhitespace() || it in "()（）" })

    private fun matches(text: String, bounds: List<Int>) = bounds.size == 4 &&
        inCorner(bounds[0], bounds[1], bounds[2], bounds[3]) && isSkip(text)

    suspend fun dismiss(port: ScreenPort, screen: ScreenSnapshot, ocr: List<VisibleTextLine>,
        snapshot: suspend () -> ScreenSnapshot,
    ): Boolean {
        val skip = screen.elements.singleOrNull { !it.secret && !it.editable && matches(it.name, it.bounds) }
        if (skip != null) {
            val fresh = snapshot()
            val same = fresh.elements.singleOrNull {
                !it.secret && !it.editable && it.bounds == skip.bounds && matches(it.name, it.bounds)
            }
            if (same != null) port.tapRef(same.ref, fresh.snapshotId)
            return true
        }
        val label = ocr.singleOrNull { matches(it.text, it.bounds) } ?: return false
        val box = label.bounds
        if (port.readVisibleText().orEmpty().any { it.bounds == box && matches(it.text, box) }) {
            val fresh = snapshot()
            if (screen.width == fresh.width && screen.height == fresh.height && screen.elements == fresh.elements) {
                port.tapXY((box[0] + box[2]) * (fresh.width - 1) / 1998, (box[1] + box[3]) * (fresh.height - 1) / 1998)
            }
        }
        return true
    }
}
