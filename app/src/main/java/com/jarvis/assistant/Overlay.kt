package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** Burbuja flotante de Jarvis: se ve sobre cualquier app, se arrastra y al tocarla abre Jarvis. */
class JarvisOverlay(private val ctx: Context) {

    companion object {
        // Se conserva la posición mientras viva el proceso
        private var lastX = 24
        private var lastY = 420
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var orb: OrbView? = null
    private var caption: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var state = "idle"
    private val hideCaption = Runnable { caption?.visibility = View.GONE }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun canShow() = Settings.canDrawOverlays(ctx)
    val isShown get() = root != null

    fun show() {
        if (root != null || !canShow()) return
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }

        val cap = TextView(ctx).apply {
            setTextColor(Color.parseColor("#DCE9F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60C1526"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#554FD1FF"))
            }
            visibility = View.GONE
        }
        val capLp = LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6) }
        box.addView(cap, capLp)

        val o = OrbView(ctx)
        o.setState(state)
        box.addView(o, LinearLayout.LayoutParams(dp(60), dp(60)))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = lastX; y = lastY }

        o.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            private var moved = false
            private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            p.x = (startX + dx).toInt().coerceAtLeast(0)
                            p.y = (startY + dy).toInt().coerceAtLeast(0)
                            lastX = p.x; lastY = p.y
                            try { wm.updateViewLayout(box, p) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) openJarvis()
                }
                return true
            }
        })

        try {
            wm.addView(box, p)
            root = box; orb = o; caption = cap; params = p
        } catch (_: Exception) {
            root = null
        }
    }

    fun hide() {
        main.removeCallbacks(hideCaption)
        val r = root ?: return
        orb?.stop()
        try { wm.removeView(r) } catch (_: Exception) {}
        root = null; orb = null; caption = null; params = null
    }

    fun setState(newState: String, text: String?) {
        state = newState
        orb?.setState(newState)
        val c = caption ?: return
        main.removeCallbacks(hideCaption)
        if (!text.isNullOrBlank()) {
            c.text = text
            c.visibility = View.VISIBLE
            main.postDelayed(hideCaption, if (newState == "speaking") 9000 else 5000)
        } else if (newState == "idle" || newState == "error") {
            main.postDelayed(hideCaption, 5000)
        }
    }

    private fun openJarvis() {
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {}
    }

    /** Reactor de arco dibujado a mano; cambia de color y ritmo según lo que hace Jarvis. */
    private class OrbView(ctx: Context) : View(ctx) {
        private var state = "idle"
        private var running = true
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG)
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0050A14") }
        private val rect = RectF()

        fun setState(s: String) { state = s }
        fun stop() { running = false }

        private fun colorFor(): Int = when (state) {
            "thinking" -> Color.parseColor("#FFB347")
            "error" -> Color.parseColor("#FF6B6B")
            else -> Color.parseColor("#4FD1FF")
        }

        override fun onDraw(c: Canvas) {
            val t = System.currentTimeMillis() / 1000f
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2; val cy = h / 2
            val col = colorFor()
            val speed = when (state) { "thinking" -> 320f; "speaking" -> 140f; "listening" -> 70f; else -> 24f }
            val pulse = when (state) {
                "speaking" -> 0.5f + 0.5f * sin(t * 9f)
                "listening" -> 0.5f + 0.5f * sin(t * 3.2f)
                "thinking" -> 0.5f + 0.5f * sin(t * 6f)
                else -> 0.35f + 0.15f * sin(t * 1.4f)
            }
            val r = minOf(w, h) / 2

            glow.color = col; glow.alpha = (40 + 70 * pulse).toInt()
            c.drawCircle(cx, cy, r * (0.9f + 0.08f * pulse), glow)
            c.drawCircle(cx, cy, r * 0.72f, plate)

            ring.color = col; ring.strokeWidth = r * 0.09f; ring.alpha = 230
            rect.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)
            val base = (t * speed) % 360f
            for (i in 0 until 3) c.drawArc(rect, base + i * 120f, 80f, false, ring)

            ring.strokeWidth = r * 0.05f; ring.alpha = 140
            rect.set(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f)
            for (i in 0 until 2) c.drawArc(rect, -base * 1.4f + i * 180f, 110f, false, ring)

            core.color = col; core.alpha = (170 + 85 * pulse).toInt()
            c.drawCircle(cx, cy, r * (0.2f + 0.07f * pulse), core)

            if (running) postInvalidateOnAnimation()
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
        override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    }
}
