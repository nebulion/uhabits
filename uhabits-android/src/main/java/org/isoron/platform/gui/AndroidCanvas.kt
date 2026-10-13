/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.platform.gui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import org.isoron.uhabits.utils.InterfaceUtils.getFontAwesome

class AndroidCanvas : Canvas {

    lateinit var context: Context

    lateinit var innerCanvas: android.graphics.Canvas
    var innerBitmap: Bitmap? = null
    var innerDensity = 1.0
    var innerWidth = 0
    var innerHeight = 0
    var mHeight = 15

    var paint = Paint().apply {
        isAntiAlias = true
    }
    var textPaint = TextPaint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    var textBounds = Rect()

    private fun Double.toDp() = (this * innerDensity).toFloat()

    /**
     * Sets the drawing colour, snapping everything to pure black or pure white
     * and expressing anything in between as a halftone of black dots.
     *
     * The panel has no midtones. A grey handed to it is dithered by the
     * hardware into a muddy, ghost-prone patch, which is why Mudita's own apps
     * never send one: the e-reader draws its placeholder covers and its
     * disabled scroll arrow as visible dot patterns instead. Doing the
     * dithering here rather than leaving it to the panel keeps it crisp and
     * predictable at 1-bit.
     *
     * Text is never halftoned -- a screened glyph is unreadable at body sizes
     * -- so it snaps to whichever of black or white is nearer. Every text
     * colour in the app is already black, so in practice this only guards
     * against a stray one.
     */
    override fun setColor(color: Color) {
        val argb = color.toInt()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b

        textPaint.color = if (luminance >= 128) android.graphics.Color.WHITE
        else android.graphics.Color.BLACK

        // Ink coverage runs opposite to luminance: a light grey becomes a
        // sparse scatter of black dots, a dark one a dense field.
        Halftone.apply(paint, argb)
    }

    override fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double) {
        innerCanvas.drawLine(
            x1.toDp(),
            y1.toDp(),
            x2.toDp(),
            y2.toDp(),
            paint
        )
    }

    override fun drawText(text: String, x: Double, y: Double) {
        innerCanvas.drawText(
            text,
            x.toDp(),
            y.toDp() + 0.6f * mHeight,
            textPaint
        )
    }

    override fun fillRect(x: Double, y: Double, width: Double, height: Double) {
        paint.style = Paint.Style.FILL
        rect(x, y, width, height)
    }

    override fun fillRoundRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        cornerRadius: Double
    ) {
        paint.style = Paint.Style.FILL
        innerCanvas.drawRoundRect(
            x.toDp(),
            y.toDp(),
            (x + width).toDp(),
            (y + height).toDp(),
            cornerRadius.toDp(),
            cornerRadius.toDp(),
            paint
        )
    }

    override fun drawRect(x: Double, y: Double, width: Double, height: Double) {
        paint.style = Paint.Style.STROKE
        rect(x, y, width, height)
    }

    private fun rect(x: Double, y: Double, width: Double, height: Double) {
        innerCanvas.drawRect(
            x.toDp(),
            y.toDp(),
            (x + width).toDp(),
            (y + height).toDp(),
            paint
        )
    }

    override fun getHeight(): Double {
        return innerHeight / innerDensity
    }

    override fun getWidth(): Double {
        return innerWidth / innerDensity
    }

    override fun setFont(font: Font) {
        // The system font, as everywhere else in the app (MmdTheme).
        textPaint.typeface = when (font) {
            Font.REGULAR -> Typeface.DEFAULT
            Font.BOLD -> Typeface.DEFAULT_BOLD
            Font.FONT_AWESOME -> getFontAwesome(context)
        }
        updateMHeight()
    }

    override fun setFontSize(size: Double) {
        textPaint.textSize = size.toDp()
        updateMHeight()
    }

    private fun updateMHeight() {
        textPaint.getTextBounds("m", 0, 1, textBounds)
        mHeight = textBounds.height()
    }

    override fun setStrokeWidth(size: Double) {
        paint.strokeWidth = size.toDp()
    }

    override fun fillArc(
        centerX: Double,
        centerY: Double,
        radius: Double,
        startAngle: Double,
        swipeAngle: Double
    ) {
        paint.style = Paint.Style.FILL
        innerCanvas.drawArc(
            (centerX - radius).toDp(),
            (centerY - radius).toDp(),
            (centerX + radius).toDp(),
            (centerY + radius).toDp(),
            -startAngle.toFloat(),
            -swipeAngle.toFloat(),
            true,
            paint
        )
    }

    override fun fillCircle(
        centerX: Double,
        centerY: Double,
        radius: Double
    ) {
        paint.style = Paint.Style.FILL
        innerCanvas.drawCircle(centerX.toDp(), centerY.toDp(), radius.toDp(), paint)
    }

    override fun setTextAlign(align: TextAlign) {
        textPaint.textAlign = when (align) {
            TextAlign.LEFT -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
        }
    }

    override fun toImage(): Image {
        val bmp = innerBitmap ?: throw UnsupportedOperationException()
        return AndroidImage(bmp)
    }

    override fun measureText(text: String): Double {
        return textPaint.measureText(text) / innerDensity
    }
}
