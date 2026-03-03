package com.example.bankscraperlogger.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView

class RecordingTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var startTimeMs = 0L
    private var pausedAccumulatedSec = 0L
    private var pausedAtMs = 0L
    private var running = false
    private var paused = false

    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsedSec = if (paused) {
                pausedAccumulatedSec
            } else {
                pausedAccumulatedSec + ((SystemClock.elapsedRealtime() - startTimeMs) / 1000L)
            }
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            text = if (h > 0) {
                "● %02d:%02d:%02d".format(h, m, s)
            } else {
                "● %02d:%02d".format(m, s)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragOffsetX = x - event.rawX
                dragOffsetY = y - event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val parentView = parent as? android.view.View ?: return true
                val newX = (event.rawX + dragOffsetX).coerceIn(0f, (parentView.width - width).toFloat())
                val newY = (event.rawY + dragOffsetY).coerceIn(0f, (parentView.height - height).toFloat())
                x = newX
                y = newY
            }
        }
        return true
    }

    fun startRecording() {
        startTimeMs = SystemClock.elapsedRealtime()
        pausedAccumulatedSec = 0L
        pausedAtMs = 0L
        running = true
        paused = false
        visibility = VISIBLE
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun pauseRecording() {
        if (!running || paused) return
        paused = true
        pausedAtMs = SystemClock.elapsedRealtime()
        pausedAccumulatedSec += (pausedAtMs - startTimeMs) / 1000L
    }

    fun resumeRecording() {
        if (!running || !paused) return
        paused = false
        startTimeMs = SystemClock.elapsedRealtime()
    }

    fun stopRecording() {
        running = false
        paused = false
        visibility = GONE
        handler.removeCallbacks(ticker)
        text = ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(ticker)
    }
}

