package com.audiobridge.client.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class EyeAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode {
        IDLE,
        SENDING,
        WAITING,
        SPEAKING,
        FAILED,
    }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#BCEFF0")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#295CFF")
        alpha = 120
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private var mode: Mode = Mode.IDLE
    private var blinkScale = 1f
    private var breatheScale = 1f
    private var jitterX = 0f
    private var speakingAmplitude = 0f
    private var failedUntil = 0L

    private val blinkAnimator = ValueAnimator.ofFloat(1f, 0.2f, 1f).apply {
        duration = 260
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        startDelay = 2000
        addUpdateListener {
            blinkScale = it.animatedValue as Float
            invalidate()
        }
    }

    private val breatheAnimator = ValueAnimator.ofFloat(0.95f, 1.05f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            breatheScale = it.animatedValue as Float
            invalidate()
        }
    }

    private val jitterAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
        duration = 220
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            jitterX = (it.animatedValue as Float) * when (mode) {
                Mode.SENDING -> 8f
                Mode.WAITING -> 4f
                Mode.SPEAKING -> 3f
                else -> 0f
            }
            invalidate()
        }
    }

    private val speakingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 320
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            speakingAmplitude = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!breatheAnimator.isStarted) breatheAnimator.start()
        if (!blinkAnimator.isStarted) blinkAnimator.start()
        if (!jitterAnimator.isStarted) jitterAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkAnimator.cancel()
        breatheAnimator.cancel()
        jitterAnimator.cancel()
        speakingAnimator.cancel()
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        if (newMode == Mode.SPEAKING) {
            if (!speakingAnimator.isStarted) speakingAnimator.start()
        } else if (speakingAnimator.isStarted) {
            speakingAnimator.cancel()
            speakingAmplitude = 0f
        }
        if (newMode == Mode.FAILED) {
            failedUntil = System.currentTimeMillis() + 900
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val effectiveMode = if (mode == Mode.FAILED && System.currentTimeMillis() > failedUntil) {
            Mode.IDLE
        } else {
            mode
        }

        val eyeW = width * 0.22f
        val eyeHBase = height * 0.26f
        val centerY = height * 0.48f
        val spacing = width * 0.12f
        val centerX = width / 2f + jitterX

        val speakingBoost = if (effectiveMode == Mode.SPEAKING) {
            1f + speakingAmplitude * 0.18f
        } else {
            1f
        }
        val eyeH = eyeHBase * blinkScale * breatheScale * speakingBoost

        val left = RectF(
            centerX - spacing - eyeW,
            centerY - eyeH / 2f,
            centerX - spacing,
            centerY + eyeH / 2f,
        )
        val right = RectF(
            centerX + spacing,
            centerY - eyeH / 2f,
            centerX + spacing + eyeW,
            centerY + eyeH / 2f,
        )

        val t = (System.currentTimeMillis() % 3000L) / 3000f
        val drift = (sin(2 * PI * t) * 5).toFloat()
        left.offset(drift, 0f)
        right.offset(drift, 0f)

        when (effectiveMode) {
            Mode.FAILED -> {
                eyePaint.color = Color.parseColor("#FFB3B3")
                glowPaint.color = Color.parseColor("#FF3B3B")
                glowPaint.alpha = 150
            }
            else -> {
                eyePaint.color = Color.parseColor("#BCEFF0")
                glowPaint.color = Color.parseColor("#295CFF")
                glowPaint.alpha = if (effectiveMode == Mode.SPEAKING) 170 else 120
            }
        }

        canvas.drawOval(
            left.left,
            left.bottom - eyeH * 0.58f,
            left.right,
            left.bottom + eyeH * 0.42f,
            glowPaint,
        )
        canvas.drawOval(
            right.left,
            right.bottom - eyeH * 0.58f,
            right.right,
            right.bottom + eyeH * 0.42f,
            glowPaint,
        )
        canvas.drawOval(left, eyePaint)
        canvas.drawOval(right, eyePaint)
    }
}
