package com.clawmate.platform.gui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** The logo owns a fixed window. Only the panel underneath it is clipped during a transition. */
internal abstract class DockedPanelWindow(context: Context, title: String, logoLabel: String) : FrameLayout(context) {
    protected val style = GuiCollectionStyle(context)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val anchor = FloatingPosition(context, moved = ::placePanel)
    protected val logo = floatingLogo(context, logoLabel)
    private val size = style.dp(48)
    private fun params(name: String) = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply {
        this.title = name
        if (android.os.Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
    }
    private val handleParams = params("${title}图标")
    private val panelParams = params(title)
    protected abstract val panelView: View
    protected abstract val panelHeader: View
    protected abstract fun panelSize(available: Size): Size
    protected var expanded = false
        private set
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var panelAdded = false

    init { addView(logo, LayoutParams(size, size)) }

    protected fun dragFrom(view: View, doubleTap: (() -> Unit)? = null) =
        anchor.gestures(view, this, handleParams, doubleTap)

    fun show() {
        anchor.place(handleParams)
        panelView.visibility = View.INVISIBLE
        panelParams.width = 1
        panelParams.height = 1
        manager.addView(panelView, panelParams)
        try {
            manager.addView(this, handleParams)
        } catch (failure: RuntimeException) {
            manager.removeViewImmediate(panelView)
            throw failure
        }
    }

    fun close() {
        if (isAttachedToWindow) manager.removeViewImmediate(this)
    }

    protected open fun onExpansionChanged() {}

    protected fun expand(value: Boolean, animate: Boolean = true) {
        if (expanded == value) return
        expanded = value
        animator?.cancel()
        onExpansionChanged()
        if (!isAttachedToWindow) return
        if (expanded) placePanel()
        val end = if (expanded) 1f else 0f
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || end == progress) {
            drawFraction(end)
            return
        }
        animator = ValueAnimator.ofFloat(progress, end).apply {
            duration = (185 * abs(end - progress)).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator()
            addUpdateListener { drawFraction(it.animatedValue as Float) }
            start()
        }
    }

    protected fun placePanel() {
        if (!isAttachedToWindow || (!expanded && !panelAdded)) return
        val screen = anchor.viewport()
        val available = Size(screen.width.coerceAtLeast(1), screen.height.coerceAtLeast(1))
        val desired = panelSize(available)
        val width = desired.width.coerceIn(1, available.width)
        // At the bottom, move both together just enough to keep the header and controls usable.
        val minHeight = minOf(desired.height, style.dp(176), available.height)
        val y = handleParams.y.coerceIn(0, available.height - minHeight)
        if (y != handleParams.y) {
            handleParams.y = y
            manager.updateViewLayout(this, handleParams)
        }
        val height = desired.height.coerceIn(1, available.height - y)
        val x = handleParams.x
        val gravity = handleParams.gravity
        val right = gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT
        val clearance = size + style.dp(2)
        panelHeader.setPadding(if (right) 0 else clearance, 0, if (right) clearance else 0, 0)
        if (!panelAdded || panelParams.width != width || panelParams.height != height ||
            panelParams.gravity != gravity || panelParams.x != x || panelParams.y != y) {
            panelParams.width = width
            panelParams.height = height
            panelParams.gravity = gravity
            panelParams.x = x
            panelParams.y = y
            applyClip()
            manager.updateViewLayout(panelView, panelParams)
            panelView.visibility = View.VISIBLE
            panelAdded = true
        }
    }

    private fun applyClip() {
        val width = panelParams.width
        val visible = (width * progress).roundToInt()
        val left = if (panelParams.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) width - visible else 0
        panelView.clipBounds = if (progress == 1f) null else Rect(left, 0, left + visible, panelParams.height)
        panelView.importantForAccessibility = if (expanded && progress == 1f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun drawFraction(value: Float) {
        progress = value
        applyClip()
        if (value == 0f && !expanded) removePanel()
    }

    private fun removePanel() {
        if (panelAdded) {
            panelAdded = false
            panelView.visibility = View.INVISIBLE
            panelParams.width = 1
            panelParams.height = 1
            manager.updateViewLayout(panelView, panelParams)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (expanded) {
            progress = 1f
            post { if (expanded) placePanel() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        anchor.place(handleParams)
        if (isAttachedToWindow) {
            manager.updateViewLayout(this, handleParams)
            placePanel()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        if (panelView.isAttachedToWindow) manager.removeViewImmediate(panelView)
        panelAdded = false
        super.onDetachedFromWindow()
    }
}
