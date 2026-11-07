package at.agar.zielpopup

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView

/** Zeichnet ein Ergebnis – gleich im Popup und in der App */
object ResultView {

    fun lines(ctx: Context, result: AnalysisResult): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (line in result.lines) {
            when (line.style) {
                1 -> list.addView(TextView(ctx).apply { // groß: was ist es
                    text = line.text
                    textSize = 23f
                    setTextColor(Glass.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(0, ctx.dp(2), 0, ctx.dp(4))
                })
                2 -> {
                    list.addView(divider(ctx), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1).coerceAtLeast(1)
                    ).apply { topMargin = ctx.dp(12) })
                    list.addView(TextView(ctx).apply { // Abschnitt
                        text = line.text.uppercase()
                        textSize = 12f
                        letterSpacing = 0.08f
                        setTextColor(Glass.TEXT_SOFT)
                        setPadding(0, ctx.dp(10), 0, ctx.dp(2))
                    })
                }
                else -> list.addView(TextView(ctx).apply {
                    text = withDot(line)
                    textSize = 15.5f
                    setTextColor(if (line.state == null) Glass.TEXT_SOFT else Glass.TEXT)
                    setLineSpacing(0f, 1.1f)
                    setPadding(0, ctx.dp(6), 0, 0)
                    if (line.bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            }
        }
        return list
    }

    /** Farbiger Punkt vor Zeilen mit Status: grün offen, grau unbekannt, rot zu */
    private fun withDot(line: PopupLine): CharSequence {
        if (line.state == null) return line.text
        val sb = SpannableStringBuilder("●  ").append(line.text)
        sb.setSpan(ForegroundColorSpan(Glass.stateColor(line.state)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun divider(ctx: Context) = android.view.View(ctx).apply {
        background = GradientDrawable().apply { setColor(0x26FFFFFF) }
    }
}
