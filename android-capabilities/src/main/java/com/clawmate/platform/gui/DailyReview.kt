package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.UiElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Compare the source's event date with the local day selected when the batch started. */
internal class DailyReview(val day: LocalDate, val zone: ZoneId, val task: String) {
    private val date = Regex("""(?:(\d{4})[-/.年])?(\d{1,2})[-/.月](\d{1,2})日?""")
    private val clock = Regex("""(?:凌晨|早上|上午|中午|下午|晚上)?\s*(?:[01]?\d|2[0-3])[:：][0-5]\d(?::[0-5]\d)?""")
    private val elapsed = Regex("""(\d{1,3})\s*(分钟|小时)前""")
    private val datedField = Regex("""^(?:下单时间|下单日期|交易时间|交易日期|支付时间|付款时间|浏览时间|观看时间|发布时间)\s*[:：]?\s*(.+)$""")
    private val dateBoundary = Regex("""(?:今天|今日|昨天|昨日|前天|更早.*|以前.*|.*之前|近.*天|最近.*|.*(?:天|周|星期|月|年)(?:内|以内|以前|前|以前的记录)|本周.*|上周.*|本月.*|上月.*|.*星期.*|.*周[一二三四五六日天].*|\d{1,4}[-/.年月].*)""")

    fun matches(label: String, observed: ZonedDateTime, chatClock: Boolean = false): Boolean {
        val s = label.trim().removePrefix("发布于").trim()
        val local = observed.withZoneSameInstant(zone)
        if (s == "刚刚" || chatClock && clock.matches(s)) return local.toLocalDate() == day
        elapsed.matchEntire(s)?.let {
            val n = it.groupValues[1].toLong()
            val latest = if (it.groupValues[2] == "分钟") local.minusMinutes(n) else local.minusHours(n)
            val earliest = if (it.groupValues[2] == "分钟") latest.minusMinutes(1) else latest.minusHours(1)
            return latest.toLocalDate() == day && earliest.plusNanos(1).toLocalDate() == day
        }
        val parts = s.replace(Regex("""(?<=日)(?=\d{1,2}:)"""), " ").split(Regex("""\s+"""), limit = 2)
        if (parts.size > 1 && !clock.matches(parts[1])) return false
        val d = when (parts[0]) {
            "今天", "今日" -> local.toLocalDate()
            "昨天", "昨日" -> local.toLocalDate().minusDays(1)
            "前天" -> local.toLocalDate().minusDays(2)
            else -> date.matchEntire(parts[0])?.let {
                runCatching {
                    val explicit = it.groupValues[1].toIntOrNull()
                    val d = LocalDate.of(explicit ?: local.year, it.groupValues[2].toInt(), it.groupValues[3].toInt())
                    if (explicit == null && d > local.toLocalDate()) d.minusYears(1) else d
                }.getOrNull()
            }
        }
        return d == day
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(zone)

    /** Date headers apply only to visible items below them on this history page. */
    fun page(elements: List<UiElement>, titles: Set<String>?, observed: ZonedDateTime): List<String> {
        val rows = elements.filter { !it.secret && !it.editable && !it.hint && it.name.isNotBlank() }
        val headers = rows.filter { !it.clickable && it.bounds[0] < 300 &&
            it.bounds[1] >= 95 && it.bounds[3] - it.bounds[1] in 5..90 &&
            (dateBoundary.matches(it.name.trim()) || date.matches(it.name.trim())) }.sortedBy { it.bounds[1] }
        if (titles == null) {
            val cards = rows.filter { it.clickable && it.bounds[1] >= 100 && it.bounds[3] <= 980 &&
                it.bounds[3] - it.bounds[1] in 20..400 && it.bounds[2] - it.bounds[0] > 250 }
            val records = linkedSetOf<String>()
            for (card in cards) {
                val inside = rows.filter { it.bounds[0] >= card.bounds[0] && it.bounds[2] <= card.bounds[2] &&
                    it.bounds[1] >= card.bounds[1] && it.bounds[3] <= card.bounds[3] }
                if (cards.any { it !== card && it in inside && it.bounds != card.bounds }) continue
                val text = inside.sortedWith(compareBy({ it.bounds[1] }, { it.bounds[0] })).flatMap { it.name.lines() }
                    .map(String::trim).filter(String::isNotEmpty).distinct()
                val times = text.mapNotNull { datedField.matchEntire(it)?.groupValues?.get(1) }
                if (times.isNotEmpty() && times.all { matches(it, observed) } && text.any { datedField.matchEntire(it) == null }) {
                    records += text.joinToString(" · ")
                }
            }
            return records.toList()
        }
        return rows.filter { item ->
            val name = item.name.trim()
            if (name !in titles || item in headers || item.bounds[1] < 120 ||
                item.bounds[3] > 975 || item.bounds[3] - item.bounds[1] > 580) return@filter false
            // Generic order/bill cards need their own labeled event time. History lists have date sections.
            val header = headers.lastOrNull { it.bounds[3] <= item.bounds[1] + 2 } ?: return@filter false
            matches(header.name, observed) && headers.none { it.bounds[1] in (item.bounds[1]+3) until item.bounds[3] }
        }.map { it.name.trim() }.distinct()
    }

    fun chat(lines: List<String>, observed: ZonedDateTime): List<String> {
        val marker = Regex("""^-\s*位置[:：].*?[｜|]发送人[:：].*?[｜|]聊天对象[:：].*?[｜|]时间原文[:：]([^｜|]+)[｜|]原文[:：].+""")
        val out = mutableListOf<String>()
        var type = ""
        var name = ""
        for (line in lines) {
            when {
                line.startsWith("会话类型：") -> { type = line; name = "" }
                line.startsWith("会话名称：") -> name = line
                else -> {
                    val match = marker.matchEntire(line.trim()) ?: continue
                    if (matches(match.groupValues[1], observed, chatClock = true)) {
                        if (type.isNotEmpty() && type !in out) out += type
                        if (name.isNotEmpty() && name !in out) out += name
                        out += line
                    }
                }
            }
        }
        return out.distinct()
    }

    companion object {
        fun at(millis: Long, task: String, zone: ZoneId = ZoneId.systemDefault()) =
            DailyReview(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), zone, task)
    }
}
