package com.wangzan101.mika.capsule.region

import kotlin.math.hypot
import kotlin.math.roundToInt

/** 长按相机按钮:到点算长按之后,松手不再算短按。 */
internal class CameraPress(private val slopPx: Float) {
    enum class Phase { Idle, Down, Long, Drag }
    enum class Release { Click, Ignore }

    var phase: Phase = Phase.Idle
        private set

    fun down() {
        phase = Phase.Down
    }

    fun move(dist: Float): Phase {
        if (phase == Phase.Down && dist > slopPx) phase = Phase.Drag
        return phase
    }

    /** 只有还停在按下、没滑开时才算长按,并且只算一次。 */
    fun longPress(): Boolean {
        if (phase != Phase.Down) return false
        phase = Phase.Long
        return true
    }

    fun up(): Release {
        val click = phase == Phase.Down
        phase = Phase.Idle
        return if (click) Release.Click else Release.Ignore
    }

    fun cancel() {
        phase = Phase.Idle
    }
}

internal enum class RegionTool { Box, Mosaic, Arrow, Text }

internal enum class BoxHandle { Create, Move, N, S, E, W, NE, NW, SE, SW }

internal sealed class RegionMark {
    data class Mosaic(val rect: PxRect) : RegionMark()
    data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : RegionMark()
    data class Label(val text: String, val bounds: PxRect) : RegionMark()
}

internal data class RegionDrag(
    val tool: RegionTool,
    val handle: BoxHandle,
    val startX: Float,
    val startY: Float,
    val origin: PxRect?,
    val x: Float,
    val y: Float,
)

internal data class EditorModel(
    val bitmapW: Int,
    val bitmapH: Int,
    val selection: PxRect? = null,
    val tool: RegionTool = RegionTool.Box,
    val marks: List<RegionMark> = emptyList(),
    val drag: RegionDrag? = null,
    val textAt: Pair<Float, Float>? = null,
) {
    val bounds: PxRect get() = PxRect(0, 0, bitmapW, bitmapH)

    fun canConfirm(minPx: Int = MIN_CONFIRM): Boolean {
        val s = selection ?: return false
        return s.width >= minPx && s.height >= minPx && s.l >= 0 && s.t >= 0 && s.r <= bitmapW && s.b <= bitmapH
    }
}

/** 确认时用的选区。不够大就不给,也不会把选区悄悄放大。 */
internal fun confirmedCrop(model: EditorModel, minPx: Int = MIN_CONFIRM): PxRect? =
    model.selection?.takeIf { model.canConfirm(minPx) }

internal data class RegionEdit(val crop: PxRect, val marks: List<RegionMark>)

/**
 * 卡片里已有的字和待发截图:取消原样留下,确认只换上新的选区。
 * [screenToken] 为 null 表示本来没有待发图;非 null 的替换值表示换成这一张。
 */
internal data class RegionInputState(val draft: String, val open: Boolean, val screenToken: String?)

internal fun regionInputAfter(previous: RegionInputState, replacement: String?): RegionInputState =
    if (replacement == null) previous else previous.copy(screenToken = replacement)

/** 框选、移动、改大小,以及可选的马赛克、箭头、文字。标不标注都能完成。 */
internal object RegionGestures {
    fun tool(model: EditorModel, tool: RegionTool): EditorModel = model.copy(tool = tool, drag = null, textAt = null)

    fun undo(model: EditorModel): EditorModel = model.copy(marks = model.marks.dropLast(1), drag = null)

    fun down(model: EditorModel, map: FrameMap, vx: Float, vy: Float, handlePx: Float): EditorModel {
        val (x, y) = map.viewToBitmap(vx, vy) ?: return model
        val selection = model.selection
        return when (model.tool) {
            RegionTool.Box -> {
                val handle = if (selection == null) BoxHandle.Create else hitBox(selection, map, vx, vy, handlePx)
                val origin = if (handle == BoxHandle.Create) null else selection
                model.copy(drag = RegionDrag(RegionTool.Box, handle, x, y, origin, x, y), textAt = null)
            }
            RegionTool.Mosaic, RegionTool.Arrow, RegionTool.Text -> {
                if (selection == null || !selection.contains(x.toInt(), y.toInt())) return model.copy(textAt = null)
                model.copy(drag = RegionDrag(model.tool, BoxHandle.Create, x, y, selection, x, y), textAt = null)
            }
        }
    }

