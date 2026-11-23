package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** One workflow owns the save policy for all of its readers. */
internal class CollectionResults(val review: DailyReview? = null) {
    val autoSave get() = review != null
    private val checkedLines = linkedSetOf<String>()
    private var newItems = 0

    fun receipt(result: com.clawmate.app.task.runner.WorkflowRunResult): com.clawmate.app.task.runner.WorkflowRunResult {
        val scope = review ?: return result
        if (result.status != com.clawmate.app.task.runner.RunStatus.SUCCESS) return result
        val text = checkedLines.joinToString("\n")
        val message = when {
            text.isEmpty() -> "未读到可核实属于 " + scope.day + " 的条目，已跳过保存"
            newItems == 0 -> "当天条目均已采集，没有新增"
            else -> "已保存 " + scope.day + " 的新增条目，共 $newItems 条"
        }
        return result.copy(message = message, resultSummary = text,
            summaryEntries = if (text.isEmpty()) emptyList() else listOf(com.clawmate.app.task.runner.WorkflowSummaryEntry(scope.day.toString(), text)))
    }

    var savedRecord = false
        private set

    suspend fun save(context: Context, record: GuiCollection, title: String? = null,
        note: String? = null, validate: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!autoSave) {
            val accepted = suspendCancellableCoroutine<Boolean> { continuation ->
                val style = GuiCollectionStyle(context)
                val message = listOfNotNull(record.description(), note, "保存后可在本机采集记录中查看。").joinToString("\n")
                var confirmed = false
                val dialog = style.sheet(title ?: "${record.sourceLabel}预览", message, "保存到本机",
                    content = style.recordContent(record), overlay = context is AccessibilityService, secure = true) {
                    confirmed = true
                }
                dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(confirmed) }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { dialog.dismiss() } }
            }
            if (!accepted) return@withContext false
        }
        currentCoroutineContext().ensureActive()
        validate()
        val scope = review
        if (scope != null) {
            savedRecord = true
            val board = record.weibo
            if (record.reviewDay != scope.day.toString() && (board == null || board.date != scope.day.toString())) return@withContext true
            if (record.lines.isEmpty()) return@withContext true
            val verified = record.copy(reviewDay = scope.day.toString(), reviewTask = scope.task)
            val (added, canonical) = GuiCollectionHistory.saveReview(context, verified)
            newItems += added
            checkedLines += board?.entries?.map { "微博实时热搜 · " + it.title } ?: canonical
        } else GuiCollectionHistory.save(context, record)
        savedRecord = true
        true
    }
}
