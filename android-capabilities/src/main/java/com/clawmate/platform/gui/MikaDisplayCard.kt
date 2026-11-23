package com.clawmate.platform.gui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Size
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.clawmate.platform.display.AgentDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The workflow retains its presentation while the same display moves between steps. */
internal class MikaPreviewState(var zoomed: Boolean = false, var hidden: Boolean = true)

internal class MikaDisplayCard(
    context: Context,
    private val target: String,
    val display: AgentDisplay,
    private val scope: CoroutineScope,
    private val state: MikaPreviewState,
    steer: (() -> Unit)?,
    stop: () -> Unit,
) : DockedPanelWindow(context, "ClawMate 虚拟屏预览", "展开虚拟屏") {
    private var aspect = display.width.toFloat() / display.height
    private var framesJob: Job? = null
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "$target · 虚拟屏实时画面"
    }
    override val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(style.dp(6), style.dp(3), style.dp(6), style.dp(4))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = style.dp(14).toFloat()
            setStroke(style.dp(1), Color.rgb(211, 218, 230))
        }
    }
    val status = TextView(context).apply {
        text = target
        textSize = 12f
        setTextColor(Color.rgb(36, 45, 64))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val zoom = action("+") {
        state.zoomed = !state.zoomed
        updateZoom()
        placePanel()
    }
    override val panelHeader = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        if (steer != null) addView(action("插话", steer))
        addView(action("停止", stop))
    }
    private val footer = LinearLayout(context).apply {
        addView(action("隐藏") { expand(false) }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(zoom, LinearLayout.LayoutParams(0, -1, 1f))
    }

    init {
        panelView.addView(panelHeader, LinearLayout.LayoutParams(-1, style.dp(48)))
        panelView.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        panelView.addView(footer, LinearLayout.LayoutParams(-1, style.dp(44)))
        logo.setOnClickListener { expand(!expanded) }
        dragFrom(image)
        dragFrom(status)
        dragFrom(logo)
        updateZoom()
        expand(!state.hidden, animate = false)
    }

    private fun action(label: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        textSize = 14f
        setTextColor(Color.rgb(64, 82, 180))
        minimumWidth = 0
        minWidth = 0
        setPadding(style.dp(6), 0, style.dp(6), 0)
        setOnClickListener { click() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFrames()
    }

    override fun onDetachedFromWindow() {
        framesJob?.cancel()
        framesJob = null
        image.setImageDrawable(null)
        super.onDetachedFromWindow()
    }

    private fun updateFrames() {
        if (state.hidden) {
            framesJob?.cancel()
            framesJob = null
            image.setImageDrawable(null)
        } else if (isAttachedToWindow && framesJob == null) {
            framesJob = scope.launch {
                display.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { frame ->
                    val next = frame.width.toFloat() / frame.height
                    if (next != aspect) {
                        aspect = next
                        placePanel()
                    }
                    image.setImageBitmap(frame)
                }
            }
        }
    }

    override fun onExpansionChanged() {
        state.hidden = !expanded
        logo.contentDescription = if (expanded) "收起虚拟屏" else "展开虚拟屏"
        updateFrames()
    }

    private fun updateZoom() {
        zoom.text = if (state.zoomed) "−" else "+"
        zoom.contentDescription = if (state.zoomed) "缩小虚拟屏" else "放大虚拟屏"
    }

    override fun panelSize(available: Size): Size {
        val metrics = resources.displayMetrics
        val maxWidth = if (state.zoomed) minOf(style.dp(320), (metrics.widthPixels * .76f).roundToInt())
            else minOf(style.dp(210), (metrics.widthPixels * .60f).roundToInt())
        val width = minOf(maxWidth, available.width)
        val maxHeight = (available.height * if (state.zoomed) .80f else .48f).roundToInt()
        val height = style.dp(99) + ((width - style.dp(12)).coerceAtLeast(1) / aspect).roundToInt()
        return Size(width, minOf(height, maxHeight))
    }
}