    fun move(model: EditorModel, map: FrameMap, vx: Float, vy: Float): EditorModel {
        val drag = model.drag ?: return model
        val (x, y) = map.viewToBitmapClamped(vx, vy)
        val next = drag.copy(x = x, y = y)
        val selection = if (drag.tool == RegionTool.Box) boxFromDrag(next, model.bounds) else model.selection
        return model.copy(drag = next, selection = selection)
    }

    fun up(model: EditorModel): EditorModel {
        val drag = model.drag ?: return model
        return when (drag.tool) {
            RegionTool.Box -> {
                val rect = model.selection?.takeIf { it.width >= 2 && it.height >= 2 }
                model.copy(selection = rect, drag = null)
            }
            RegionTool.Mosaic -> {
                val rect = mosaicFromDrag(drag)?.takeIf { it.width >= MIN_MARK && it.height >= MIN_MARK }
                val clipped = rect?.intersect(drag.origin ?: return model.copy(drag = null))
                    ?.takeIf { it.width >= 2 && it.height >= 2 }
                model.copy(marks = if (clipped == null) model.marks else model.marks + RegionMark.Mosaic(clipped), drag = null)
            }
            RegionTool.Arrow -> {
                val far = hypot(drag.x - drag.startX, drag.y - drag.startY) >= MIN_MARK
                val marks = if (far) model.marks + RegionMark.Arrow(drag.startX, drag.startY, drag.x, drag.y) else model.marks
                model.copy(marks = marks, drag = null)
            }
            RegionTool.Text -> {
                val tapped = hypot(drag.x - drag.startX, drag.y - drag.startY) <= TEXT_TAP_PX
                val inside = drag.origin?.contains(drag.startX.toInt(), drag.startY.toInt()) == true
                model.copy(drag = null, textAt = if (tapped && inside) drag.startX to drag.startY else null)
            }
        }
    }

    fun dismissText(model: EditorModel): EditorModel = model.copy(textAt = null)

    /** 文字落在选区里;放不下就挪进选区,仍放不下就丢掉,不会写到选区外面。 */
    fun placeText(model: EditorModel, text: String, textPx: Int): EditorModel {
        val at = model.textAt ?: return model
        val selection = model.selection ?: return model.copy(textAt = null)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return model.copy(textAt = null)
        val raw = labelBounds(trimmed, at.first, at.second, textPx)
        val bounds = shiftInside(raw, selection) ?: return model.copy(textAt = null)
        return model.copy(marks = model.marks + RegionMark.Label(trimmed, bounds), textAt = null)
    }

    fun previewMosaic(model: EditorModel): PxRect? {
        val drag = model.drag ?: return null
        if (drag.tool != RegionTool.Mosaic) return null
        return mosaicFromDrag(drag)?.intersect(drag.origin ?: return null)
    }

    fun previewArrow(model: EditorModel): RegionMark.Arrow? {
        val drag = model.drag ?: return null
        if (drag.tool != RegionTool.Arrow) return null
        return RegionMark.Arrow(drag.startX, drag.startY, drag.x, drag.y)
    }
}

internal fun hitBox(sel: PxRect, map: FrameMap, vx: Float, vy: Float, handlePx: Float): BoxHandle {
    val v = map.viewRectOf(sel)
    val corners = listOf(
        BoxHandle.NW to (v.l to v.t),
        BoxHandle.NE to (v.r to v.t),
        BoxHandle.SW to (v.l to v.b),
        BoxHandle.SE to (v.r to v.b),
    )
    corners.firstOrNull { (_, point) ->
        hypot(vx - point.first, vy - point.second) <= handlePx
    }?.let { return it.first }
    val withinY = vy in (v.t - handlePx)..(v.b + handlePx)
    val withinX = vx in (v.l - handlePx)..(v.r + handlePx)
    if (withinY && kotlin.math.abs(vx - v.l) <= handlePx) return BoxHandle.W
    if (withinY && kotlin.math.abs(vx - v.r) <= handlePx) return BoxHandle.E
    if (withinX && kotlin.math.abs(vy - v.t) <= handlePx) return BoxHandle.N
    if (withinX && kotlin.math.abs(vy - v.b) <= handlePx) return BoxHandle.S
    if (vx in v.l..v.r && vy in v.t..v.b) return BoxHandle.Move
    return BoxHandle.Create
}

