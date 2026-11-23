package com.clawmate.platform.gui

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.record.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** A model chooses relevance; the host can only publish text present in the cited candidate. */
internal class ChatCollectionEvidence(val goal: String, val fields: List<String>) {
    private val quotes = fields.map { linkedMapOf<String, String>() }
    val hasQuotes get() = quotes.any { it.isNotEmpty() }
    val complete get() = quotes.all { it.isNotEmpty() }
    val missing get() = fields.filterIndexed { i, _ -> quotes[i].isEmpty() }

    suspend fun review(lines: List<String>, source: String, ask: suspend (String, String) -> String): Boolean {
        if (lines.isEmpty()) return false
        val candidates = lines.map { Redactor.evidence(it).trim().take(400) }
            .filter { it.isNotBlank() && !advertisement.containsMatchIn(it) }.distinct()
        var changed = false
        for (page in candidates.chunked(24)) {
            val question = buildJsonObject {
                put("goal", goal)
                putJsonObject("questions") { fields.forEachIndexed { i, field -> put("Q${i + 1}", field) } }
                putJsonObject("candidates") { page.forEachIndexed { i, line -> put("T${i + 1}", line) } }
            }
            val answer = request { ask(SELECT, question.toString()) } ?: continue
            fields.indices.forEach { field ->
                val ids = answer["Q${field + 1}"] as? JsonArray ?: return@forEach
                val selected = ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                for ((index, original) in page.withIndex()) {
                    if (quotes[field].size >= 3) break
                    if ("T${index + 1}" in selected && quotes[field].putIfAbsent(original, source.take(80)) == null) {
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    fun recordLines(): List<String> {
        // Reserve a row for every field before adding second and later quotes.
        val rows = fields.indices.map { i ->
            quotes[i].entries.map { (q, source) -> "${fields[i]} · 「$q」 · $source" }
                .ifEmpty { listOf("${fields[i]} · 暂未找到可核实原文") }
        }
        val ordered = mutableListOf<String>()
        var chars = 0
        for (n in 0 until 3) for (group in rows) {
            val row = group.getOrNull(n) ?: continue
            if (chars + row.length > 6000) continue
            ordered.add(row)
            chars += row.length
        }
        return ordered
    }

    fun render(compact: Boolean = false): String = fields.indices.joinToString("\n\n") { i ->
        val rows = quotes[i]
        if (rows.isEmpty()) "${fields[i]} · 暂未找到可核实原文"
        else "${fields[i]}\n" + rows.entries.take(if (compact) 1 else 3).joinToString("\n") { (quote, source) ->
            val snippet = if (compact && quote.length > 120) quote.take(120) + "…" else quote
            "「$snippet」 · $source"
        }
    }

    companion object {
        private val advertisement = Regex("""^(?:\[(?:广告|推广|赞助|ad|sponsored)]|【(?:广告|推广|赞助)】|(?:广告|赞助推广|商业推广|sponsored|advertisement)\s*[:：])""", RegexOption.IGNORE_CASE)
        const val PLAN = "判断用户是否要求从手机页面搜集、查阅、比较或汇总信息。按用户原意拆出要分别回答的问题，保留指定对象、限制及计量维度；禁止凭空添加要求。只输出 JSON {\"fields\":[\"问题1\",\"问题2\"]}。仅导航、点击、设置、纯视觉判断颜色或图形、执行动作均返回空数组。最多8项。"
        const val SELECT = """你是原文筛选员。读目标和逐条编号的候选，分别回答每个问题。每项只选择符合目标对象、能直接支持该项的候选。口语或同义表达可以采用。忽略其他对象、广告和搜索回显。返回 JSON 对象，键是题号 (Q1 等)，值是候选号数组 (如 ["T1"])。找不到用空数组。"""

        fun visibleLines(text: String, inputs: List<String>, focused: String? = null): List<String> {
            val inputText = inputs.map { it.filterNot(Char::isWhitespace) }.toSet()
            val prefix = focused?.filterNot(Char::isWhitespace).orEmpty()
            return text.lineSequence().map(String::trim).filter {
                val normalized = it.filterNot(Char::isWhitespace)
                it.isNotEmpty() && !it.startsWith("当前 App:") && !it.startsWith("当前网页:") &&
                    it != "屏幕可见文字(OCR):" && normalized !in inputText &&
                    (prefix.isEmpty() || !normalized.startsWith(prefix))
            }.distinct().take(48).map { it.take(600) }.toList()
        }

        suspend fun plan(goal: String, ask: suspend (String, String) -> String): ChatCollectionEvidence? {
            val answer = request { ask(PLAN, goal) } ?: return null
            val array = answer["fields"] as? JsonArray ?: return null
            val fields = array.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content?.trim() }
                .filter { it.length in 1..100 && it.none(Char::isISOControl) }.distinct().take(8)
            return fields.takeIf { it.isNotEmpty() }?.let { ChatCollectionEvidence(goal, it) }
        }

        private suspend fun request(call: suspend () -> String): JsonObject? = try {
            withTimeoutOrNull(18_000) {
                val raw = call().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                if (raw.length > 32_000) null else Json.parseToJsonElement(raw) as? JsonObject
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        fun reader(model: ChatModel): suspend (String, String) -> String = { instruction, data ->
            val message = Message(Role.User, listOf(Part.Text(data)), Author.Mika)
            model.complete(instruction, listOf(message), emptyList()).parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        }
    }
}
