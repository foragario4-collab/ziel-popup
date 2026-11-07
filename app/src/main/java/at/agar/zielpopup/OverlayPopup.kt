package at.agar.zielpopup

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Das schwebende Fenster, das über Uber/Bolt angezeigt wird */
object OverlayPopup {
    private const val SHOW_MS = 20000L
    private var current: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context, result: AnalysisResult) {
        val ctx = context.applicationContext
        if (!Settings.canDrawOverlays(ctx)) return
        hide(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE1B1B26.toInt())
                cornerRadius = ctx.dp(16).toFloat()
            }
            setOnClickListener { hide(ctx) }
        }
        box.addView(TextView(ctx).apply {
            text = result.title
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        })
        for (line in result.lines) {
            box.addView(TextView(ctx).apply {
                text = line.text
                textSize = 16f
                setPadding(0, ctx.dp(4), 0, 0)
                if (line.bold) setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when (line.state) {
                        OpenState.OPEN -> 0xFF8DF5A6.toInt()
                        OpenState.UNKNOWN -> 0xFFB8B8C4.toInt()
                        OpenState.CLOSED -> 0xFFFF9C8F.toInt()
                        null -> 0xFFFFFFFF.toInt()
                    }
                )
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = ctx.dp(48)
            horizontalMargin = 0.03f
        }
        wm.addView(box, params)
        current = box
        handler.postDelayed({ hide(ctx) }, SHOW_MS)
    }

    fun hide(context: Context) {
        handler.removeCallbacksAndMessages(null)
        val v = current ?: return
        current = null
        try {
            (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }
}
