package com.wangzan101.mika.capsule.region

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 把标注画进截图,马赛克改掉像素,再只留下选区。整屏那张用完由调用方回收,这里不另存一份。 */
internal object RegionBitmap {
    const val JPEG_QUALITY = 88

    fun render(src: Bitmap, crop: PxRect, marks: List<RegionMark>): Bitmap {
        require(crop.width > 0 && crop.height > 0)
        require(crop.l >= 0 && crop.t >= 0 && crop.r <= src.width && crop.b <= src.height)
        val full = src.copy(Bitmap.Config.ARGB_8888, true)
        try {
            for (mark in marks) {
                when (mark) {
                    is RegionMark.Mosaic -> mosaicBitmap(full, mark.rect)
                    is RegionMark.Arrow -> drawArrow(Canvas(full), mark)
                    is RegionMark.Label -> drawLabel(Canvas(full), mark)
                }
            }
            val out = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(full, Rect(crop.l, crop.t, crop.r, crop.b), Rect(0, 0, crop.width, crop.height), null)
            return out
        } finally {
            full.recycle()
        }
    }

    fun jpeg(bmp: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private fun mosaicBitmap(bmp: Bitmap, rect: PxRect) {
        val area = rect.intersect(PxRect(0, 0, bmp.width, bmp.height)) ?: return
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val next = mosaicPixels(pixels, bmp.width, bmp.height, area, mosaicBlock(area.width, area.height))
        bmp.setPixels(next, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    private fun drawArrow(canvas: Canvas, mark: RegionMark.Arrow) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val angle = atan2((mark.y2 - mark.y1).toDouble(), (mark.x2 - mark.x1).toDouble())
        val head = 36.0
        val path = Path().apply {
            moveTo(mark.x2, mark.y2)
            lineTo((mark.x2 + cos(angle + 2.6) * head).toFloat(), (mark.y2 + sin(angle + 2.6) * head).toFloat())
            moveTo(mark.x2, mark.y2)
            lineTo((mark.x2 + cos(angle - 2.6) * head).toFloat(), (mark.y2 + sin(angle - 2.6) * head).toFloat())
        }
        fun stroke(color: Int, width: Float) {
            paint.color = color
            paint.strokeWidth = width
            canvas.drawLine(mark.x1, mark.y1, mark.x2, mark.y2, paint)
            canvas.drawPath(path, paint)
        }
        stroke(Color.BLACK, 14f)
        stroke(Color.WHITE, 7f)
    }

    private fun drawLabel(canvas: Canvas, mark: RegionMark.Label) {
        if (mark.text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = mark.bounds.height.coerceAtLeast(1) * 0.85f
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = mark.bounds.b - paint.textSize * 0.12f
        val x = mark.bounds.l.toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (paint.textSize / 6f).coerceAtLeast(2f)
        paint.color = Color.BLACK
        canvas.drawText(mark.text, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawText(mark.text, x, y, paint)
    }
}
