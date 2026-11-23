package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.UiElement
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DailyReviewTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = DailyReview(LocalDate.of(2026, 11, 23), zone, "history")
    private val seen = ZonedDateTime.parse("2026-11-23T23:58:00+08:00[Asia/Shanghai]")
    private fun element(name: String, y: Int, left: Int = 10, click: Boolean = false, h: Int = 23) =
        UiElement(name, "TextView", name, 500, y + h/2, listOf(left, y, 955, y+h), null, click, false, false)

    @Test fun sameDayIsResolvedAtObservationAndComparedWithBatchStart() {
        assertTrue(day.matches("今天", seen))
        assertTrue(day.matches("2026年11月23日 14:28", seen))
        assertFalse(day.matches("2025-11-23 11:00", seen))
        assertFalse(day.matches("今天下单能发货吗", seen))
        val next = seen.plusMinutes(5)
        assertFalse(day.matches("今天", next))
        val late = GuiCollection("pkg", "历史", next.toInstant().toEpochMilli(), listOf("条目"), false, reviewDay = day.day.toString())
        assertEquals(day.day, late.displayDay(zone))
        assertTrue(day.matches("昨天 16:30", next))
        assertTrue(day.matches("10分钟前", next))
        assertFalse(day.matches("12:30", seen))
    }

    @Test fun historyDatesBelongToTheirOwnSectionsAndNeverCarryOntoAnUnknownPage() {
        val elems = listOf(element("今天", 160), element("片段A", 200, 420), element("昨天", 310),
            element("片段B", 365, 420), element("一周以前", 505), element("片段C", 550, 420))
        val titles = setOf("片段A", "片段B", "片段C")
        assertEquals(listOf("片段A"), day.page(elems, titles, seen))
        assertTrue(day.page(elems.takeLast(1), titles, seen).isEmpty())
    }

    @Test fun transactionDateAndContentMustBelongToTheSameCard() {
        val elems = listOf(element("订单甲", 200, click = true, h = 95), element("下单时间：2026-11-23 12:00", 230),
            element("订单乙", 310, click = true, h = 100), element("下单时间：昨天 14:20", 340),
            element("今天", 490), element("没有日期的订单", 540, click = true))
        val result = day.page(elems, null, seen)
        assertEquals(1, result.size)
        assertTrue(result.single().contains("订单甲"))
        assertFalse(result.single().contains("订单乙"))
    }

    @Test fun aChatBubbleCannotSupplyItsOwnDate() {
        fun row(time: String, text: String) = "- 位置：左侧｜发送人：A｜聊天对象：我｜时间原文：" + time + "｜原文：" + text
        val good = row("上午 10:05", "昨天提过的事")
        assertEquals(listOf("会话名称：A", good), day.chat(listOf("会话名称：A", row("未显示", "今天买的"),
            good, row("昨天 15:17", "今天出发")), seen))
    }

    @Test fun legacyIdenticalCapturesFoldOnlyWithinTheirLocalDay() {
        val t = seen.toInstant().toEpochMilli()
        val old = GuiCollection("pkg", "历史", t, listOf("内容"), false)
        val records = listOf(old, old.copy(capturedAt = t - 500), old.copy(capturedAt = t + 300_000), old.copy(lines = listOf("其他")))
        assertEquals(listOf(records[0], records[2], records[3]), GuiCollectionHistory.distinctCaptures(records, zone))
        assertEquals(4, records.size)
    }

    @Test fun repeatedAndOverlappingRunsOnlyWriteNewItemsAndKeepDistinctOrders() {
        val first = GuiCollection("pkg", "订单", 100, listOf("甲", "乙"), false,
            reviewDay = day.day.toString(), reviewTask = day.task, reviewIds = mapOf("甲" to "order-1"))
        val history = GuiCollectionHistory.mergeReview(emptyList(), first).first
        assertSame(history, GuiCollectionHistory.mergeReview(history, first.copy(capturedAt = 101)).first)
        val next = first.copy(lines = listOf("甲OCR异文", "乙", "丙"),
            reviewIds = mapOf("甲OCR异文" to "order-1", "丙" to "order-2"))
        val (updated, count) = GuiCollectionHistory.mergeReview(history, next)
        assertEquals(1, count)
        assertEquals(listOf("丙"), updated.first().lines)
        assertEquals(2, GuiCollectionHistory.mergeReview(history, first.copy(reviewDay = "2026-11-24")).second)
    }
}