internal fun labelBounds(text: String, x: Float, y: Float, textPx: Int): PxRect {
    val size = textPx.coerceAtLeast(1)
    val width = text.fold(0) { acc, ch ->
        acc + if (ch.code >= CJK_START) size else (size * 0.55f).roundToInt().coerceAtLeast(1)
    }.coerceAtLeast(size)
    val left = x.roundToInt()
    val bottom = y.roundToInt()
    val top = bottom - size
    return PxRect(left, top, left + width, bottom.coerceAtLeast(top + 1))
}

internal fun shiftInside(rect: PxRect, box: PxRect): PxRect? {
    if (box.width < 4 || box.height < 4) return null
    val w = minOf(rect.width, box.width)
    val h = minOf(rect.height, box.height)
    if (w < 4 || h < 4) return null
    var l = rect.l
    var t = rect.t
    if (l < box.l) l = box.l
    if (t < box.t) t = box.t
    if (l + w > box.r) l = box.r - w
    if (t + h > box.b) t = box.b - h
    return PxRect(l, t, l + w, t + h)
}

private fun boxFromDrag(drag: RegionDrag, bounds: PxRect): PxRect = when (drag.handle) {
    BoxHandle.Create -> boxInside(drag.startX.roundToInt(), drag.startY.roundToInt(), drag.x.roundToInt(), drag.y.roundToInt(), bounds)
    BoxHandle.Move -> {
        val origin = drag.origin ?: return boxInside(drag.x.roundToInt(), drag.y.roundToInt(), drag.x.roundToInt() + 1, drag.y.roundToInt() + 1, bounds)
        val dx = (drag.x - drag.startX).roundToInt().coerceIn(bounds.l - origin.l, bounds.r - origin.r)
        val dy = (drag.y - drag.startY).roundToInt().coerceIn(bounds.t - origin.t, bounds.b - origin.b)
        PxRect(origin.l + dx, origin.t + dy, origin.r + dx, origin.b + dy)
    }
    else -> resize(drag.origin ?: bounds, drag.handle, drag.x, drag.y, bounds)
}

private fun resize(origin: PxRect, handle: BoxHandle, x: Float, y: Float, bounds: PxRect): PxRect {
    val px = x.roundToInt()
    val py = y.roundToInt()
    var l = origin.l
    var t = origin.t
    var r = origin.r
    var b = origin.b
    when (handle) {
        BoxHandle.E, BoxHandle.NE, BoxHandle.SE -> r = px
        BoxHandle.W, BoxHandle.NW, BoxHandle.SW -> l = px
        else -> Unit
    }
    when (handle) {
        BoxHandle.S, BoxHandle.SE, BoxHandle.SW -> b = py
        BoxHandle.N, BoxHandle.NE, BoxHandle.NW -> t = py
        else -> Unit
    }
    return boxInside(l, t, r, b, bounds)
}

private fun mosaicFromDrag(drag: RegionDrag): PxRect? {
    if (drag.tool != RegionTool.Mosaic) return null
    val bounds = drag.origin ?: return null
    return boxInside(drag.startX.roundToInt(), drag.startY.roundToInt(), drag.x.roundToInt(), drag.y.roundToInt(), bounds)
}

/** 选区短边小于它就不能完成,避免裁出空图;不会为了达到它去多裁。 */
internal const val MIN_CONFIRM = 8

private const val MIN_MARK = 8
private const val TEXT_TAP_PX = 12f
private const val CJK_START = 0x2E80
