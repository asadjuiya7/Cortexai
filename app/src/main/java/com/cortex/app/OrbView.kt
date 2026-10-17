package com.cortex.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** The glowing Siri-style orb shown at the bottom of the screen while Cortex listens, thinks and speaks. */
class OrbView(ctx: Context) : View(ctx) {

    enum class State { LISTEN, THINK, SPEAK }

    var state: State = State.LISTEN

    private var t = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tints = intArrayOf(
        Color.parseColor("#4FD6E8"), // cyan
        Color.parseColor("#8B7BFF"), // violet
        Color.parseColor("#FF6FB5")  // pink
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
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

    private fun withAlpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val base = minOf(w, h) / 2f
        val turns = when (state) {
            State.LISTEN -> 1
            State.THINK -> 3
            State.SPEAK -> 2
        }
        val ph = t * 2f * Math.PI.toFloat() * turns
        val pulse = when (state) {
            State.LISTEN -> 0.90f + 0.06f * sin(ph)
            State.THINK -> 0.84f + 0.08f * sin(ph)
            State.SPEAK -> 0.80f + 0.14f * sin(ph * 2f)
        }
        val spread = if (state == State.THINK) 0.34f else 0.24f

        // soft outer glow
        paint.shader = RadialGradient(
            cx, cy, base * pulse,
            intArrayOf(withAlpha(tints[0], 90), withAlpha(tints[1], 0)), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, base * pulse, paint)

        // three coloured blobs circling the centre
        for (i in 0 until 3) {
            val a = ph + i * 2f * Math.PI.toFloat() / 3f
            val bx = cx + cos(a) * base * spread
            val by = cy + sin(a) * base * spread
            val r = base * 0.60f * pulse
            paint.shader = RadialGradient(
                bx, by, r, intArrayOf(withAlpha(tints[i], 190), withAlpha(tints[i], 0)), null, Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, r, paint)
        }

        // bright core
        val core = base * 0.30f * (0.9f + 0.1f * pulse)
        paint.shader = RadialGradient(
            cx, cy, core, intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, core, paint)
        paint.shader = null
    }
}
