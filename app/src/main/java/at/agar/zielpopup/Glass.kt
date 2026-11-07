package at.agar.zielpopup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
    val ORANGE = 0xFFFFC46B.toInt()

    /** LED-Farben im Popup: grün offen, rot geschlossen, weiß unklar */
    val LED_GREEN = 0xFF4CD964.toInt()
    val LED_RED = 0xFFFF453A.toInt()
    val LED_WHITE = 0xFFFFFFFF.toInt()

    /** Runde LED; weiß bekommt einen feinen grauen Rand */
    fun dot(ctx: Context, color: Int, sizeDp: Int = 12) = android.view.View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (color == LED_WHITE) setStroke(ctx.dp(1).coerceAtLeast(1), 0xFF8A8C96.toInt())
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun ledColor(s: OpenState) = when (s) {
        OpenState.OPEN -> LED_GREEN
        OpenState.CLOSED -> LED_RED
        OpenState.UNKNOWN -> LED_WHITE
    }

    /** Glasfläche: Füllung + Glanzverlauf + heller Rand */
    fun surface(ctx: Context, radiusDp: Int, fill: Int = 0x1FFFFFFF, stroke: Int = 0xB3FFFFFF.toInt()): Drawable =
        GlassDrawable(ctx.dp(radiusDp).toFloat(), fill, stroke, ctx.dp(1).toFloat().coerceAtLeast(1.5f))

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

/**
 * Liquid-Glass-Fläche:
 *  - durchscheinende Füllung
 *  - Glanzlicht oben (wie Licht, das auf Glas fällt)
 *  - leichtes Leuchten unten (Lichtbrechung)
 *  - Rand mit Lichtverlauf: oben links hell, Mitte zart, unten rechts wieder heller
 */
class GlassDrawable(
    private val radius: Float,
    fill: Int,
    private val rim: Int,
    private val strokeW: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val half = strokeW / 2
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        val h = b.height().toFloat()
        sheenPaint.shader = LinearGradient(0f, top, 0f, top + h * 0.55f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        glowPaint.shader = LinearGradient(0f, bottom - h * 0.35f, 0f, bottom, 0x00FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
        val rimSoft = (rim and 0x00FFFFFF) or 0x1A000000
        val rimMid = (rim and 0x00FFFFFF) or 0x59000000
        rimPaint.shader = LinearGradient(
            b.left.toFloat(), top, b.right.toFloat(), bottom,
            intArrayOf(rim, rimSoft, rimSoft, rimMid),
            floatArrayOf(0f, 0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        c.drawRoundRect(rect, radius, radius, fillPaint)
        c.drawRoundRect(rect, radius, radius, sheenPaint)
        c.drawRoundRect(rect, radius, radius, glowPaint)
        c.drawRoundRect(rect, radius, radius, rimPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 0.25f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** ScrollView mit Höchsthöhe – damit lange Listen im Popup scrollen statt den Bildschirm zu füllen */
class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST))
    }
}
