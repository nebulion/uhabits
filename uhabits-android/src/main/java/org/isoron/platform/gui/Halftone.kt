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

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * The 4x4 ordered dither the whole app screens its midtones with.
 *
 * The panel has no midtones. A grey handed to it is dithered by the hardware
 * into a muddy, ghost-prone patch, which is why Mudita's own apps never send
 * one: the e-reader draws its placeholder covers and its disabled scroll arrow
 * as visible dot patterns instead. Doing the dithering here rather than
 * leaving it to the panel keeps it crisp and predictable at 1-bit.
 *
 * This lives outside [AndroidCanvas] because the charts are split across two
 * drawing pipelines. Everything that draws through the core [Canvas]
 * abstraction gets screened automatically by `AndroidCanvas.setColor`, but
 * `ScoreChart`, `StreakChart`, `FrequencyChart`, `TargetChart` and `RingView`
 * are `android.view.View` subclasses with their own `onDraw` and never touch
 * that class -- so their greys reached the panel as literal greys. Until they
 * are ported onto the core interface they screen themselves, through here.
 */
object Halftone {

    private val BAYER_4X4 = arrayOf(
        intArrayOf(0, 8, 2, 10),
        intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9),
        intArrayOf(15, 7, 13, 5)
    )

    /**
     * How many device pixels wide each dot of the screen is.
     *
     * **One, deliberately.** At 213dpi a one-pixel Bayer cell is too fine to
     * resolve, so the screens read as smooth greys rather than as visible
     * texture. That is a real trade: Mudita's own placeholders and its disabled
     * scroll arrow *are* visibly dotted, and a coarser dot is the more faithful
     * reading of the guideline. It was tried at 3 (about 0.36mm here) and
     * rejected on the device -- across ninety-odd calendar cells and the streak
     * bars it turns the page into a dense field of dots, and the smooth grey
     * is easier to read.
     *
     * The dithering still earns its place at this size: the panel is handed
     * only pure black and pure white, so it never has to dither a midtone
     * itself, which is what ghosts and smears. This is a halftone that happens
     * to look like grey, not a grey.
     */
    private const val DOT = 1

    private val cache = mutableMapOf<Int, BitmapShader>()

    /**
     * A repeating black-on-white dot pattern whose density is [coverage],
     * where 0.0 is blank paper and 1.0 is solid ink.
     *
     * Quantised to the seventeen patterns a 4x4 ordered dither can express, so
     * that neighbouring greys do not produce subtly different screens.
     */
    fun shader(coverage: Double): BitmapShader {
        val level = (coverage * 16).roundToInt().coerceIn(0, 16)
        return cache.getOrPut(level) {
            val size = 4 * DOT
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val on = BAYER_4X4[y / DOT][x / DOT] < level
                    bmp.setPixel(x, y, if (on) Color.BLACK else Color.WHITE)
                }
            }
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    /** Ink coverage of [argb], accounting for both its luminance and its alpha. */
    fun coverageOf(argb: Int): Double {
        val a = ((argb shr 24) and 0xFF) / 255.0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        // A translucent black is as light as an opaque grey of the same
        // coverage, and both must screen to the same dot density -- RingView
        // asks for its inactive arc as black at 15% alpha.
        return (1.0 - luminance / 255.0) * a
    }

    /**
     * Points [paint] at [argb], screening it if it is not already pure black
     * or pure white. Fully transparent colours are left alone.
     */
    fun apply(paint: Paint, argb: Int) {
        val alpha = (argb shr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val isPure = alpha == 0xFF && r == g && g == b && (r == 0 || r == 255)
        if (isPure || alpha == 0) {
            paint.shader = null
            paint.color = argb
        } else {
            paint.shader = shader(coverageOf(argb))
            paint.color = Color.BLACK
        }
    }
}
