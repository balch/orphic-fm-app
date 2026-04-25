package org.balch.djapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Composes the album art for a vibe: base artwork + the vibe name typeset
 * across the bottom. Intentionally carries *no* transient state (section,
 * bar, etc.) — the now-playing surface on Android Auto should only change
 * when the song itself changes, not per-beat.
 *
 * Cached by vibe title so repeat renders reuse the bitmap.
 */
class SectionArtRenderer(
    private val base: Bitmap,
) {
    private var lastTitle: String? = null
    private var cached: Bitmap? = null

    fun render(title: String): Bitmap {
        if (title == lastTitle) cached?.let { return it }
        val out = draw(title)
        lastTitle = title
        cached = out
        return out
    }

    private fun draw(title: String): Bitmap {
        val w = base.width
        val h = base.height
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // Bottom gradient band so typography stays legible over any art.
        val bandHeight = h * 0.32f
        val bandTop = h - bandHeight
        val bandPaint = Paint().apply {
            shader = LinearGradient(
                0f, bandTop, 0f, h.toFloat(),
                intArrayOf(
                    Color.argb(0x00, 0, 0, 0),
                    Color.argb(0xC8, 0, 0, 0),
                    Color.argb(0xF0, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, bandTop, w.toFloat(), h.toFloat(), bandPaint)

        // Accent stripe above the title for visual lift.
        val padX = w * 0.06f
        val accentPaint = Paint().apply {
            color = Color.parseColor("#7B68EE")
            isAntiAlias = true
        }
        val accentTop = bandTop + bandHeight * 0.15f
        canvas.drawRect(padX, accentTop, padX + w * 0.08f, accentTop + h * 0.008f, accentPaint)

        // Title — bold, condensed, legible at a glance on a car display.
        val titlePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.04f
        }
        val textSize = chooseTextSize(
            paint = titlePaint,
            text = title.uppercase(),
            maxWidth = w - padX * 2f,
            startingSize = h * 0.115f,
            minSize = h * 0.060f,
        )
        titlePaint.textSize = textSize

        val fm = titlePaint.fontMetrics
        val titleBaselineY = h - bandHeight * 0.30f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(title.uppercase(), padX, titleBaselineY, titlePaint)

        return out
    }

    /**
     * Shrink text until it fits [maxWidth], floored at [minSize]. Keeps long
     * vibe names like "DOG HOUSE NIGHT DRIVE" from clipping off the edge.
     */
    private fun chooseTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        startingSize: Float,
        minSize: Float,
    ): Float {
        var size = startingSize
        while (size > minSize) {
            paint.textSize = size
            if (paint.measureText(text) <= maxWidth) return size
            size *= 0.92f
        }
        return minSize
    }
}
