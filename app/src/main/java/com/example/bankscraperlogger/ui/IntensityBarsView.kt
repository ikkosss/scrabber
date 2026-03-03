package com.example.bankscraperlogger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import kotlin.math.max
import kotlin.math.min

class IntensityBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var intensity: Float = 0f // 0..1
    private var phaseOffset: Float = 0f

    private val bars = 18
    private val gapPx = dp(2f)
    private val minBarHeightFactor = 0.22f

    init {
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.colorAccent)).use {
            // no-op: keep theme default
        }
        paint.color = context.getColorCompat(com.google.android.material.R.attr.colorPrimary, fallback = 0xFF255FA6.toInt())
    }

    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        intensity = clamped
        phaseOffset += 0.35f + 0.9f * clamped
        if (phaseOffset > 10000f) phaseOffset -= 10000f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val totalGap = gapPx * (bars - 1)
        val barW = max(1f, (w - totalGap) / bars)

        var x = 0f
        for (i in 0 until bars) {
            val phase = i.toFloat() / max(1, bars - 1)
            val shaped = (minBarHeightFactor + (1f - minBarHeightFactor) * intensity) *
                (0.50f + 0.50f * wave(phase, intensity, phaseOffset))
            val barH = min(h, max(1f, h * shaped))
            canvas.drawRoundRect(x, h - barH, x + barW, h, dp(2f), dp(2f), paint)
            x += barW + gapPx
        }
    }

    private fun wave(phase: Float, intensity: Float, offset: Float): Float {
        // Deterministic pseudo-wave (no random) so it feels like an “equalizer”
        // that responds to intensity but doesn’t flicker uncontrollably.
        val a = 6.28318f
        val base = kotlin.math.sin(a * (phase * (0.9f + 1.6f * intensity)) + 0.08f * offset + 1.3f * intensity)
        val mod = kotlin.math.sin(a * (phase * (2.1f + 2.7f * intensity)) + 0.05f * offset + 2.1f)
        return (0.6f * base + 0.4f * mod + 1f) / 2f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

private fun Context.getColorCompat(attr: Int, fallback: Int): Int {
    val typedArray = theme.obtainStyledAttributes(intArrayOf(attr))
    return typedArray.use {
        it.getColor(0, fallback)
    }
}

