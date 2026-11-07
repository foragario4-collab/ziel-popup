package at.agar.zielpopup

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
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

    /** Akzentfarbe (Hellblau wie bei Private Lock) */
    val ACCENT = 0xFF5AC8FA.toInt()

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
    fun surface(ctx: Context, radiusDp: Int, fill: Int = 0x17FFFFFF, stroke: Int = 0x99FFFFFF.toInt()): Drawable =
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
            background = pressable(ctx, 28, if (primary) 0x665AC8FA else 0x22FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressEffect(this)
        }

    /** Federnder Druck-Effekt: Knopf wird beim Antippen kurz kleiner und springt zurück */
    @SuppressLint("ClickableViewAccessibility")
    fun pressEffect(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale)
                    .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    fun input(ctx: Context, hintText: String, single: Boolean) = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(0x80FFFFFF.toInt())
        setTextColor(TEXT)
        textSize = 17f
        setSingleLine(single)
        background = surface(ctx, 20, 0x14FFFFFF, 0x4DFFFFFF)
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
        intArrayOf(0xFF0A0B14.toInt(), 0xFF0D0F1E.toInt(), 0xFF0A0B14.toInt())
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

/**
 * Schalter im iOS-Stil: Knopf gleitet federnd hinüber und wird dabei kurz breiter (flüssiger Glas-Effekt).
 * onChange wird nur bei Antippen durch den Nutzer aufgerufen – wie beim normalen Switch.
 */
class GlassSwitch(ctx: Context) : View(ctx) {
    var isChecked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private var pos = 0f
    private var stretch = 0f
    private var posAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null
    private val evaluator = ArgbEvaluator()
    private val offColor = 0x33FFFFFF
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat().coerceAtLeast(1f)
        color = 0x40FFFFFF
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000 }
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (isEnabled) setOn(!isChecked, animate = true, fromUser = true)
        }
    }

    fun setOn(checked: Boolean, animate: Boolean = false, fromUser: Boolean = false) {
        isChecked = checked
        val target = if (checked) 1f else 0f
        posAnim?.cancel()
        if (animate) {
            posAnim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 440
                interpolator = OvershootInterpolator(1.3f)
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
            stretchAnim?.cancel()
            stretchAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 340
                interpolator = DecelerateInterpolator()
                addUpdateListener { stretch = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = target
            invalidate()
        }
        if (fromUser) onChange?.invoke(checked)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(32))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = pos.coerceIn(0f, 1f)
        r.set(0f, 0f, w, h)
        trackPaint.color = evaluator.evaluate(p, offColor, Glass.ACCENT) as Int
        c.drawRoundRect(r, h / 2, h / 2, trackPaint)
        val half = rimPaint.strokeWidth / 2
        r.set(half, half, w - half, h - half)
        c.drawRoundRect(r, h / 2, h / 2, rimPaint)

        val inset = context.dp(2).toFloat()
        val kh = h - inset * 2
        val kw = kh + stretch * context.dp(7)
        val minX = inset
        val maxX = w - inset - kw
        val x = (minX + (maxX - minX) * pos).coerceIn(inset / 2, w - inset / 2 - kw)
        val drop = context.dp(1).toFloat() * 1.5f
        r.set(x, inset + drop, x + kw, inset + kh + drop)
        c.drawRoundRect(r, kh / 2, kh / 2, shadowPaint)
        r.set(x, inset, x + kw, inset + kh)
        c.drawRoundRect(r, kh / 2, kh / 2, knobPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }
}

/**
 * Auswahl-Leiste (Segmented Control): das helle Glas-Element gleitet federnd
 * zur gewählten Option und wird beim Gleiten kurz größer.
 */
