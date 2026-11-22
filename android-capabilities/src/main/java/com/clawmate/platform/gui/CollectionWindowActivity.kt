package com.clawmate.platform.gui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID

/** Own the temporary foreground task so stopping a collection can remove just that task. */
class CollectionWindowActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val owner = ForegroundCollectionWindow.current
        if (owner == null || owner.id != intent.identifier) { finishAndRemoveTask(); return }
        if (state == null) {
            try { startActivity(owner.target) }
            catch (error: Exception) {
                finishAndRemoveTask()
                GuiControlService.connected?.stop(error.message ?: "无法打开采集页面")
            }
        }
    }
}

internal class ForegroundCollectionWindow(private val context: Context, launch: Intent) : AutoCloseable {
    val id: String = UUID.randomUUID().toString()
    val target = Intent(launch).apply {
        addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        flags = flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED).inv()
    }

    fun open() {
        check(current == null)
        current = this
        context.startActivity(Intent(context, CollectionWindowActivity::class.java).setIdentifier(id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
    }

    override fun close() {
        if (current === this) current = null
        removeOrphans(context)
    }

    companion object {
        var current: ForegroundCollectionWindow? = null
            private set

        fun removeOrphans(context: Context) {
            val manager = context.getSystemService(ActivityManager::class.java)
            for (task in manager.appTasks) {
                val base = runCatching { task.taskInfo.baseIntent }.getOrNull() ?: continue
                if (base.component?.className == CollectionWindowActivity::class.java.name &&
                    base.identifier != null && base.identifier != current?.id) {
                    runCatching { task.finishAndRemoveTask() }
                }
            }
        }
    }
}
