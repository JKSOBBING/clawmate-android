package com.clawmate.platform.gui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import com.clawmate.platform.R
import kotlin.math.hypot
import kotlin.math.roundToInt

private class FloatingLogoButton(context: Context) : ImageButton(context) {
    private val dim = Runnable { animate().alpha(.5f).setDuration(200).start() }

    private fun showLogo(startCountdown: Boolean) {
        removeCallbacks(dim)
        animate().cancel()
        alpha = 1f
        if (startCountdown && isShown) postDelayed(dim, 3_000L)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        showLogo(isVisible)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dim)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> showLogo(false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showLogo(true)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) showLogo(true)
        return super.performAccessibilityAction(action, arguments)
    }
}

internal fun floatingLogo(context: Context, label: String): ImageButton = FloatingLogoButton(context).apply {
    contentDescription = label
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    setPadding(0, 0, 0, 0)
    scaleType = ImageView.ScaleType.FIT_CENTER
    setImageResource(R.drawable.clawmate_harmony_logo)
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
    clipToOutline = true
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
        }
    }
}

/** Keep the chosen edge and a proportional height across window sizes and rotations. */
internal class FloatingPosition(private val context: Context, private val moved: () -> Unit = {},
    private val alignContent: (Int) -> Unit = {}) {
    private val prefs = context.getSharedPreferences("floating-logo", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(WindowManager::class.java)
    private var dockRight = prefs.getFloat("x", 1f) >= .5f
    private var y = prefs.getFloat("y", .22f)
    private val logoHeight = (48 * context.resources.displayMetrics.density).roundToInt()
    private var docking: ValueAnimator? = null

    private fun stopDocking() {
        docking?.cancel()
        docking = null
    }

    fun viewport(): Size {
        val display = context.resources.displayMetrics
        var frameWidth = display.widthPixels
        var frameHeight = display.heightPixels
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            frameWidth = metrics.bounds.width() - insets.left - insets.right
            frameHeight = metrics.bounds.height() - insets.top - insets.bottom
        }
        return Size(frameWidth, frameHeight)
    }

    private fun anchoredRight(params: WindowManager.LayoutParams) =
        params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT

    fun place(params: WindowManager.LayoutParams, screen: Size = viewport()) {
        stopDocking()
        val side = if (dockRight) Gravity.RIGHT else Gravity.LEFT
        params.gravity = Gravity.TOP or side
        params.x = 0
        params.y = (y * (screen.height - logoHeight)).roundToInt().coerceIn(0, (screen.height - params.height).coerceAtLeast(0))
        if (android.os.Build.VERSION.SDK_INT >= 34) params.setCanPlayMoveAnimation(false)
        alignContent(side)
    }

    private fun update(window: View, params: WindowManager.LayoutParams) {
        manager.updateViewLayout(window, params)
        moved()
    }

    private fun settle(window: View, params: WindowManager.LayoutParams) {
        val screen = viewport()
        val start = if (anchoredRight(params) == dockRight) params.x else (screen.width - params.width - params.x).coerceAtLeast(0)
        place(params, screen)
        if (!window.isAttachedToWindow) return
        if (start == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            update(window, params)
            return
        }
        docking = ValueAnimator.ofInt(start, 0).apply {
            duration = 190
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (window.isAttachedToWindow) update(window, params)
            }
            start()
        }
    }

    fun gestures(handle: View, window: View, params: WindowManager.LayoutParams, doubleTap: (() -> Unit)? = null) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var handleCenterX = 0f
        val windowLocation = IntArray(2)
        val handleLocation = IntArray(2)
        var screen = viewport()
        var moving = false
        var interruptedDock = false
        fun finishDrag(deltaX: Float) {
            dockRight = handleCenterX + deltaX >= screen.width / 2f
            val travel = screen.height - logoHeight
            if (travel > 0) y = params.y.toFloat() / travel
            settle(window, params)
            prefs.edit().putFloat("x", if (dockRight) 1f else 0f).putFloat("y", y).apply()
            moving = false
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastTap = 0L
        var secondTap = false
        val single = Runnable { lastTap = 0; if (handle.isAttachedToWindow) handle.performClick() }
        var lastX = 0f
        var lastY = 0f
        val doubleSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
        handle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { stopDocking(); handler.removeCallbacks(single); lastTap = 0 }
        })
        handle.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interruptedDock = docking?.isRunning == true
                    stopDocking()
                    downX = e.rawX; downY = e.rawY
                    originX = params.x; originY = params.y; moving = false
                    screen = viewport()
                    window.getLocationOnScreen(windowLocation)
                    handle.getLocationOnScreen(handleLocation)
                    val left = if (anchoredRight(params)) screen.width - window.width - originX else originX
                    handleCenterX = left + handleLocation[0] - windowLocation[0] + handle.width / 2f
                    secondTap = lastTap > 0 && e.eventTime - lastTap <= ViewConfiguration.getDoubleTapTimeout() &&
                        hypot(e.rawX - lastX, e.rawY - lastY) <= doubleSlop
                    if (secondTap) handler.removeCallbacks(single)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moving && hypot(dx, dy) > slop) {
                        moving = true; handler.removeCallbacks(single); lastTap = 0
                    }
                    if (moving && window.isAttachedToWindow) {
                        val offset = if (anchoredRight(params)) -dx else dx
                        params.x = (originX + offset.roundToInt()).coerceIn(0, (screen.width - window.width).coerceAtLeast(0))
                        params.y = (originY + dy.roundToInt()).coerceIn(0, (screen.height - window.height).coerceAtLeast(0))
                        update(window, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moving && interruptedDock) settle(window, params)
                    if (moving) finishDrag(e.rawX - downX)
                    else if (doubleTap == null) view.performClick()
                    else if (secondTap) {
                        handler.removeCallbacks(single); lastTap = 0; doubleTap()
                    } else {
                        if (lastTap > 0) { handler.removeCallbacks(single); single.run() }
                        lastTap = e.eventTime; lastX = e.rawX; lastY = e.rawY
                        handler.postDelayed(single, ViewConfiguration.getDoubleTapTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moving) finishDrag((if (anchoredRight(params)) originX - params.x else params.x - originX).toFloat())
                    else if (interruptedDock) settle(window, params)
                    handler.removeCallbacks(single)
                    lastTap = 0
                }
            }
            true
        }
    }
}