class GlassSegmented(
    ctx: Context,
    labels: List<String>,
    initial: Int,
    private val onSelect: (Int) -> Unit
) : LinearLayout(ctx) {
    var selected = initial
        private set
    private var pos = initial.toFloat()
    private var stretch = 0f
    private var posAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null
    private val items = ArrayList<TextView>()
    private val thumb = GlassDrawable(
        ctx.dp(14).toFloat(), 0x38FFFFFF, 0xCCFFFFFF.toInt(), ctx.dp(1).toFloat().coerceAtLeast(1.5f)
    )

    init {
        orientation = LinearLayout.HORIZONTAL
        val pad = ctx.dp(4)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(18).toFloat()
            setColor(0x40000000)
        }
        setWillNotDraw(false)
        labels.forEachIndexed { i, s ->
            val t = TextView(ctx).apply {
                text = s
                textSize = 15f
                setTextColor(Glass.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(ctx.dp(6), ctx.dp(11), ctx.dp(6), ctx.dp(11))
                alpha = if (i == initial) 1f else 0.65f
                isClickable = true
                setOnClickListener { select(i) }
            }
            items.add(t)
            addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun select(i: Int) {
        if (i == selected) return
        selected = i
        posAnim?.cancel()
        posAnim = ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = 520
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        stretchAnim?.cancel()
        stretchAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            addUpdateListener { stretch = it.animatedValue as Float; invalidate() }
            start()
        }
        items.forEachIndexed { k, t -> t.animate().alpha(if (k == i) 1f else 0.65f).setDuration(250).start() }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onSelect(i)
    }

    override fun dispatchDraw(c: Canvas) {
        val n = items.size
        if (n > 0 && width > 0) {
            val seg = (width - paddingLeft - paddingRight).toFloat() / n
            val sx = stretch * context.dp(6)
            val sy = stretch * context.dp(2)
            val left = paddingLeft + seg * pos - sx
            val right = paddingLeft + seg * (pos + 1) + sx
            thumb.setBounds(left.toInt(), (paddingTop - sy).toInt(), right.toInt(), (height - paddingBottom + sy).toInt())
            thumb.draw(c)
        }
        super.dispatchDraw(c)
    }
}

/** Kategorie-Chip zum An-/Ausschalten (statt Häkchen-Kästchen) */
class GlassChip(
    ctx: Context,
    label: String,
    on: Boolean,
    private val onToggle: (Boolean) -> Unit
) : TextView(ctx) {
    var isOn = on
        private set

    init {
        text = label
        textSize = 14.5f
        setTextColor(Glass.TEXT)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        isClickable = true
        isFocusable = true
        refresh()
        setOnClickListener {
            isOn = !isOn
            refresh()
            scaleX = 0.9f
            scaleY = 0.9f
            animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onToggle(isOn)
        }
    }

    private fun refresh() {
        background = if (isOn) Glass.surface(context, 18, 0x405AC8FA, 0xCC9FE3FF.toInt())
        else Glass.surface(context, 18, 0x0FFFFFFF, 0x40FFFFFF)
        animate().alpha(if (isOn) 1f else 0.6f).setDuration(200).start()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isOn
    }
}

/**
 * Liquid Glass wie bei iOS 26 (für das Popup):
 *  - klarere, leicht getönte Füllung
 *  - diagonaler Lichtschimmer über die Fläche
 *  - Lichtkante: oben links und unten rechts hell, dazwischen fast unsichtbar
 *  - feine dunkle Innenkante für Tiefe
 */
class LiquidGlassDrawable(
    private val radius: Float,
    fill: Int,
    private val rimW: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimW
        color = 0x2E000000
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimW
    }
    private val rect = RectF()
    private val inner = RectF()
    private val sheenRect = RectF()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bt = b.bottom.toFloat()
        rect.set(l + rimW / 2, t + rimW / 2, r - rimW / 2, bt - rimW / 2)
        inner.set(rect.left + rimW, rect.top + rimW, rect.right - rimW, rect.bottom - rimW)
        tintPaint.shader = LinearGradient(
            l, t, r, bt,
            intArrayOf(0x29FFFFFF, 0x08FFFFFF, 0x14FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        val h = b.height().toFloat()
        sheenRect.set(l + radius * 0.6f, t + rimW, l + b.width() * 0.6f, t + h * 0.22f + rimW)
        sheenPaint.shader = LinearGradient(0f, sheenRect.top, 0f, sheenRect.bottom, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rimPaint.shader = LinearGradient(
            l, t, r, bt,
            intArrayOf(0xF2FFFFFF.toInt(), 0x40FFFFFF, 0x10FFFFFF, 0x33FFFFFF, 0xCCFFFFFF.toInt()),
            floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        c.drawRoundRect(rect, radius, radius, fillPaint)
        c.drawRoundRect(rect, radius, radius, tintPaint)
        c.drawRoundRect(inner, radius - rimW, radius - rimW, innerPaint)
        c.drawRoundRect(rect, radius, radius, rimPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = 0.35f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
