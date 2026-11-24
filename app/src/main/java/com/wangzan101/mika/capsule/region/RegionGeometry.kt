package com.wangzan101.mika.capsule.region

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 像素矩形,右边和下边不含。坐标系和截图像素一致,左上是 0。 */
internal data class PxRect(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    val empty: Boolean get() = width <= 0 || height <= 0

    init {
        require(r >= l && b >= t)
    }

    fun contains(x: Int, y: Int): Boolean = x in l until r && y in t until b

    /** [other] 整个落在里面。边可以向外容错 [tolerance] 像素,用来吸收 0–999 来回换算的舍入。 */
    fun contains(other: PxRect, tolerance: Int = 0): Boolean =
        other.l >= l - tolerance && other.t >= t - tolerance && other.r <= r + tolerance && other.b <= b + tolerance

    fun intersects(other: PxRect, tolerance: Int = 0): Boolean {
        val a = inflate(tolerance)
        return a.l < other.r && other.l < a.r && a.t < other.b && other.t < a.b
    }

    fun inflate(p: Int) = PxRect(l - p, t - p, r + p, b + p)

    fun intersect(other: PxRect): PxRect? {
        val nl = max(l, other.l)
        val nt = max(t, other.t)
        val nr = min(r, other.r)
        val nb = min(b, other.b)
        return if (nr > nl && nb > nt) PxRect(nl, nt, nr, nb) else null
    }
}

/**
 * 冻结帧铺在全屏上的位置。宽高比一致时贴满屏幕(尺寸相同就是 1:1);
 * 只有宽高比不同才在多出来的边上留黑边,框选坐标按这个映射回到截图像素。
 */
internal data class FrameMap(
    val viewW: Int,
    val viewH: Int,
    val bitmapW: Int,
    val bitmapH: Int,
    val scale: Float,
    val originX: Float,
    val originY: Float,
) {
    val contentW: Float get() = bitmapW * scale
    val contentH: Float get() = bitmapH * scale

    /** 点在画面外(黑边)时为 null。 */
    fun viewToBitmap(x: Float, y: Float): Pair<Float, Float>? {
        if (scale <= 0f) return null
        val bx = (x - originX) / scale
        val by = (y - originY) / scale
        if (bx < -0.5f || by < -0.5f || bx > bitmapW + 0.5f || by > bitmapH + 0.5f) return null
        return bx.coerceIn(0f, bitmapW.toFloat()) to by.coerceIn(0f, bitmapH.toFloat())
    }

    /** 拖出画面时贴到画面边缘,选区仍能拉到边。 */
    fun viewToBitmapClamped(x: Float, y: Float): Pair<Float, Float> {
        if (scale <= 0f) return 0f to 0f
        val bx = ((x - originX) / scale).coerceIn(0f, bitmapW.toFloat())
        val by = ((y - originY) / scale).coerceIn(0f, bitmapH.toFloat())
        return bx to by
    }

    fun bitmapToView(x: Float, y: Float): Pair<Float, Float> = originX + x * scale to originY + y * scale

    fun viewRectOf(rect: PxRect): ViewRect {
        val (l, t) = bitmapToView(rect.l.toFloat(), rect.t.toFloat())
        val (r, b) = bitmapToView(rect.r.toFloat(), rect.b.toFloat())
        return ViewRect(l, t, r, b)
    }

    companion object {
        fun of(viewW: Int, viewH: Int, bitmapW: Int, bitmapH: Int): FrameMap {
            if (viewW <= 0 || viewH <= 0 || bitmapW <= 0 || bitmapH <= 0) {
                return FrameMap(viewW, viewH, bitmapW, bitmapH, 1f, 0f, 0f)
            }
            val scale = min(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
            val cw = bitmapW * scale
            val ch = bitmapH * scale
            return FrameMap(viewW, viewH, bitmapW, bitmapH, scale, (viewW - cw) / 2f, (viewH - ch) / 2f)
        }
    }
}

internal data class ViewRect(val l: Float, val t: Float, val r: Float, val b: Float)

/** 视图宽高比相对一开始变了这么多,就当成转了屏:旧的冻结帧对不上,这次取消。 */
internal fun aspectShift(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
    if (minOf(w1, h1, w2, h2) <= 0) return false
    val a = w1.toFloat() / h1
    val b = w2.toFloat() / h2
    return abs(a - b) / max(a, b) > ASPECT_SHIFT
}

/** 0–999 映射到 `0..size`(size 是不含的右/下边)。和 [com.wangzan101.mika.core.screen.uistate.normalize] 互逆到舍入误差以内。 */
internal fun normToPx(n: Int, size: Int): Int {
    if (size <= 0) return 0
    return (n.coerceIn(0, 999) * size / 999.0).roundToInt().coerceIn(0, size)
}

/** 把一块屏幕像素矩形按比例放进截图像素。两边尺寸一样时原样返回。 */
internal fun scaleRect(rect: PxRect, fromW: Int, fromH: Int, toW: Int, toH: Int): PxRect {
    if (fromW <= 0 || fromH <= 0 || toW <= 0 || toH <= 0) return PxRect(0, 0, 0, 0)
    fun sx(v: Int) = (v.toDouble() * toW / fromW).roundToInt().coerceIn(0, toW)
    fun sy(v: Int) = (v.toDouble() * toH / fromH).roundToInt().coerceIn(0, toH)
    return boxInside(sx(rect.l), sy(rect.t), sx(rect.r), sy(rect.b), PxRect(0, 0, toW, toH))
}

/** 把可能颠倒、越界的边收成画面里至少 1 像素的矩形。画面本身没有像素时为空矩形。 */
internal fun boxInside(l0: Int, t0: Int, r0: Int, b0: Int, bounds: PxRect): PxRect {
    if (bounds.width <= 0 || bounds.height <= 0) return PxRect(bounds.l, bounds.t, bounds.l, bounds.t)
    var l = min(l0, r0)
    var r = max(l0, r0)
    var t = min(t0, b0)
    var b = max(t0, b0)
    l = l.coerceIn(bounds.l, bounds.r - 1)
    t = t.coerceIn(bounds.t, bounds.b - 1)
    r = r.coerceIn(l + 1, bounds.r)
    b = b.coerceIn(t + 1, bounds.b)
    return PxRect(l, t, r, b)
}

private const val ASPECT_SHIFT = 0.08f
