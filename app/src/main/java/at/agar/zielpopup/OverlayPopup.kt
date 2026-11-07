package at.agar.zielpopup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Schwebendes Glas-Fenster über Uber/Bolt.
 * Zeigt alle Treffer (scrollbar), gleitet weich ein,
 * schließt per ✕, Tippen daneben oder nach 25 Sekunden.
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
            background = Glass.surface(ctx, 30, 0xE0141828.toInt())
            elevation = ctx.dp(14).toFloat()
        }

        // Kopf: Adresse klein + Schließen
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = result.title
            textSize = 14f
            setTextColor(Glass.TEXT_SOFT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Glass.TEXT)
            gravity = Gravity.CENTER
            background = Glass.pressable(ctx, 16, 0x26FFFFFF)
            setOnClickListener { hide(ctx) }
        }, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
        box.addView(header)

        // Inhalt, scrollbar bis ca. 55 % der Bildschirmhöhe
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = MaxHeightScrollView(ctx, maxH).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(ResultView.lines(ctx, result))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) restartTimer()
                false
            }
        }
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(4) })

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
        try {
            wm.addView(box, params)
        } catch (e: Exception) {
            return
        }
        current = box

        // weich von oben hereingleiten
        box.alpha = 0f
        box.translationY = -ctx.dp(24).toFloat()
        box.animate().alpha(1f).translationY(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
        restartTimer()
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
        v.animate().alpha(0f).translationY(-v.context.dp(16).toFloat()).setDuration(180)
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
