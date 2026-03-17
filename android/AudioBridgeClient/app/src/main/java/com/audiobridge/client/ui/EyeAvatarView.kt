package com.audiobridge.client.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class EyeAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class Mode {
        IDLE,
        SENDING,
        WAITING,
        THINKING,
        SPEAKING,
        FAILED,
    }

    enum class Emotion {
        NEUTRAL,
        HAPPY,
        JOY,
        ANGRY,
        SAD,
    }

    private enum class Performance {
        IDLE,
        THINKING,
        HAPPY,
        JOY,
        ANGRY,
        SAD,
        SPEAKING,
        FAILED,
    }

    private enum class ActionType {
        IDLE_SIDE_GLANCE,
        IDLE_SOFT_SWAY,
        IDLE_DOUBLE_BLINK,
        THINK_SCAN,
        THINK_FOCUS,
        THINK_MICRO_TWITCH,
        HAPPY_SMILE_SQUINT,
        HAPPY_WINK,
        HAPPY_GLOW_PULSE,
        JOY_SWING,
        JOY_RAPID_BLINK,
        JOY_SPARK,
        ANGRY_GLARE,
        ANGRY_TWITCH,
        ANGRY_ALERT_PULSE,
        SAD_DOWNCAST,
        SAD_LONG_BLINK,
        SAD_DIM_BREATH,
    }

    private enum class BlinkPattern {
        SINGLE,
        DOUBLE,
        WINK_LEFT,
        WINK_RIGHT,
        LONG,
    }

    private data class ActiveAction(
        val type: ActionType,
        val startMs: Long,
        val durationMs: Long,
    )

    private data class BlinkState(
        val pattern: BlinkPattern,
        val startMs: Long,
        val durationMs: Long,
    )

    private data class Style(
        val eyeColor: Int,
        val glowColor: Int,
        val glowAlpha: Int,
        val openBase: Float,
        val widthScale: Float,
        val driftX: Float,
        val driftY: Float,
        val jitter: Float,
        val breathe: Float,
        val tiltLeft: Float,
        val tiltRight: Float,
    )

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 54
    }

    private val leftEyeRect = RectF()
    private val rightEyeRect = RectF()
    private val rng = Random(System.currentTimeMillis())

    private var mode: Mode = Mode.IDLE
    private var baseEmotion: Emotion = Emotion.NEUTRAL
    private var pulseEmotion: Emotion? = null
    private var pulseUntilMs = 0L
    private var failedUntilMs = 0L

    private var lastPerformance: Performance? = null
    private var activeAction: ActiveAction? = null
    private var nextActionAtMs = 0L
    private var blinkState: BlinkState? = null
    private var nextBlinkAtMs = 0L

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticker.cancel()
    }

    fun setMode(newMode: Mode) {
        mode = when (newMode) {
            Mode.SENDING, Mode.WAITING -> Mode.THINKING
            else -> newMode
        }
        if (mode == Mode.FAILED) failedUntilMs = System.currentTimeMillis() + 1200L
        invalidate()
    }

    fun setEmotion(emotion: Emotion) {
        baseEmotion = emotion
        invalidate()
    }

    fun pulseEmotion(
        emotion: Emotion,
        durationMs: Long = 2200L,
    ) {
        pulseEmotion = emotion
        pulseUntilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(500L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (width <= 0 || height <= 0) return

        val now = System.currentTimeMillis()
        val effectiveMode = if (mode == Mode.FAILED && now > failedUntilMs) Mode.IDLE else mode
        val emotion = if (pulseEmotion != null && now < pulseUntilMs) pulseEmotion!! else baseEmotion
        if (pulseEmotion != null && now >= pulseUntilMs) pulseEmotion = null
        val performance = resolvePerformance(effectiveMode, emotion)

        if (performance != lastPerformance) {
            lastPerformance = performance
            activeAction = null
            blinkState = null
            nextActionAtMs = now + randomLong(140L, 420L)
            nextBlinkAtMs = now + randomLong(260L, 600L)
        }

        updateAction(performance, now)
        updateBlink(performance, now)

        val style = tint(styleFor(performance), emotion)
        val motion = Motion(style, now)
        applyAction(activeAction, now, motion)
        applyBlink(motion, now)

        drawEyes(canvas, motion)
    }

    private data class Motion(
        val style: Style,
        val now: Long,
        var gazeX: Float = 0f,
        var gazeY: Float = 0f,
        var openLeft: Float = 1f,
        var openRight: Float = 1f,
        var widthMul: Float = 1f,
        var glowBoost: Float = 0f,
        var jitterBoost: Float = 0f,
        var tiltLeftAdd: Float = 0f,
        var tiltRightAdd: Float = 0f,
    )

    private fun resolvePerformance(mode: Mode, emotion: Emotion): Performance {
        return when (mode) {
            Mode.FAILED -> Performance.FAILED
            Mode.SPEAKING -> Performance.SPEAKING
            Mode.THINKING, Mode.SENDING, Mode.WAITING -> Performance.THINKING
            Mode.IDLE -> when (emotion) {
                Emotion.HAPPY -> Performance.HAPPY
                Emotion.JOY -> Performance.JOY
                Emotion.ANGRY -> Performance.ANGRY
                Emotion.SAD -> Performance.SAD
                Emotion.NEUTRAL -> Performance.IDLE
            }
        }
    }

    private fun updateAction(performance: Performance, now: Long) {
        val current = activeAction
        if (current != null && now - current.startMs >= current.durationMs) {
            activeAction = null
            nextActionAtMs = now + actionCooldown(performance)
        }
        if (activeAction != null || now < nextActionAtMs) return
        val type = pickAction(performance) ?: return
        activeAction = ActiveAction(type, now, actionDuration(performance))
    }

    private fun updateBlink(performance: Performance, now: Long) {
        val blink = blinkState
        if (blink != null && now - blink.startMs >= blink.durationMs) {
            blinkState = null
            nextBlinkAtMs = now + blinkGap(performance)
        }
        if (blinkState != null || now < nextBlinkAtMs) return
        blinkState = BlinkState(pickBlink(performance), now, blinkDuration(performance))
    }

    private fun applyAction(active: ActiveAction?, now: Long, m: Motion) {
        val baseOsc = sin(2.0 * PI * (now % 2800L) / 2800.0).toFloat()
        val vOsc = sin(2.0 * PI * (now % 3600L) / 3600.0 + PI / 3).toFloat()
        m.gazeX += baseOsc * m.style.driftX
        m.gazeY += vOsc * m.style.driftY
        m.openLeft *= 1f + m.style.breathe * baseOsc
        m.openRight *= 1f + m.style.breathe * baseOsc

        if (lastPerformance == Performance.SPEAKING) {
            val pulse = ((sin(2.0 * PI * (now % 360L) / 360.0) + 1.0) / 2.0).toFloat()
            m.openLeft *= 1f + 0.15f * pulse
            m.openRight *= 1f + 0.15f * pulse
            m.glowBoost += 0.25f * pulse
        }

        if (active == null) return
        val p = ((now - active.startMs).toFloat() / active.durationMs.toFloat()).coerceIn(0f, 1f)
        val env = sin(PI * p).toFloat().coerceIn(0f, 1f)
        when (active.type) {
            ActionType.IDLE_SIDE_GLANCE -> { m.gazeX += sin(2.0 * PI * p).toFloat() * 11f * env; m.openLeft *= 1f - 0.05f * env; m.openRight *= 1f - 0.05f * env }
            ActionType.IDLE_SOFT_SWAY -> { m.gazeY += sin(2.0 * PI * p).toFloat() * 4f; m.widthMul *= 1f + 0.04f * env }
            ActionType.IDLE_DOUBLE_BLINK -> { val c = if (p < 0.5f) triangle(p * 2f) else triangle((p - 0.5f) * 2f); m.openLeft *= 1f - 0.72f * c; m.openRight *= 1f - 0.72f * c }
            ActionType.THINK_SCAN -> { m.gazeX += sin(2.0 * PI * p).toFloat() * 17f; m.openLeft *= 1f - 0.12f * env; m.openRight *= 1f - 0.12f * env; m.glowBoost += 0.18f * env }
            ActionType.THINK_FOCUS -> { m.openLeft *= 1f - 0.20f * env; m.openRight *= 1f - 0.20f * env; m.tiltLeftAdd += 3.5f * env; m.tiltRightAdd -= 3.5f * env; m.jitterBoost += 0.8f * env }
            ActionType.THINK_MICRO_TWITCH -> { m.gazeX += sin(14.0 * PI * p).toFloat() * 4.2f * env; m.jitterBoost += 2.3f * env }
            ActionType.HAPPY_SMILE_SQUINT -> { m.openLeft *= 1f - 0.18f * env; m.openRight *= 1f - 0.18f * env; m.tiltLeftAdd -= 7f * env; m.tiltRightAdd += 7f * env; m.glowBoost += 0.18f * env }
            ActionType.HAPPY_WINK -> { val c = triangle(p); m.openLeft *= 1f - 0.82f * c; m.openRight *= 1f - 0.10f * c; m.glowBoost += 0.15f * env }
            ActionType.HAPPY_GLOW_PULSE -> { m.widthMul *= 1f + 0.07f * env; m.glowBoost += 0.42f * env }
            ActionType.JOY_SWING -> { m.gazeX += sin(3.0 * PI * p).toFloat() * 15f; m.widthMul *= 1f + 0.09f * env; m.glowBoost += 0.30f * env }
            ActionType.JOY_RAPID_BLINK -> { val c = abs(sin(6.0 * PI * p)).toFloat(); m.openLeft *= 1f - 0.76f * c; m.openRight *= 1f - 0.76f * c; m.glowBoost += 0.16f * env }
            ActionType.JOY_SPARK -> { m.jitterBoost += 2.8f * env; m.glowBoost += 0.50f * env }
            ActionType.ANGRY_GLARE -> { m.openLeft *= 1f - 0.24f * env; m.openRight *= 1f - 0.24f * env; m.tiltLeftAdd += 8.5f * env; m.tiltRightAdd -= 8.5f * env; m.glowBoost += 0.20f * env }
            ActionType.ANGRY_TWITCH -> { m.gazeX += sin(16.0 * PI * p).toFloat() * 5f * env; m.jitterBoost += 3.2f * env }
            ActionType.ANGRY_ALERT_PULSE -> { m.glowBoost += 0.60f * env; m.openLeft *= 1f - 0.12f * env; m.openRight *= 1f - 0.12f * env }
            ActionType.SAD_DOWNCAST -> { m.gazeY += 8f * env; m.openLeft *= 1f - 0.28f * env; m.openRight *= 1f - 0.28f * env }
            ActionType.SAD_LONG_BLINK -> { val c = sin(PI * p).toFloat(); m.openLeft *= 1f - 0.86f * c; m.openRight *= 1f - 0.86f * c; m.glowBoost -= 0.12f * c }
            ActionType.SAD_DIM_BREATH -> { m.glowBoost -= 0.36f * env; m.gazeY += 4f * env }
        }
    }

    private fun applyBlink(m: Motion, now: Long) {
        val blink = blinkState ?: return
        val p = ((now - blink.startMs).toFloat() / blink.durationMs.toFloat()).coerceIn(0f, 1f)
        val closure = when (blink.pattern) {
            BlinkPattern.SINGLE -> triangle(p)
            BlinkPattern.DOUBLE -> if (p < 0.5f) triangle(p * 2f) else triangle((p - 0.5f) * 2f)
            BlinkPattern.LONG -> sin(PI * p).toFloat()
            BlinkPattern.WINK_LEFT, BlinkPattern.WINK_RIGHT -> triangle(p)
        }.coerceIn(0f, 1f)
        val left = if (blink.pattern == BlinkPattern.WINK_RIGHT) 0.12f * closure else closure
        val right = if (blink.pattern == BlinkPattern.WINK_LEFT) 0.12f * closure else closure
        m.openLeft *= (1f - 0.92f * left).coerceIn(0.08f, 1f)
        m.openRight *= (1f - 0.92f * right).coerceIn(0.08f, 1f)
    }

    private fun drawEyes(canvas: Canvas, m: Motion) {
        val microX = sin(2.0 * PI * (m.now % 220L) / 220.0).toFloat() * (m.style.jitter + m.jitterBoost)
        val microY = sin(2.0 * PI * (m.now % 300L) / 300.0 + PI / 4).toFloat() * (m.style.jitter + m.jitterBoost) * 0.35f
        val eyeW = width * 0.215f * (m.style.widthScale * m.widthMul).coerceIn(0.82f, 1.24f)
        val eyeHBase = height * 0.245f
        val spacing = width * 0.12f
        val centerX = width / 2f + m.gazeX + microX
        val centerY = height * 0.48f + m.gazeY + microY
        val hLeft = max(eyeHBase * (m.style.openBase * m.openLeft).coerceIn(0.08f, 1.28f), 4f)
        val hRight = max(eyeHBase * (m.style.openBase * m.openRight).coerceIn(0.08f, 1.28f), 4f)

        leftEyeRect.set(centerX - spacing - eyeW, centerY - hLeft / 2f, centerX - spacing, centerY + hLeft / 2f)
        rightEyeRect.set(centerX + spacing, centerY - hRight / 2f, centerX + spacing + eyeW, centerY + hRight / 2f)

        glowPaint.color = m.style.glowColor
        glowPaint.alpha = (m.style.glowAlpha * (1f + m.glowBoost)).toInt().coerceIn(40, 230)
        drawGlow(canvas, leftEyeRect)
        drawGlow(canvas, rightEyeRect)

        eyePaint.color = m.style.eyeColor
        drawEye(canvas, leftEyeRect, m.style.tiltLeft + m.tiltLeftAdd)
        drawEye(canvas, rightEyeRect, m.style.tiltRight + m.tiltRightAdd)
    }

    private fun drawGlow(canvas: Canvas, rect: RectF) {
        canvas.drawOval(
            rect.left,
            rect.bottom - rect.height() * 0.56f,
            rect.right,
            rect.bottom + rect.height() * 0.48f,
            glowPaint,
        )
    }

    private fun drawEye(canvas: Canvas, rect: RectF, tilt: Float) {
        canvas.save()
        canvas.rotate(tilt, rect.centerX(), rect.centerY())
        val radius = rect.height() * 0.52f
        canvas.drawRoundRect(rect, radius, radius, eyePaint)
        canvas.drawOval(
            rect.left + rect.width() * 0.20f,
            rect.top + rect.height() * 0.15f,
            rect.left + rect.width() * 0.41f,
            rect.top + rect.height() * 0.42f,
            highlightPaint,
        )
        canvas.restore()
    }

    private fun styleFor(performance: Performance): Style {
        return when (performance) {
            Performance.IDLE -> Style(Color.parseColor("#C7F3F3"), Color.parseColor("#285BFF"), 104, 0.90f, 1.0f, 4.4f, 1.8f, 0.8f, 0.05f, 0f, 0f)
            Performance.THINKING -> Style(Color.parseColor("#CFE9FF"), Color.parseColor("#3F77FF"), 132, 0.78f, 1.03f, 3.2f, 1.2f, 1.6f, 0.03f, 2f, -2f)
            Performance.HAPPY -> Style(Color.parseColor("#D8FFF0"), Color.parseColor("#18D6A0"), 148, 0.86f, 1.05f, 5.8f, 2.2f, 1.2f, 0.05f, -5f, 5f)
            Performance.JOY -> Style(Color.parseColor("#E9FFF9"), Color.parseColor("#00DBFF"), 164, 0.94f, 1.08f, 7.2f, 2.4f, 2.0f, 0.06f, -3f, 3f)
            Performance.ANGRY -> Style(Color.parseColor("#FFD4D4"), Color.parseColor("#FF3E3E"), 152, 0.64f, 1.04f, 1.7f, 0.8f, 2.8f, 0.02f, 10f, -10f)
            Performance.SAD -> Style(Color.parseColor("#C8D9EA"), Color.parseColor("#4D72B1"), 88, 0.58f, 0.97f, 1.8f, 2.6f, 0.5f, 0.02f, -6f, 6f)
            Performance.SPEAKING -> Style(Color.parseColor("#D4F6F8"), Color.parseColor("#2D67FF"), 140, 0.90f, 1.02f, 4.6f, 1.5f, 1.3f, 0.04f, 0f, 0f)
            Performance.FAILED -> Style(Color.parseColor("#FFB8B8"), Color.parseColor("#FF2A2A"), 178, 0.50f, 1.02f, 0f, 0f, 3.2f, 0f, 12f, -12f)
        }
    }

    private fun tint(style: Style, emotion: Emotion): Style {
        val (eye, glow, t) = when (emotion) {
            Emotion.NEUTRAL -> return style
            Emotion.HAPPY -> Triple(Color.parseColor("#DDFFF4"), Color.parseColor("#28E0B0"), 0.35f)
            Emotion.JOY -> Triple(Color.parseColor("#EBFFFA"), Color.parseColor("#03E6FF"), 0.45f)
            Emotion.ANGRY -> Triple(Color.parseColor("#FFD7D7"), Color.parseColor("#FF4C4C"), 0.46f)
            Emotion.SAD -> Triple(Color.parseColor("#CDDAE8"), Color.parseColor("#607FB8"), 0.42f)
        }
        return style.copy(eyeColor = blend(style.eyeColor, eye, t), glowColor = blend(style.glowColor, glow, t))
    }

    private fun pickAction(perf: Performance): ActionType? = when (perf) {
        Performance.IDLE -> listOf(ActionType.IDLE_SIDE_GLANCE, ActionType.IDLE_SOFT_SWAY, ActionType.IDLE_DOUBLE_BLINK)
        Performance.THINKING -> listOf(ActionType.THINK_SCAN, ActionType.THINK_FOCUS, ActionType.THINK_MICRO_TWITCH)
        Performance.HAPPY -> listOf(ActionType.HAPPY_SMILE_SQUINT, ActionType.HAPPY_WINK, ActionType.HAPPY_GLOW_PULSE)
        Performance.JOY -> listOf(ActionType.JOY_SWING, ActionType.JOY_RAPID_BLINK, ActionType.JOY_SPARK)
        Performance.ANGRY -> listOf(ActionType.ANGRY_GLARE, ActionType.ANGRY_TWITCH, ActionType.ANGRY_ALERT_PULSE)
        Performance.SAD -> listOf(ActionType.SAD_DOWNCAST, ActionType.SAD_LONG_BLINK, ActionType.SAD_DIM_BREATH)
        Performance.SPEAKING, Performance.FAILED -> null
    }?.let { it[rng.nextInt(it.size)] }

    private fun actionDuration(perf: Performance): Long = when (perf) {
        Performance.IDLE -> randomLong(820L, 1500L)
        Performance.THINKING -> randomLong(700L, 1300L)
        Performance.HAPPY, Performance.JOY -> randomLong(600L, 1100L)
        Performance.ANGRY -> randomLong(420L, 920L)
        Performance.SAD -> randomLong(900L, 1700L)
        Performance.SPEAKING, Performance.FAILED -> randomLong(420L, 760L)
    }

    private fun actionCooldown(perf: Performance): Long = when (perf) {
        Performance.IDLE -> randomLong(1400L, 3000L)
        Performance.THINKING -> randomLong(700L, 1600L)
        Performance.HAPPY, Performance.JOY -> randomLong(700L, 1500L)
        Performance.ANGRY -> randomLong(600L, 1300L)
        Performance.SAD -> randomLong(1000L, 2400L)
        Performance.SPEAKING, Performance.FAILED -> randomLong(500L, 900L)
    }

    private fun pickBlink(perf: Performance): BlinkPattern {
        val p = rng.nextInt(100)
        return when (perf) {
            Performance.HAPPY -> if (p < 20) BlinkPattern.WINK_LEFT else if (p < 35) BlinkPattern.WINK_RIGHT else if (p < 60) BlinkPattern.DOUBLE else BlinkPattern.SINGLE
            Performance.JOY -> if (p < 36) BlinkPattern.DOUBLE else if (p < 52) BlinkPattern.WINK_LEFT else BlinkPattern.SINGLE
            Performance.SAD -> if (p < 38) BlinkPattern.LONG else BlinkPattern.SINGLE
            Performance.IDLE -> if (p < 22) BlinkPattern.DOUBLE else BlinkPattern.SINGLE
            Performance.THINKING -> if (p < 15) BlinkPattern.DOUBLE else BlinkPattern.SINGLE
            else -> BlinkPattern.SINGLE
        }
    }

    private fun blinkDuration(perf: Performance): Long = when (perf) {
        Performance.JOY -> randomLong(150L, 220L)
        Performance.HAPPY -> randomLong(170L, 250L)
        Performance.SAD -> randomLong(280L, 460L)
        Performance.ANGRY -> randomLong(130L, 200L)
        else -> randomLong(180L, 280L)
    }

    private fun blinkGap(perf: Performance): Long = when (perf) {
        Performance.IDLE -> randomLong(2800L, 6200L)
        Performance.THINKING -> randomLong(3400L, 6900L)
        Performance.HAPPY -> randomLong(2200L, 5000L)
        Performance.JOY -> randomLong(1700L, 3600L)
        Performance.ANGRY -> randomLong(4200L, 8000L)
        Performance.SAD -> randomLong(4200L, 7600L)
        Performance.SPEAKING -> randomLong(3200L, 5600L)
        Performance.FAILED -> randomLong(9000L, 12000L)
    }

    private fun triangle(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) x * 2f else (1f - x) * 2f
    }

    private fun randomLong(min: Long, max: Long): Long {
        if (max <= min) return min
        return rng.nextLong(min, max + 1L)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val p = t.coerceIn(0f, 1f)
        val ca = (Color.alpha(a) + ((Color.alpha(b) - Color.alpha(a)) * p)).toInt().coerceIn(0, 255)
        val cr = (Color.red(a) + ((Color.red(b) - Color.red(a)) * p)).toInt().coerceIn(0, 255)
        val cg = (Color.green(a) + ((Color.green(b) - Color.green(a)) * p)).toInt().coerceIn(0, 255)
        val cb = (Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * p)).toInt().coerceIn(0, 255)
        return Color.argb(ca, cr, cg, cb)
    }
}
