@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.platform.gui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollectionHistoryDialogDeviceTest {
    @Test fun readsExcerptExpandsOriginalsAndReturnsToList() {
        val examples = listOf(
            GuiCollection("com.android.chrome", "Chrome", System.currentTimeMillis(), listOf("本周读书会", "周五下午三点，城市图书馆二楼。"), false,
                summary = "本周读书会 · 周五下午三点，城市图书馆二楼。",
                floating = FloatingExcerpt(listOf("本周读书会", "周五下午三点，城市图书馆二楼。"), listOf("读书会报名截止周四"), "已补读", 8, emptyList())),
            GuiCollection("com.coloros.calculator", "计算器", System.currentTimeMillis() - 75 * 60_000, listOf("86147+23", "86,170"), false,
                summary = "86147+23 · 86,170"),
            GuiCollection("com.android.deskclock", "时钟", System.currentTimeMillis() - 86_400_000, listOf("阅读计时 · 25 分钟"), false),
        )
        fun all(v: View): Sequence<View> = sequence {
            yield(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(all(v.getChildAt(i)))
        }
        lateinit var view: View
        var lookedUp = false
        ActivityScenario.launch(GuiControlSetupActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                GuiCollectionHistoryDialog(a, null, { examples }, { error("unexpected deletion") }, { lookedUp = true }).show()
                view = WindowInspector.getGlobalWindowViews().single { root ->
                    all(root).filterIsInstance<ListView>().any { it.adapter?.count == examples.size } &&
                        all(root).filterIsInstance<TextView>().any { it.text == "本机采集记录" }
                }
            }
            fun capture(file: String) = scenario.onActivity { a ->
                check(view.width > 0 && view.height > 0)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    File(a.cacheDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
            capture("history-synthetic-list.png")
            scenario.onActivity {
                val list = all(view).filterIsInstance<ListView>().single()
                list.performItemClick(list.getChildAt(0), 0, 0)
                assertTrue(all(view).filterIsInstance<TextView>().any { it.text == "摘录" })
            }
            capture("history-synthetic-detail.png")
            scenario.onActivity {
                fun button(label: String) = all(view).filterIsInstance<TextView>().first { it.text.startsWith(label) }
                button("OCR 原文").performClick()
                assertTrue(button("读书会报名截止周四").isShown)
                button("查找采集任务").performClick()
                assertTrue(lookedUp)
                button("返回").performClick()
                assertEquals(3, all(view).filterIsInstance<ListView>().single().adapter.count)
            }
        }
    }
}
