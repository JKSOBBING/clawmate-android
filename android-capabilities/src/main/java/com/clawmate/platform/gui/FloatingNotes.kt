package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Size
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray

/** The home board publishes its current pending rows to the app-private store. */
object FloatingNotes {
    const val OPEN_HOME = "com.clawmate.action.OPEN_FLOATING_NOTE_HOME"
    private fun store(context: Context) = context.getSharedPreferences("floating-notes", Context.MODE_PRIVATE)
    fun enabled(context: Context) = store(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean("enabled", enabled).apply()
        GuiControlService.connected?.refreshFloatingNotes()
    }

    fun settings(activity: Activity) {
        val dialog = AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle("后台悬浮窗")
        if (android.os.Build.VERSION.SDK_INT < 34) {
            dialog.setMessage("悬浮便签需要 Android 14 或更高版本。").setPositiveButton("知道了", null).show()
            return
        }
        val enabled = enabled(activity)
        val connected = GuiControlService.connected != null
        val state = when {
            !enabled -> "已关闭"
            connected -> "已开启"
            else -> "等待无障碍服务连接"
        }
        val help = if (connected) "单击图标看提醒，双击采集当前页。拖动松手后会贴到最近的屏幕边缘，闲置 3 秒后图标变为半透明。"
            else "请在系统无障碍设置中开启「ClawMate 按次界面控制」，以便在桌面和其他应用上显示图标。"
        dialog.setMessage("$state\n\n$help").setNegativeButton("取消", null)
        if (enabled) {
            dialog.setPositiveButton("关闭悬浮窗") { _, _ -> setEnabled(activity, false) }
            if (!connected) dialog.setNeutralButton("去系统设置") { _, _ -> GuiControlEntry.settings(activity) }
        } else {
            dialog.setPositiveButton("开启悬浮窗") { _, _ ->
                setEnabled(activity, true)
                if (GuiControlService.connected == null) GuiControlEntry.settings(activity)
            }
        }
        dialog.show()
    }

    fun update(context: Context, raw: String) {
        require(raw.length <= 12000)
        val items = JSONArray(raw)
        require(items.length() <= 32)
        store(context).edit().putString("rows", raw).apply()
        GuiControlService.connected?.refreshFloatingNotes()
    }

    internal fun lines(context: Context, now: Long): List<String> {
        val rows = JSONArray(store(context).getString("rows", "[]"))
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val until = row.optLong("until")
            row.optString("text").replace(Regex("\\s+"), " ").trim().take(90)
                .takeIf { it.isNotEmpty() && (until == 0L || until > now) }
        }
    }
}

internal class FloatingNoteWindow(context: Context, capture: () -> Unit) :
    DockedPanelWindow(context, "ClawMate 悬浮便签", "ClawMate 便签，单击看提醒，双击采集当前页") {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(rows) }
    override val panelHeader = style.label("提醒事项", 16, true).apply {
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { openHome() }
    }
    override val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.noteSurface()
        setPadding(style.dp(12), style.dp(4), style.dp(12), style.dp(10))
        addView(panelHeader, LinearLayout.LayoutParams(-1, style.dp(48)))
        addView(scroll, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { openHome() }
    }

    init {
        logo.setOnClickListener { expand(!expanded) }
        dragFrom(logo, capture)
    }

    override fun onExpansionChanged() {
        logo.contentDescription = if (expanded) "收起提醒，双击采集当前页" else "ClawMate 便签，单击看提醒，双击采集当前页"
        if (expanded) refresh()
    }

    private fun openHome() {
        if (!expanded) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.action = FloatingNotes.OPEN_HOME
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
            expand(false)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "暂时无法打开 ClawMate 首页", Toast.LENGTH_SHORT).show()
        }
    }

    fun refresh() {
        if (!expanded) return
        val lines = FloatingNotes.lines(context, System.currentTimeMillis())
        rows.removeAllViews()
        for (line in lines.ifEmpty { listOf("暂无待办提醒") }) {
            rows.addView(style.label(line, 13).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, style.dp(4), 0, style.dp(2))
                setOnClickListener { openHome() }
            })
        }
        placePanel()
    }

    override fun panelSize(available: Size): Size {
        val width = minOf(style.dp(220), available.width)
        val body = (width - panelView.paddingLeft - panelView.paddingRight).coerceAtLeast(1)
        rows.measure(MeasureSpec.makeMeasureSpec(body, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        val chrome = style.dp(48) + panelView.paddingTop + panelView.paddingBottom
        val height = minOf(rows.measuredHeight, style.dp(120), (available.height - chrome).coerceAtLeast(0))
        if (scroll.layoutParams.height != height) scroll.layoutParams = scroll.layoutParams.apply { this.height = height }
        return Size(width, chrome + height)
    }
}
