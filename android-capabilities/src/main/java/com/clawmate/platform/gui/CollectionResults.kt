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
internal class CollectionResults(val autoSave: Boolean = false) {
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
        GuiCollectionHistory.save(context, record)
        savedRecord = true
        true
    }
}
