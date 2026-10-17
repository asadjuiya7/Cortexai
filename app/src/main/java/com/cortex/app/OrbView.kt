package com.cortex.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Cortex planet (the same globe, orbit ring and orange moon as the app icon), glowing at the bottom
 * of the screen while Cortex listens, thinks and speaks.
 */
class OrbView(ctx: Context) : View(ctx) {

    enum class State { LISTEN, THINK, SPEAK }

    var state: State = State.LISTEN

    private var t = 0f
    private var anim: ValueAnimator? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val cyan = Color.parseColor("#4FD6E8")
    private val amber = Color.parseColor("#E8A33D")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    private fun a(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val size = minOf(w, h)
        val r = size * 0.30f // planet radius
        val tau = 2f * Math.PI.toFloat()

        // speed in whole turns per 6 seconds, so the loop never jumps
        val turns = when (state) {
            State.LISTEN -> 1
            State.THINK -> 4
            State.SPEAK -> 2
        }
        val ph = t * tau * turns
        val pulse = when (state) {
            State.LISTEN -> 1f + 0.03f * sin(t * tau * 2f)
            State.THINK -> 1f + 0.05f * sin(t * tau * 4f)
            State.SPEAK -> 1f + 0.09f * sin(t * tau * 6f)
        }

        // soft glow behind the planet
        val glowR = size * 0.50f * pulse
        fill.shader = RadialGradient(cx, cy, glowR, intArrayOf(a(cyan, 120), a(cyan, 40), a(cyan, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, glowR, fill)
        fill.shader = null

        // outer ring of the planet
        line.color = cyan
        line.strokeWidth = r * 0.11f
        c.drawCircle(cx, cy, r, line)

        // equator
        line.color = a(cyan, 165)
        line.strokeWidth = r * 0.06f
        rect.set(cx - r, cy - r * 0.37f, cx + r, cy + r * 0.37f)
        c.drawOval(rect, line)

        // two meridians that turn, so the globe seems to spin
        for (k in 0 until 2) {
            val ang = ph * 0.5f + k * (Math.PI.toFloat() / 2f)
            val rx = r * (0.18f + 0.82f * abs(cos(ang)))
            rect.set(cx - rx, cy - r, cx + rx, cy + r)
            line.color = a(cyan, if (k == 0) 165 else 110)
            c.drawOval(rect, line)
        }

        // tilted orbit ring with the orange moon travelling along it
        c.save()
        c.rotate(38f, cx, cy)
        line.color = a(cyan, 105)
        line.strokeWidth = r * 0.05f
        rect.set(cx - r, cy - r * 0.37f, cx + r, cy + r * 0.37f)
        c.drawOval(rect, line)
        val ma = ph * 1.0f + 1.2f
        val mx = cx + r * cos(ma)
        val my = cy + r * 0.37f * sin(ma)
        fill.shader = RadialGradient(mx, my, r * 0.36f, intArrayOf(a(amber, 200), a(amber, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, r * 0.36f, fill)
        fill.shader = null
        fill.color = amber
        c.drawCircle(mx, my, r * 0.13f, fill)
        c.restore()

        // glowing cyan core
        val core = r * 0.23f * pulse
        fill.shader = RadialGradient(cx, cy, core * 2.4f, intArrayOf(a(cyan, 170), a(cyan, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core * 2.4f, fill)
        fill.shader = null
        fill.color = cyan
        c.drawCircle(cx, cy, core, fill)
    }
}
