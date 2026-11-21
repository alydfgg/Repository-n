package com.exfiles.app

import android.graphics.*
import android.graphics.drawable.Drawable

// 0 folder, 1 image, 2 video, 3 audio, 4 apk, 5 zip, 6 text, 7 other
class FileIconDrawable(private val kind: Int, ext: String) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = ext.uppercase().take(4)
    private val cols = intArrayOf(0xFF78AEFF.toInt(), 0xFFEF5350.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
        0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFFE6C15A.toInt(), 0xFF90A4C0.toInt())

    override fun getIntrinsicWidth() = 100
    override fun getIntrinsicHeight() = 100

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val l = b.left.toFloat(); val t = b.top.toFloat()
        if (kind == 0) {
            p.color = 0xFF5E9CFA.toInt()
            c.drawRoundRect(RectF(l + w * 0.06f, t + h * 0.16f, l + w * 0.50f, t + h * 0.40f), w * 0.06f, w * 0.06f, p)
            p.color = cols[0]
            c.drawRoundRect(RectF(l + w * 0.06f, t + h * 0.26f, l + w * 0.94f, t + h * 0.84f), w * 0.07f, w * 0.07f, p)
            return
        }
        val x0 = l + w * 0.20f; val x1 = l + w * 0.80f
        val y0 = t + h * 0.06f; val y1 = t + h * 0.94f
        val f = w * 0.22f
        val body = cols[kind.coerceIn(0, 7)]
        p.style = Paint.Style.FILL; p.color = body
        val pg = Path()
        pg.moveTo(x0, y0); pg.lineTo(x1 - f, y0); pg.lineTo(x1, y0 + f); pg.lineTo(x1, y1); pg.lineTo(x0, y1); pg.close()
        c.drawPath(pg, p)
        p.color = Color.argb(70, 0, 0, 0)
        val fold = Path()
        fold.moveTo(x1 - f, y0); fold.lineTo(x1 - f, y0 + f); fold.lineTo(x1, y0 + f); fold.close()
        c.drawPath(fold, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
        if (label.isNotEmpty() && kind != 7) {
            p.textSize = w * (if (label.length > 3) 0.15f else 0.19f)
            c.drawText(label, (x0 + x1) / 2, t + h * 0.66f, p)
        } else {
            p.textSize = w * 0.34f
            c.drawText(if (kind == 7 && label.isNotEmpty()) label.take(3) else "?", (x0 + x1) / 2, t + h * 0.66f, p)
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
