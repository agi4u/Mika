package com.wangzan101.mika.capsule.region

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

/** 框选示例:斜向画出一角,停大约一秒,再淡出。只播这一遍。 */
internal object HintMotion {
    const val DRAW_MS = 900L
    const val PAUSE_MS = 1000L
    const val FADE_MS = 400L
    const val TOTAL_MS = DRAW_MS + PAUSE_MS + FADE_MS
    const val START_X = 0.239f
    const val START_Y = 0.321f
    const val END_X = 0.761f
    const val END_Y = 0.551f

    fun frame(elapsedMs: Long, viewW: Int, viewH: Int): HintFrame? {
        if (elapsedMs < 0 || viewW <= 0 || viewH <= 0 || elapsedMs >= TOTAL_MS) return null
        val sx = viewW * START_X
        val sy = viewH * START_Y
        val ex = viewW * END_X
        val ey = viewH * END_Y
        val drawT = elapsedMs.coerceAtMost(DRAW_MS).toFloat() / DRAW_MS
        val eased = 1f - (1f - drawT) * (1f - drawT) * (1f - drawT)
        val x = sx + (ex - sx) * eased
        val y = sy + (ey - sy) * eased
        val opacity = if (elapsedMs <= DRAW_MS + PAUSE_MS) 1f
        else 1f - (elapsedMs - DRAW_MS - PAUSE_MS).toFloat() / FADE_MS
        return HintFrame(
            left = min(sx, x),
            top = min(sy, y),
            right = max(sx, x).coerceAtLeast(min(sx, x) + HINT_MIN),
            bottom = max(sy, y).coerceAtLeast(min(sy, y) + HINT_MIN),
            opacity = opacity.coerceIn(0f, 1f),
            fingerX = x,
            fingerY = y,
        )
    }

    private const val HINT_MIN = 8f
}

internal data class HintFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val opacity: Float,
    val fingerX: Float,
    val fingerY: Float,
)

/** 马赛克是一级打码。框选、涂抹、箭头是「标记」里的三种,框选只在已有选区上画矩形。 */
internal enum class RegionTool { Mosaic, Box, Smear, Arrow, Text }

internal enum class RegionStage { Hint, Idle, Dragging, Selected, Editing }

/**
 * 框选前和拖动中只有取消。松手后才是重画、编辑、完成。
 * 编辑换成马赛克、标记、文字、完成;标记再向上弹出框选、涂抹、箭头。
 */
internal enum class RegionChrome { None, Actions, Tools }

internal enum class BoxHandle { Create, Move, N, S, E, W, NE, NW, SE, SW }

internal sealed class RegionMark {
    data class Mosaic(val rect: PxRect) : RegionMark()
    data class Box(val rect: PxRect) : RegionMark()
    data class Smear(val points: List<Pair<Float, Float>>, val radius: Float) : RegionMark()
    data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : RegionMark()
    data class Label(val text: String, val bounds: PxRect) : RegionMark()
}

/** [tool] 为空是在拉选区;有工具才是在选区里标注。 */
internal data class RegionDrag(
    val tool: RegionTool?,
    val handle: BoxHandle,
    val startX: Float,
    val startY: Float,
    val origin: PxRect?,
    val x: Float,
    val y: Float,
    val points: List<Pair<Float, Float>> = emptyList(),
)

