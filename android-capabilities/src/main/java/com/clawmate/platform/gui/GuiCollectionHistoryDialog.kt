package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

internal class GuiCollectionHistoryDialog(
    private val activity: Activity,
    private val groupLabel: String?,
    private val load: () -> List<GuiCollection>,
    private val remove: (GuiCollection) -> Unit,
    private val lookup: (GuiCollection) -> Unit,
) {
    private val style = GuiCollectionStyle(activity)
    private val dialog = Dialog(activity)
    private val shell = style.card(compact = true)
    private val icons = mutableMapOf<String, Drawable.ConstantState?>()
    private var selected: GuiCollection? = null
    private var first = 0
    private var offset = 0
    private var records = emptyList<GuiCollection>()
    private var showAllCaptures = false
    private val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val fullDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private val zone = ZoneId.systemDefault()

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(shell)
        val window = checkNotNull(dialog.window)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = .38f }
        dialog.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && selected != null) {
                if (event.action == KeyEvent.ACTION_UP) showList()
                true
            } else false
        }
        showList()
        dialog.show()
        val frame = android.graphics.Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(frame)
        window.setLayout(minOf(dp(580), frame.width() - dp(20)), minOf(dp(720), (frame.height() * .88f).toInt()))
    }

    private fun dp(value: Int) = style.dp(value)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun space(height: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(height) }
    private fun date(record: GuiCollection) = record.displayDay(zone)
    private fun dayLabel(day: LocalDate): String = when (day) {
        LocalDate.now(zone) -> "今天"
        LocalDate.now(zone).minusDays(1) -> "昨天"
        else -> day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
    private fun section(text: String) = style.label(text, 14, true).apply { setPadding(0, dp(14), 0, dp(6)) }
    private fun reading(text: String) = style.label(text, 16, true).apply {
        typeface = android.graphics.Typeface.DEFAULT
        setLineSpacing(dp(4).toFloat(), 1f)
        setTextIsSelectable(true)
    }
    private fun count(record: GuiCollection) = when {
        record.floating != null -> "${record.floating.content.size} 条摘录"
        record.weibo != null -> "${record.weibo.entries.size} 条热搜"
        else -> "${record.pages} 页 · ${record.lines.size} 条文字"
    }

    private fun header(title: String, subtitle: String, back: Boolean) {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(3), 0, dp(12)) }
        row.addView(column().apply {
            addView(style.label(title, 22, true))
            addView(style.label(subtitle, 13), space(2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(style.action(if (back) "返回" else "关闭") { if (back) showList() else dialog.dismiss() })
        shell.addView(row)
    }

    private fun showList() {
        selected = null
        val all = load().sortedWith(compareByDescending<GuiCollection> { date(it) }.thenByDescending { it.capturedAt })
        val distinct = GuiCollectionHistory.distinctCaptures(all, zone)
        records = if (showAllCaptures) all else distinct
        shell.removeAllViews()
        header(groupLabel ?: "本机采集记录", "共 " + records.size + " 条记录", false)
        if (all.size > distinct.size) {
            val label = if (showAllCaptures) "收起相同采集" else "展开 " + (all.size - distinct.size) + " 次相同采集"
            shell.addView(style.action(label) { showAllCaptures = !showAllCaptures; first = 0; offset = 0; showList() })
        }
        if (records.isEmpty()) {
            shell.addView(style.label("暂无采集记录", 17, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        val list = ListView(activity).apply {
            divider = null
            adapter = RecordAdapter()
            setOnItemClickListener { _, _, position, _ ->
                first = firstVisiblePosition
                offset = getChildAt(0)?.top ?: 0
                showDetail(records[position])
            }
        }
        shell.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setSelectionFromTop(first.coerceAtMost(records.lastIndex), offset)
    }

    private inner class RecordAdapter : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? RecordRow ?: RecordRow()).apply { bind(position) }
    }

    private inner class RecordRow : LinearLayout(activity) {
        private val day = section("")
        private val icon = ImageView(activity)
        private val app = style.label("", 16, true)
        private val clock = style.label("", 13)
        private val preview = style.label("", 14)
        private val meta = style.label("", 12)
        init {
            orientation = VERTICAL
            addView(day)
            addView(LinearLayout(activity).apply {
                gravity = Gravity.TOP
                background = style.noteSurface()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
                addView(column().apply {
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        app.maxLines = 1; app.ellipsize = TextUtils.TruncateAt.END
                        addView(app, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                        addView(clock)
                    })
                    preview.maxLines = 2; preview.ellipsize = TextUtils.TruncateAt.END
                    addView(preview, space(4))
                    addView(meta, space(5))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }

        fun bind(position: Int) {
            val item = records[position]
            val d = date(item)
            day.visibility = if (position == 0 || date(records[position - 1]) != d) View.VISIBLE else View.GONE
            day.text = dayLabel(d)
            app.text = item.sourceLabel
            clock.text = time.format(Date(item.capturedAt))
            preview.text = item.summary.ifBlank { item.weibo?.lead?.title ?: item.lines.firstOrNull().orEmpty() }
            preview.visibility = if (preview.text.isBlank()) View.GONE else View.VISIBLE
            meta.text = count(item) + if (item.partial) " · 部分内容" else ""
            val pm = activity.packageManager
            val state = icons.getOrPut(item.sourcePackage) {
                runCatching { pm.getApplicationIcon(item.sourcePackage) }.getOrDefault(pm.defaultActivityIcon).constantState
            }
            icon.setImageDrawable(state?.newDrawable(activity.resources))
        }
    }

    private fun showDetail(record: GuiCollection) {
        selected = record
        shell.removeAllViews()
        val captured = fullDate.format(Date(record.capturedAt))
        header(record.sourceLabel, if (record.reviewDay.isEmpty()) captured else "回顾 " + record.reviewDay + " · 采集于 " + captured, true)
        val content = column().apply { setPadding(dp(6), 0, dp(6), dp(12)) }
        val scroll = ScrollView(activity).apply { addView(content) }
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(style.badge(count(record) + if (record.partial) " · 部分内容" else ""))
        val excerpt = record.floating
        val board = record.weibo
        when {
            excerpt != null -> {
                content.addView(section("摘录"))
                content.addView(reading(excerpt.content.joinToString("\n\n").ifBlank { "仅采集到界面控件" }))
                disclosure(content, "无障碍原文", record.lines)
                disclosure(content, "OCR 原文", excerpt.ocrLines, excerpt.ocrStatus)
            }
            board != null -> {
                content.addView(section("微博热搜 · ${board.date}"))
                content.addView(style.label(if (board.complete) "已核对主榜第 1 至 50 名" else "部分采集 · ${board.endReason}", 14))
                board.lead?.let { content.addView(section(it.title)); content.addView(reading(it.excerpt)) }
                content.addView(reading(board.entries.joinToString("\n\n") { it.display() }), space(10))
                disclosure(content, "采集原文", record.lines)
            }
            else -> {
                if (record.summary.isNotBlank()) { content.addView(section("摘要")); content.addView(reading(record.summary)) }
                content.addView(section("采集原文"))
                content.addView(reading(record.lines.joinToString("\n\n")))
            }
        }
        content.addView(section("来源应用"))
        content.addView(style.label(record.sourcePackage, 14).apply { setTextIsSelectable(true) })
        if (excerpt != null && excerpt.tasks.isNotEmpty()) {
            content.addView(section("采集时匹配的任务"))
            content.addView(style.label(excerpt.tasks.joinToString("\n"), 14))
        } else if (excerpt?.taskLookupComplete == false) content.addView(style.label("采集时任务列表暂不可读", 14))
        shell.addView(LinearLayout(activity).apply {
            addView(style.action("删除记录") { confirmDelete(record) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(style.action("查找采集任务", true) { lookup(record) }, LinearLayout.LayoutParams(0, -2, 1.35f))
        }, space(10))
    }

    private fun disclosure(content: LinearLayout, title: String, lines: List<String>, status: String = "") {
        val text = reading(lines.joinToString("\n")).apply { visibility = View.GONE }
        val name = "$title · ${lines.size} 条" + if (status.isEmpty()) "" else " · $status"
        val button = style.action(name) {}
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        fun update() { button.text = name + if (lines.isEmpty()) "" else if (text.visibility == View.VISIBLE) "  ▴" else "  ▾" }
        button.isEnabled = lines.isNotEmpty()
        button.setOnClickListener { text.visibility = if (text.visibility == View.VISIBLE) View.GONE else View.VISIBLE; update() }
        update()
        content.addView(button, space(10))
        content.addView(text, space(4))
    }

    private fun confirmDelete(record: GuiCollection) {
        AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle("删除这条本机采集记录？")
            .setMessage("${record.sourceLabel} · ${fullDate.format(Date(record.capturedAt))}")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runCatching { remove(record); showList() }.onFailure {
                    Toast.makeText(activity, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }
}
