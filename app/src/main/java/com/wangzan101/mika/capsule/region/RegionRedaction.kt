package com.wangzan101.mika.capsule.region

import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.normalize
import com.wangzan101.mika.core.screen.user.ScreenLine
import com.wangzan101.mika.core.screen.user.UserScreenText
import kotlin.math.min

/** 一行可读文字在截图像素里的范围。[secret] 的内容整行丢掉,不进模型。 */
internal data class RegionSpan(
    val text: String,
    val bounds: PxRect,
    val fromOcr: Boolean = false,
    val secret: Boolean = false,
    val authored: Boolean = false,
)

/**
 * 无障碍树的框是相对屏幕的 0–999,认出来的字是相对截图的 0–999。
 * 两边都换成截图像素,后面只按用户选区和马赛克决定留哪些。
 */
internal fun spansOf(
    elements: List<UiElement>,
    screenW: Int,
    screenH: Int,
    ocr: List<VisibleTextLine>?,
    bitmapW: Int,
    bitmapH: Int,
): List<RegionSpan> {
    val tree = elements.mapNotNull { element ->
        val b = element.bounds
        if (b.size < 4 || element.name.isBlank()) return@mapNotNull null
        val screen = pxOf(b, screenW, screenH) ?: return@mapNotNull null
        RegionSpan(element.name, scaleRect(screen, screenW, screenH, bitmapW, bitmapH), secret = element.secret)
    }
    val recognized = ocr.orEmpty().mapNotNull { line ->
        if (line.bounds.size < 4 || line.text.isBlank()) return@mapNotNull null
        val rect = pxOf(line.bounds, bitmapW, bitmapH) ?: return@mapNotNull null
        RegionSpan(line.text, rect, fromOcr = true)
    }
    return tree + recognized
}

/**
 * 发给模型的字只留选区里的。跨出选区的整行丢掉(没有逐字边界,不能只切一半)。
 * 马赛克按涂抹顺序盖住的行也丢掉;之后写在马赛克上的字留下。
 * 坐标换成选区自己的 0–999,不再用整屏的位置。
 */
internal object RegionText {
    const val TOLERANCE = 2

    fun apply(spans: List<RegionSpan>, marks: List<RegionMark>, crop: PxRect): Pair<List<ScreenLine>, Boolean> {
        if (crop.width <= 0 || crop.height <= 0) return emptyList<ScreenLine>() to false
        val inside = spans.filter { span ->
            !span.secret && span.text.isNotBlank() && !span.bounds.empty && crop.contains(span.bounds, TOLERANCE)
        }
        var live = inside.filterNot { isContainer(it, inside) }
        for (mark in marks) {
            when (mark) {
                is RegionMark.Mosaic -> {
                    val box = mark.rect.intersect(crop) ?: continue
                    live = live.filterNot { it.bounds.intersects(box, TOLERANCE) }
                }
                is RegionMark.Arrow -> Unit
                is RegionMark.Label -> {
                    if (mark.text.isBlank()) continue
                    if (!crop.contains(mark.bounds, TOLERANCE)) continue
                    live = live + RegionSpan(mark.text, mark.bounds, authored = true)
                }
            }
        }
        val lines = live.map { span ->
            val cx = (span.bounds.l + span.bounds.r) / 2 - crop.l
            val cy = (span.bounds.t + span.bounds.b) / 2 - crop.t
            ScreenLine(clip(span.text), normalize(cx, crop.width), normalize(cy, crop.height)) to span.fromOcr
        }.distinctBy { (line, _) -> Triple(line.text, line.x / 20, line.y / 20) }
            .sortedWith(compareBy({ it.first.y / ROW }, { it.first.x }))
            .take(UserScreenText.MAX_LINES)
        return lines.map { it.first } to lines.any { it.second }
    }

    /** 名字只是把里面几段连起来时丢掉外框,避免同一句话留两份;马赛克打在内文上时外框也不会把原文带回去。 */
    private fun isContainer(span: RegionSpan, all: List<RegionSpan>): Boolean {
        if (span.authored) return false
        val inner = all.filter { other ->
            other !== span && span.bounds.contains(other.bounds, TOLERANCE) &&
                other.bounds.width.toLong() * other.bounds.height < span.bounds.width.toLong() * span.bounds.height
        }
        if (inner.isEmpty()) return false
        var rest = span.text
        inner.map { it.text.trim() }.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.none { it.isLetterOrDigit() }
    }

    private fun clip(t: String): String {
        val one = t.replace('\n', ' ').trim()
        return if (one.length <= UserScreenText.MAX_CHARS) one else one.take(UserScreenText.MAX_CHARS) + "…"
    }

    private const val ROW = 12
}

/** 马赛克块有多大:小选区用小块,大选区用大块,块里的字不能再被认出来。 */
internal fun mosaicBlock(width: Int, height: Int): Int {
    val side = min(width, height)
    if (side <= 2) return 1
    return (side / 12).coerceIn(8, 48).coerceAtMost(side)
}

/**
 * 把 [rect] 里的像素换成块平均色。块外一个像素不动。
 * 返回新数组,调用方用它覆盖画面,不另留原图。
 */
internal fun mosaicPixels(pixels: IntArray, width: Int, height: Int, rect: PxRect, block: Int): IntArray {
    val out = pixels.copyOf()
    val area = rect.intersect(PxRect(0, 0, width, height)) ?: return out
    val step = block.coerceAtLeast(1)
    var y = area.t
    while (y < area.b) {
        val bh = min(step, area.b - y)
        var x = area.l
        while (x < area.r) {
            val bw = min(step, area.r - x)
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            val count = bw * bh
            for (dy in 0 until bh) {
                val row = (y + dy) * width
                for (dx in 0 until bw) {
                    val c = out[row + x + dx]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
            }
            val color = ((a / count).toInt() shl 24) or ((r / count).toInt() shl 16) or
                ((g / count).toInt() shl 8) or (b / count).toInt()
            for (dy in 0 until bh) {
                val row = (y + dy) * width
                for (dx in 0 until bw) out[row + x + dx] = color
            }
            x += step
        }
        y += step
    }
    return out
}

/** 只拷出选区里的像素。选区外的颜色不会出现在结果里。 */
internal fun cropPixels(pixels: IntArray, width: Int, height: Int, crop: PxRect): IntArray {
    val area = crop.intersect(PxRect(0, 0, width, height)) ?: return IntArray(0)
    val out = IntArray(area.width * area.height)
    for (y in 0 until area.height) {
        val from = (area.t + y) * width + area.l
        pixels.copyInto(out, y * area.width, from, from + area.width)
    }
    return out
}

private fun pxOf(bounds: List<Int>, width: Int, height: Int): PxRect? {
    if (width <= 0 || height <= 0) return null
    val rect = boxInside(normToPx(bounds[0], width), normToPx(bounds[1], height), normToPx(bounds[2], width), normToPx(bounds[3], height), PxRect(0, 0, width, height))
    return rect.takeUnless { it.empty }
}
