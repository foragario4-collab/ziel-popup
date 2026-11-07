package at.agar.zielpopup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/** "Liquid Glass"-Look: durchscheinende Flächen, feiner heller Rand, Glanz oben */
object Glass {
    val TEXT = 0xFFFFFFFF.toInt()
    val TEXT_SOFT = 0xB3FFFFFF.toInt()
    val GREEN = 0xFF8DF5A6.toInt()
    val GREY = 0xFFC9CBD6.toInt()
    val RED = 0xFFFF9C8F.toInt()

    /** Glasfläche: Füllung + Glanzverlauf + heller Rand */
    fun surface(ctx: Context, radiusDp: Int, fill: Int = 0x24FFFFFF, stroke: Int = 0x59FFFFFF): Drawable {
        val r = ctx.dp(radiusDp).toFloat()
        val base = GradientDrawable().apply {
            cornerRadius = r
            setColor(fill)
            setStroke(ctx.dp(1).coerceAtLeast(1), stroke)
        }
        val shine = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf<Drawable>(base, shine))
    }

    fun pressable(ctx: Context, radiusDp: Int, fill: Int, stroke: Int = 0x66FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), surface(ctx, radiusDp, fill, stroke), null)

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            background = pressable(ctx, 28, if (primary) 0x664F8CFF else 0x22FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun input(ctx: Context, hintText: String, single: Boolean) = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(0x80FFFFFF.toInt())
        setTextColor(TEXT)
        textSize = 17f
        setSingleLine(single)
        background = surface(ctx, 20, 0x1AFFFFFF, 0x4DFFFFFF)
        setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
    }

    fun label(ctx: Context, s: String, sizeSp: Float = 14f, color: Int = TEXT_SOFT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    /** Hintergrund der App: tiefer Farbverlauf */
    fun backdrop(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF0B1026.toInt(), 0xFF2A1B5E.toInt(), 0xFF0E4A5C.toInt())
    )

    /** Weiche Farbkugel: Farbverlauf zur Mitte hin, läuft am Rand unsichtbar aus (keine harten Kanten) */
    fun blob(ctx: Context, color: Int, sizeDp: Int) = android.view.View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = ctx.dp(sizeDp) / 2f
            colors = intArrayOf(color, color and 0x00FFFFFF or 0x40000000, color and 0x00FFFFFF)
        }
        layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun stateColor(s: OpenState?) = when (s) {
        OpenState.OPEN -> GREEN
        OpenState.UNKNOWN -> GREY
        OpenState.CLOSED -> RED
        null -> TEXT
    }

    @Suppress("unused")
    fun transparent() = Color.TRANSPARENT
}

/** ScrollView mit Höchsthöhe – damit lange Listen im Popup scrollen statt den Bildschirm zu füllen */
class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST))
    }
}
