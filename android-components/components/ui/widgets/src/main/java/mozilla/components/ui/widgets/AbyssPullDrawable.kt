/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.ui.widgets

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.PI
import kotlin.math.cos

/**
 * Neon pull-to-refresh indicator for Abyss. A full circle shrinks to a dot while the
 * user pulls down, grows back if the user pulls up, and pulses while refreshing.
 */
internal class AbyssPullDrawable(
    private val layout: SwipeRefreshLayout,
    private val circle: ImageView,
) : Drawable() {

    private val density = layout.resources.displayMetrics.density
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19E6FF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5519E6FF }
    private var alphaValue = 255

    private fun pullProgress(): Float {
        val end = 64f * density
        if (circle.height <= 0) return 0f
        val shown = (circle.top + circle.height).toFloat()
        return (shown / end).coerceIn(0f, 1f)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val full = b.width() * 0.30f
        val dot = 3f * density
        val radius: Float
        if (layout.isRefreshing) {
            val t = (SystemClock.uptimeMillis() % 900L) / 900f
            val wave = (0.5f - 0.5f * cos(2.0 * PI * t)).toFloat()
            radius = dot + dot * 1.6f * wave
            invalidateSelf()
        } else {
            val p = pullProgress()
            val eased = p * p * (3f - 2f * p)
            radius = full + (dot - full) * eased
        }
        core.alpha = alphaValue
        glow.alpha = alphaValue * 85 / 255
        canvas.drawCircle(cx, cy, radius * 1.4f, glow)
        canvas.drawCircle(cx, cy, radius, core)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        core.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}