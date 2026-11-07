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
import android.widget.LinearLayout
import android.widget.TextView

/** Das schwebende Glas-Fenster über Uber/Bolt – zeigt ALLE Treffer, scrollbar */
object OverlayPopup {
    private const val SHOW_MS = 25000L
    private var current: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { current?.let { v -> hideView(v.context) } }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, result: AnalysisResult) {
        val ctx = context.applicationContext
        if (!Settings.canDrawOverlays(ctx)) return
        hide(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            // dunkles Glas, damit es über jeder App gut lesbar bleibt
            background = Glass.surface(ctx, 30, 0xD9161A2E.toInt())
            elevation = ctx.dp(12).toFloat()
        }

        // Kopfzeile: Titel + Schließen
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = result.title // Adresse, klein
            textSize = 14f
            setTextColor(Glass.TEXT_SOFT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Glass.TEXT)
            gravity = Gravity.CENTER
            background = Glass.pressable(ctx, 18, 0x26FFFFFF)
            setOnClickListener { hide(ctx) }
        }, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        box.addView(header)

        // Alle Zeilen, scrollbar (max. ca. 55 % der Bildschirmhöhe)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (line in result.lines) {
            list.addView(TextView(ctx).apply {
                text = line.text
                setTextColor(Glass.stateColor(line.state))
                when (line.style) {
                    1 -> { // groß: was ist es
                        textSize = 22f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setPadding(0, ctx.dp(2), 0, ctx.dp(2))
                    }
                    2 -> { // Abschnitt
                        text = line.text.uppercase()
                        textSize = 12f
                        letterSpacing = 0.08f
                        setTextColor(Glass.TEXT_SOFT)
                        setPadding(0, ctx.dp(14), 0, ctx.dp(2))
                    }
                    else -> {
                        textSize = 15.5f
                        setPadding(0, ctx.dp(6), 0, 0)
                        if (line.bold) setTypeface(typeface, Typeface.BOLD)
                    }
                }
            })
        }
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = MaxHeightScrollView(ctx, maxH).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
            // beim Scrollen nicht automatisch schließen
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(autoHide)
                    handler.postDelayed(autoHide, SHOW_MS)
                }
                false
            }
        }
        box.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(4) })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or      // Rest des Bildschirms bleibt bedienbar
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or  // ...aber wir merken, wenn daneben getippt wird
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = ctx.dp(44)
            horizontalMargin = 0.03f
        }
        // Tippen irgendwo außerhalb des Popups -> schließen
        box.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) hide(ctx)
            false
        }
        wm.addView(box, params)
        current = box
        handler.postDelayed(autoHide, SHOW_MS)
    }

    fun hide(context: Context) = hideView(context)

    private fun hideView(context: Context) {
        handler.removeCallbacks(autoHide)
        val v = current ?: return
        current = null
        try {
            (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }
}
