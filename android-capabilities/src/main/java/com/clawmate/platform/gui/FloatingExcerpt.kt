package com.clawmate.platform.gui

import android.content.Context
import kotlinx.serialization.Serializable
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

@Serializable
internal data class FloatingExcerpt(
    val content: List<String>,
    val ocrLines: List<String>,
    val ocrStatus: String,
    val scene: Int,
    val tasks: List<String>,
    val taskLookupComplete: Boolean = true,
)

internal object FloatingExcerpts {
    private val commonControls = setOf("返回", "换算", "科学计算器", "以浮窗显示计算器", "更多选项", "清除", "百分号", "退格",
        "除", "乘", "减", "加", "等于", "关闭", "更多", "分享", "搜索", "首页", "取消", "菜单", "收藏", "刷新",
        "back", "home", "menu", "share", "search", "more options", "ac", "c")
    private val keypad = Regex("""(?:\d{1,2}|[+\-×÷=.%±·−/（）()])""")
    private val calculation = Regex("""[\d,.，]+\s*[+×÷*/=−\-]\s*[\d,.，]+""")
    private val mathCharacters = Regex("""[0-9.,，+×÷*/=−\-()%（）\s]+""")
    fun arithmetic(value: String?): String? = value?.trim()?.takeIf {
        it.length in 3..180 && mathCharacters.matches(it) && calculation.containsMatchIn(it)
    }

    private fun key(line: String) = Normalizer.normalize(line, Normalizer.Form.NFKC).lowercase().filterNot(Char::isWhitespace)
    fun control(line: String, controls: Set<String>): Boolean =
        line in controls || line.lowercase() in commonControls || keypad.matches(line.trim())

    fun content(raw: List<String>, ocr: List<String>, controls: Set<String>): List<String> {
        val controlsByKey = controls.mapTo(mutableSetOf(), ::key)
        val seen = mutableSetOf<String>()
        val body = raw.filter { !control(it, controls) }.map(::key)
        val additions = ocr.filter { value ->
            val k = key(value)
            k.length > 1 && body.none { it.contains(k) }
        }
        return (raw + additions).filter { value ->
            val k = key(value)
            k.isNotBlank() && !control(value, controls) && k !in controlsByKey &&
                !value.matches(Regex("""[\p{P}\p{S}\s]+""")) && seen.add(k)
        }.sortedByDescending { calculation.containsMatchIn(it) }.take(80)
    }

    // WorkflowStorage persists the live task index. Edited tasks may override the embedded graph on disk.
    fun matches(context: Context, pkg: String): List<Pair<String, Int>> {
        val root = File(context.filesDir, "files/workflows")
        val index = File(root, "task-index.json")
        if (!index.exists()) return emptyList()
        check(index.isFile && index.length() <= 8_000_000) { "任务索引不可读" }
        val tasks = JSONObject(index.readText()).getJSONArray("tasks")
        val matches = mutableListOf<Pair<String, Int>>()
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            if (!task.optBoolean("enabled", true)) continue
            val name = task.optString("configFileName")
            var raw = task.optString("workflowJson")
            if ((task.optBoolean("isUserModified") || !task.optString("id").startsWith("default_") || raw.isBlank()) &&
                name.matches(Regex("""[^/\\:]+\.json"""))) {
                val config = File(File(root, "configs"), name)
                if (config.isFile && config.length() in 1..256000) raw = config.readText()
            }
            if (raw.length !in 1..256000) continue
            if (targets(raw, pkg)) {
                val title = task.optString("title").trim().take(120)
                if (title.isNotEmpty()) matches.add(title to task.optInt("sceneId", -1))
            }
        }
        return matches.distinct()
    }

    internal fun targets(raw: String, pkg: String): Boolean {
        val workflow = (WorkflowJsonParser.parse(raw) as? WorkflowJsonParser.Result.Ok)?.definition ?: return false
        val steps = workflow.steps
        if (pkg == XhsGuiWorkflow.PACKAGE && steps.any { it.action == XhsGuiWorkflow.ACTION })
            return runCatching { XhsGuiWorkflow.validate(steps) }.isSuccess
        if (pkg == WeiboGuiWorkflow.PACKAGE && steps.any { it.action == "weibo_open_board" })
            return runCatching { WeiboGuiWorkflow.validate(steps) }.isSuccess
        var opened = false
        var reads = false
        var inspected = 0
        val pending = ArrayDeque<WorkflowStep>().apply { addAll(steps) }
        while (pending.isNotEmpty() && ++inspected <= 256) {
            val step = pending.removeFirst()
            if (step.type == GuiCollectionWorkflow.TYPE || step.type == "gui_task") {
                if (step.action == GuiCollectionWorkflow.OPEN && (step.raw["package_name"] as? JsonPrimitive)?.content == pkg) opened = true
                if (step.action in setOf(GuiCollectionWorkflow.READ, GuiCollectionWorkflow.MODEL_READ)) reads = true
            }
            if (step.type == "tool" && (step.raw["tool_name"] as? JsonPrimitive)?.content == "vlm_qa" &&
                ((step.raw["inputs"] as? JsonObject)?.get("time_context") as? JsonPrimitive)?.content == "capture_only") reads = true
            pending.addAll(step.steps)
            pending.addAll(step.thenSteps)
            pending.addAll(step.elseSteps)
        }
        return pending.isEmpty() && opened && reads
    }

    fun scene(pkg: String, lines: List<String>, matches: List<Pair<String, Int>>): Int {
        val text = lines.joinToString("\n")
        val choices = matches.map { it.second }.filter { it in 0..6 }.distinct()
        if (choices.size == 1) return choices.first()
        if (pkg.contains("calculator")) return 4
        return when {
            Regex("外卖|骑手|送达时间").containsMatchIn(text) -> 1
            Regex("订单编号|实付款|购物车").containsMatchIn(text) -> 0
            Regex("登机口|航班号|酒店预订|车次").containsMatchIn(text) -> 6
            Regex("体检|血压|心率").containsMatchIn(text) -> 9
            Regex("会议纪要|工作周报|项目进度").containsMatchIn(text) -> 10
            else -> choices.firstOrNull() ?: 8
        }
    }
}