internal data class EditorModel(
    val bitmapW: Int,
    val bitmapH: Int,
    val selection: PxRect? = null,
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

/** 完成放进待发图,取消回到原来的草稿。两条路都不发送。 */
internal enum class RegionDelivery { Restore, Pending }

internal fun regionDelivery(edit: RegionEdit?): RegionDelivery {
    val crop = edit?.crop ?: return RegionDelivery.Restore
    if (crop.width < MIN_CONFIRM || crop.height < MIN_CONFIRM) return RegionDelivery.Restore
    return RegionDelivery.Pending
}

/**
 * 卡片里已有的字和待发截图:取消原样留下,确认只换上新的选区。
 * [screenToken] 为 null 表示本来没有待发图;非 null 的替换值表示换成这一张。
 */
internal data class RegionInputState(val draft: String, val open: Boolean, val screenToken: String?)

internal fun regionInputAfter(previous: RegionInputState, replacement: String?): RegionInputState =
    if (replacement == null) previous else previous.copy(screenToken = replacement)

/**
 * 冻结之后的交互。示例动画只在进入时播一次,手指一点就停,重画也不会再播。
 * 底部操作要等选区松手才出现。
 */
internal data class RegionFlow(
    val stage: RegionStage = RegionStage.Hint,
    val model: EditorModel,
    val tool: RegionTool? = null,
    val markMenu: Boolean = false,
    val hintConsumed: Boolean = false,
) {
    val chrome: RegionChrome
        get() = when (stage) {
            RegionStage.Selected -> RegionChrome.Actions
            RegionStage.Editing -> RegionChrome.Tools
            RegionStage.Hint, RegionStage.Idle, RegionStage.Dragging -> RegionChrome.None
        }

    fun hintFrame(elapsedMs: Long, viewW: Int, viewH: Int): HintFrame? =
        if (stage == RegionStage.Hint) HintMotion.frame(elapsedMs, viewW, viewH) else null

    fun afterHintClock(elapsedMs: Long, viewW: Int, viewH: Int): RegionFlow {
        if (stage != RegionStage.Hint) return this
        if (HintMotion.frame(elapsedMs, viewW, viewH) != null) return this
        return copy(stage = RegionStage.Idle, hintConsumed = true)
    }

    /** 系统关了动画时直接停在空白蒙层,不补播。 */
    fun skipHint(): RegionFlow =
        if (stage == RegionStage.Hint) copy(stage = RegionStage.Idle, hintConsumed = true) else this

    fun pointerDown(map: FrameMap, vx: Float, vy: Float, handlePx: Float): RegionFlow {
        val base = if (stage == RegionStage.Hint) copy(stage = RegionStage.Idle, hintConsumed = true) else this
        return when (base.stage) {
            RegionStage.Hint -> base
            RegionStage.Idle, RegionStage.Dragging -> base.beginSelection(map, vx, vy)
            RegionStage.Selected -> base.adjustOrReselect(map, vx, vy, handlePx)
            RegionStage.Editing -> base.beginMark(map, vx, vy)
        }
    }

    fun pointerMove(map: FrameMap, vx: Float, vy: Float): RegionFlow {
        val drag = model.drag ?: return this
        val (bx, by) = map.viewToBitmapClamped(vx, vy)
        val (x, y) = if (drag.tool != null && drag.origin != null) clampPoint(bx, by, drag.origin) else bx to by
        val points = if (drag.tool == RegionTool.Smear) drag.points + (x to y) else drag.points
        val next = drag.copy(x = x, y = y, points = points)
        val selection = if (drag.tool == null) boxFromDrag(next, model.bounds) else model.selection
        return copy(model = model.copy(drag = next, selection = selection))
    }

    fun pointerUp(map: FrameMap): RegionFlow {
        val drag = model.drag ?: return this
        return if (drag.tool == null) finishSelection(map, drag) else copy(model = commitMark(drag))
    }

    /** 清掉选区和标注,回到可以再拖的蒙层。不重播示例。 */
    fun redraw(): RegionFlow = copy(
        stage = RegionStage.Idle,
        tool = null,
        markMenu = false,
        hintConsumed = true,
        model = model.copy(selection = null, marks = emptyList(), drag = null, textAt = null),
    )

    fun edit(): RegionFlow =
        if (stage == RegionStage.Selected || stage == RegionStage.Editing) copy(stage = RegionStage.Editing, markMenu = false) else this

    fun toggleMarkMenu(): RegionFlow =
        if (stage != RegionStage.Editing) this else copy(markMenu = !markMenu)

    fun pick(next: RegionTool): RegionFlow =
        if (stage != RegionStage.Editing) this else copy(tool = next, markMenu = false, model = model.copy(drag = null, textAt = null))

    fun dismissText(): RegionFlow = copy(model = model.copy(textAt = null))

    fun placeText(text: String, textPx: Int): RegionFlow = copy(model = placeLabel(model, text, textPx))

    fun previewMosaic(): PxRect? = previewRect(RegionTool.Mosaic)

    fun previewBox(): PxRect? = previewRect(RegionTool.Box)

    fun previewArrow(): RegionMark.Arrow? {
        val drag = model.drag ?: return null
        if (drag.tool != RegionTool.Arrow) return null
        return RegionMark.Arrow(drag.startX, drag.startY, drag.x, drag.y)
    }

    fun previewSmear(): RegionMark.Smear? {
        val drag = model.drag ?: return null
        if (drag.tool != RegionTool.Smear || drag.points.isEmpty()) return null
        val radius = drag.origin?.let(::smearRadius) ?: return null
        return RegionMark.Smear(drag.points, radius)
    }

    private fun beginSelection(map: FrameMap, vx: Float, vy: Float): RegionFlow {
        val (x, y) = map.viewToBitmap(vx, vy) ?: return this
        val drag = RegionDrag(null, BoxHandle.Create, x, y, null, x, y)
        return copy(
            stage = RegionStage.Dragging,
            tool = null,
            markMenu = false,
            model = model.copy(drag = drag, selection = boxFromDrag(drag, model.bounds), textAt = null),
        )
    }

    private fun adjustOrReselect(map: FrameMap, vx: Float, vy: Float, handlePx: Float): RegionFlow {
        val sel = model.selection ?: return beginSelection(map, vx, vy)
        val handle = hitBox(sel, map, vx, vy, handlePx)
        if (handle == BoxHandle.Create) return beginSelection(map, vx, vy)
        val (x, y) = map.viewToBitmapClamped(vx, vy)
        return copy(
            stage = RegionStage.Dragging,
            markMenu = false,
            model = model.copy(drag = RegionDrag(null, handle, x, y, sel, x, y), textAt = null),
        )
    }

    private fun beginMark(map: FrameMap, vx: Float, vy: Float): RegionFlow {
        val active = tool ?: return copy(markMenu = false)
        val selection = model.selection ?: return copy(markMenu = false)
        val raw = map.viewToBitmap(vx, vy) ?: return copy(markMenu = false)
        if (!selection.contains(raw.first.toInt(), raw.second.toInt())) return copy(markMenu = false)
        val (x, y) = clampPoint(raw.first, raw.second, selection)
        val points = if (active == RegionTool.Smear) listOf(x to y) else emptyList()
        return copy(
            markMenu = false,
            model = model.copy(
                drag = RegionDrag(active, BoxHandle.Create, x, y, selection, x, y, points),
                textAt = null,
            ),
        )
    }

    private fun finishSelection(map: FrameMap, drag: RegionDrag): RegionFlow {
        val rect = boxFromDrag(drag, model.bounds).takeIf { it.width >= 2 && it.height >= 2 }
        val fresh = drag.handle == BoxHandle.Create
        if (rect == null || rect.width < MIN_CONFIRM || rect.height < MIN_CONFIRM || (fresh && !viewLarge(map, rect))) {
            return copy(
                stage = RegionStage.Idle,
                tool = null,
                markMenu = false,
                model = model.copy(selection = null, marks = emptyList(), drag = null, textAt = null),
            )
        }
        return copy(
            stage = RegionStage.Selected,
            tool = null,
            markMenu = false,
            model = model.copy(selection = rect, marks = if (fresh) emptyList() else model.marks, drag = null, textAt = null),
        )
    }

    private fun commitMark(drag: RegionDrag): EditorModel {
        val selection = drag.origin
        val marks = when (drag.tool) {
            RegionTool.Mosaic -> marked(drag, model.marks, selection) { RegionMark.Mosaic(it) }
            RegionTool.Box -> marked(drag, model.marks, selection) { RegionMark.Box(it) }
            RegionTool.Smear -> {
                val box = selection ?: return model.copy(drag = null)
                if (pathLength(drag.points) < MIN_MARK) model.marks
                else model.marks + RegionMark.Smear(drag.points, smearRadius(box))
            }
            RegionTool.Arrow -> {
                val far = hypot(drag.x - drag.startX, drag.y - drag.startY) >= MIN_MARK
                if (far) model.marks + RegionMark.Arrow(drag.startX, drag.startY, drag.x, drag.y) else model.marks
            }
            RegionTool.Text, null -> model.marks
        }
        val textAt = if (drag.tool == RegionTool.Text &&
            hypot(drag.x - drag.startX, drag.y - drag.startY) <= TEXT_TAP_PX &&
            selection?.contains(drag.startX.toInt(), drag.startY.toInt()) == true
        ) drag.startX to drag.startY else null
        return model.copy(marks = marks, drag = null, textAt = textAt)
    }

    private fun marked(drag: RegionDrag, marks: List<RegionMark>, selection: PxRect?, build: (PxRect) -> RegionMark): List<RegionMark> {
        val rect = rectInside(drag, selection)?.takeIf { it.width >= MIN_MARK && it.height >= MIN_MARK }
        return if (rect == null) marks else marks + build(rect)
    }

    private fun previewRect(tool: RegionTool): PxRect? {
        val drag = model.drag ?: return null
        if (drag.tool != tool) return null
        return rectInside(drag, drag.origin)
    }

    companion object {
        fun start(bitmapW: Int, bitmapH: Int) = RegionFlow(model = EditorModel(bitmapW, bitmapH))
    }
}

internal fun smearRadius(selection: PxRect): Float {
    val side = min(selection.width, selection.height).toFloat()
    return (side / 18f).coerceIn(10f, 36f)
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

private fun placeLabel(model: EditorModel, text: String, textPx: Int): EditorModel {
    val at = model.textAt ?: return model
    val selection = model.selection ?: return model.copy(textAt = null)
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return model.copy(textAt = null)
    val raw = labelBounds(trimmed, at.first, at.second, textPx)
    val bounds = shiftInside(raw, selection) ?: return model.copy(textAt = null)
    return model.copy(marks = model.marks + RegionMark.Label(trimmed, bounds), textAt = null)
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

private fun rectInside(drag: RegionDrag, bounds: PxRect?): PxRect? {
    val box = bounds ?: return null
    return boxInside(drag.startX.roundToInt(), drag.startY.roundToInt(), drag.x.roundToInt(), drag.y.roundToInt(), box)
}

private fun viewLarge(map: FrameMap, rect: PxRect): Boolean {
    val view = map.viewRectOf(rect)
    return view.r - view.l >= MIN_SELECTION_VIEW && view.b - view.t >= MIN_SELECTION_VIEW
}

private fun clampPoint(x: Float, y: Float, box: PxRect): Pair<Float, Float> {
    val right = (box.r - 1).coerceAtLeast(box.l).toFloat()
    val bottom = (box.b - 1).coerceAtLeast(box.t).toFloat()
    return x.coerceIn(box.l.toFloat(), right) to y.coerceIn(box.t.toFloat(), bottom)
}

private fun pathLength(points: List<Pair<Float, Float>>): Float {
    var total = 0f
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        total += hypot(b.first - a.first, b.second - a.second)
    }
    return total
}

/** 选区短边小于它就不能完成,避免裁出空图;不会为了达到它去多裁。 */
internal const val MIN_CONFIRM = 8

/** 新拉出来的选区,在屏幕上短边至少这么多才算框完。 */
internal const val MIN_SELECTION_VIEW = 36f

private const val MIN_MARK = 8
private const val TEXT_TAP_PX = 12f
private const val CJK_START = 0x2E80
