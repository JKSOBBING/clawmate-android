package com.clawmate.platform.gui

import org.junit.Assert.*
import org.junit.Test

class FloatingExcerptTest {
    @Test fun keepsOriginalArithmeticAndNewCanvasTextAheadOfControls() {
        val lines = FloatingExcerpts.content(listOf("清除", "7", "返回", "86,170", "网页正文"),
            listOf("86,147+23", "86,170", "网页 正文", "画布中的正文"), setOf("返回"))
        assertEquals(listOf("86,147+23", "86,170", "网页正文", "画布中的正文"), lines)
        assertEquals(4, FloatingExcerpts.scene("com.coloros.calculator", lines, emptyList()))
        assertEquals(8, FloatingExcerpts.scene("com.android.chrome", lines, emptyList()))
    }

    @Test fun acceptsOnlyCompleteArithmeticFromTheCalculatorDisplay() {
        assertEquals("86,147+23", FloatingExcerpts.arithmetic("86,147+23"))
        assertNull(FloatingExcerpts.arithmetic("note: 86,147+23"))
        assertNull(FloatingExcerpts.arithmetic("123456"))
        assertNull(FloatingExcerpts.arithmetic("123+456secret"))
    }

    @Test fun matchesAnActualCollectionStepAndItsConfiguredPackage() {
        val open = """{"id":1,"type":"gui_task","action":"collection_open_app","package_name":"org.example.browser"}"""
        val read = """{"id":2,"type":"gui_task","action":"collection_model_read"}"""
        val workflow = """{"steps":[$open,$read]}"""
        assertTrue(FloatingExcerpts.targets(workflow, "org.example.browser"))
        assertFalse(FloatingExcerpts.targets(workflow, "org.example"))
        assertFalse(FloatingExcerpts.targets("""{"steps":[$open]}""", "org.example.browser"))
        assertFalse(FloatingExcerpts.targets("""{"steps":[$read]}""", "org.example.browser"))
        assertEquals(5, FloatingExcerpts.scene("com.sina.weibo", emptyList(), listOf("读取微博热搜" to 5)))
    }
}
