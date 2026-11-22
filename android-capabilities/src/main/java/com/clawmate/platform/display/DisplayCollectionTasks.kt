package com.clawmate.platform.display

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock

/** Used only by the authorized display host. Task ids belong to one virtual display session. */
internal class DisplayCollectionTasks(context: Context, private val pkg: String, private val displayId: Int) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val oldIds: Set<Int>
    private val taskDisplay = android.app.TaskInfo::class.java.getField("displayId")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private val remove = service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)

    init {
        @Suppress("DEPRECATION")
        val recent = am.getRecentTasks(Int.MAX_VALUE, ActivityManager.RECENT_WITH_EXCLUDED)
        oldIds = recent.mapTo(mutableSetOf()) { it.persistentId } + running().map { it.taskId }
    }

    @Suppress("DEPRECATION")
    private fun running() = am.getRunningTasks(Int.MAX_VALUE)

    /** Weibo's launcher posts a second start. Keep its display alive until that handoff is visible. */
    fun awaitLauncherRedirect(component: ComponentName?) {
        if (component?.flattenToShortString() != "com.sina.weibo/.SplashActivity") return
        val deadline = SystemClock.elapsedRealtime() + 3_500
        do {
            if (running().any { task ->
                    taskDisplay.getInt(task) == displayId && task.topActivity?.let {
                        it.packageName == pkg && it != component
                    } == true
                }) return
            SystemClock.sleep(80)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    fun closeWindows() {
        val own = running().filter { task ->
            task.taskId !in oldIds && taskDisplay.getInt(task) == displayId &&
                (task.baseIntent.component?.packageName ?: task.baseActivity?.packageName) == pkg
        }
        for (task in own) remove.invoke(service, task.taskId)
    }
}
