package at.agar.zielpopup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Schwebendes Glas-Fenster über Uber/Bolt.
 * Zeigt alle Treffer (scrollbar), gleitet weich ein,
 * schließt per ✕, Wischen nach oben, Tippen daneben oder nach 25 Sekunden.
 */
object OverlayPopup {
    private const val SHOW_MS = 25000L
    private var current: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private val autoHide = Runnable { appCtx?.let { hide(it) } }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, result: AnalysisResult) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (!Settings.canDrawOverlays(ctx)) return
        removeNow(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(14), ctx.dp(16))
            background = LiquidGlassDrawable(ctx.dp(34).toFloat(), 0xC7141A2A.toInt(), ctx.dp(1).toFloat() * 1.5f)
            elevation = ctx.dp(14).toFloat()
        }

        // Kopf: was ist es (groß) · Adresse · Uhrzeit + Schließen
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        header.addView(ResultView.header(ctx, result),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Glass.TEXT)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF),
                LiquidGlassDrawable(ctx.dp(18).toFloat(), 0x26FFFFFF, ctx.dp(1).toFloat() * 1.5f), null
            )
            contentDescription = "Schließen"
            setOnClickListener { hide(ctx) }
            Glass.pressEffect(this, 0.88f)
        }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { leftMargin = ctx.dp(10) })
        box.addView(header)

        // Kopf nach oben wischen -> Popup wegwischen; loslassen ohne genug Weg -> federt zurück
        var downY = 0f
        var dragging = false
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    dragging = true
                    restartTimer()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dy = (ev.rawY - downY).coerceAtMost(0f)
                        box.translationY = dy
                        box.alpha = (1f + dy / ctx.dp(220)).coerceIn(0.3f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (box.translationY < -ctx.dp(50)) {
                        hide(ctx)
                    } else {
                        box.animate().translationY(0f).alpha(1f).setDuration(420)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                    }
                    true
                }
                else -> false
            }
        }

        // Liste, scrollbar bis ca. 55 % der Bildschirmhöhe
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = MaxHeightScrollView(ctx, maxH).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(ResultView.body(ctx, result))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) restartTimer()
                false
            }
        }
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Tippen außerhalb -> schließen
        box.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) hide(ctx)
            false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = ctx.dp(40)
            horizontalMargin = 0.03f
        }
        softShadows(box)
        try {
            wm.addView(box, params)
        } catch (e: Exception) {
            return
        }
        current = box

        // federnd von oben hereingleiten (wie eine iPhone-Mitteilung)
        box.pivotX = ctx.resources.displayMetrics.widthPixels / 2f
        box.pivotY = 0f
        box.alpha = 0f
        box.translationY = -ctx.dp(44).toFloat()
        box.scaleX = 0.92f
        box.scaleY = 0.92f
        box.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        restartTimer()
    }

    /** Weicher Schatten unter jeder Schrift: bleibt auf dem durchsichtigen Glas gut lesbar */
    private fun softShadows(v: View) {
        if (v is TextView) v.setShadowLayer(v.context.dp(3).toFloat(), 0f, v.context.dp(1).toFloat(), 0x73000000)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) softShadows(v.getChildAt(i))
    }

    private fun restartTimer() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, SHOW_MS)
    }

    /** Mit Animation ausblenden */
    fun hide(context: Context) {
        handler.removeCallbacks(autoHide)
        val v = current ?: return
        current = null
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        v.animate().alpha(0f).translationY(v.translationY - v.context.dp(36))
            .scaleX(0.95f).scaleY(0.95f).setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { try { wm.removeView(v) } catch (_: Exception) { } }
            .start()
    }

    /** Sofort entfernen (wenn gleich ein neues Popup kommt) */
    private fun removeNow(context: Context) {
        handler.removeCallbacks(autoHide)
        val v = current ?: return
        current = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }
}
