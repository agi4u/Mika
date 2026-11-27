package com.wangzan101.mika.capsule.region

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

internal object RegionCopy {
    val action: String get() = tr(R.string.region_capture_action)
    val cancel: String get() = tr(R.string.region_capture_cancel)
    val done: String get() = tr(R.string.region_capture_done)
    val redraw: String get() = tr(R.string.region_capture_redraw)
    val edit: String get() = tr(R.string.region_capture_edit)
    val mosaic: String get() = tr(R.string.region_capture_mosaic)
    val mark: String get() = tr(R.string.region_capture_mark)
    val box: String get() = tr(R.string.region_capture_box)
    val smear: String get() = tr(R.string.region_capture_smear)
    val arrow: String get() = tr(R.string.region_capture_arrow)
    val text: String get() = tr(R.string.region_capture_text)
    val textHint: String get() = tr(R.string.region_capture_text_hint)
    val textPlace: String get() = tr(R.string.region_capture_text_place)
    val frozen: String get() = tr(R.string.region_capture_frozen)
}

/**
 * 1:1 铺满屏幕的冻结帧。先播一遍框选示例,手指按下就停。
 * 松手以后才出现重画、编辑、完成。编辑里再换马赛克、标记、文字。
 */
@Composable
internal fun RegionCaptureOverlay(
    bitmap: Bitmap,
    insetTopPx: Int,
    insetBottomPx: Int,
    onCancel: () -> Unit,
    onConfirm: (RegionEdit) -> Unit,
    onRotated: () -> Unit,
    onIme: (Boolean) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val initial = remember(bitmap.width, bitmap.height) { RegionFlow.start(bitmap.width, bitmap.height) }
    var flow by remember(bitmap.width, bitmap.height) { mutableStateOf(initial) }
    val holder = remember(bitmap.width, bitmap.height) { FlowHolder(initial) }
    var closed by remember { mutableStateOf(false) }
    var elapsed by remember(bitmap.width, bitmap.height) { mutableLongStateOf(0L) }
    val sizes = remember(bitmap.width, bitmap.height) { SizeHolder() }
    var base by remember { mutableStateOf<IntArray?>(null) }
    val density = LocalDensity.current
    val top = with(density) { insetTopPx.toDp() }
    val bottom = with(density) { insetBottomPx.toDp() }
    val textPx = with(density) { 18.sp.toPx() }.toInt().coerceAtLeast(1)
    val reduceMotion = MikaTheme.reduceMotion
    fun publish(next: RegionFlow) {
        holder.flow = next
        flow = next
    }
    fun finish(block: () -> Unit) {
        if (closed) return
        closed = true
        block()
    }
    LaunchedEffect(flow.model.textAt != null) { onIme(flow.model.textAt != null) }
    LaunchedEffect(bitmap.width, bitmap.height, reduceMotion) {
        if (reduceMotion) {
            publish(holder.flow.skipHint())
            return@LaunchedEffect
        }
        var started = 0L
        while (holder.flow.stage == RegionStage.Hint) {
            val now = withFrameNanos { it }
            val width = sizes.width
            val height = sizes.height
            if (width <= 0 || height <= 0) continue
            if (started == 0L) started = now
            val ms = (now - started) / 1_000_000
            elapsed = ms
            val current = holder.flow
            if (current.stage != RegionStage.Hint) break
            val next = current.afterHintClock(ms, width, height)
            if (holder.flow.stage != RegionStage.Hint) break
            if (next.stage != RegionStage.Hint) publish(next)
        }
    }
    // 窗口被系统拆掉时也要结束这次框选,否则 capturing 会一直为真,胶囊再点不开。
    DisposableEffect(Unit) {
        onDispose {
            Log.i("MikaRegion", "overlay-disposed")
            finish(onCancel)
        }
    }
    Box(
        Modifier.fillMaxSize().testTag("region-overlay").onSizeChanged { size ->
            sizes.width = size.width
            sizes.height = size.height
            if (size.width > 0 && size.height > 0) {
                val map = FrameMap.of(size.width, size.height, bitmap.width, bitmap.height)
                Log.i(
                    "MikaRegion",
                    "frame view=${size.width}x${size.height} bmp=${bitmap.width}x${bitmap.height} scale=${map.scale} origin=${map.originX},${map.originY} content=${map.contentW}x${map.contentH} inset=$insetTopPx,$insetBottomPx",
                )
            }
            val prev = base
            if (prev == null) {
                if (size.width > 0 && size.height > 0) base = intArrayOf(size.width, size.height)
            } else if (aspectShift(prev[0], prev[1], size.width, size.height)) {
                Log.i("MikaRegion", "rotated ${prev[0]}x${prev[1]} -> ${size.width}x${size.height}, cancel")
                finish(onRotated)
            }
        }.semantics { contentDescription = RegionCopy.frozen; role = Role.Image },
    ) {
        FrozenFrame(
            image = image,
            bitmap = bitmap,
            flow = flow,
            elapsed = elapsed,
            insetTopPx = insetTopPx,
            onFlow = { if (!closed) publish(it) },
        )
        CancelKey(
            Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = top + 8.dp),
            onClick = { finish(onCancel) },
        )
        if (flow.model.textAt != null) {
            TextEntry(
                Modifier.align(Alignment.TopCenter).padding(top = top + 52.dp, start = 16.dp, end = 16.dp),
                onPlace = { publish(flow.placeText(it, textPx)) },
                onDismiss = { publish(flow.dismissText()) },
            )
        }
        val showBar = flow.chrome != RegionChrome.None
        val enter = if (reduceMotion) fadeIn(snap()) else slideInVertically(tween(280)) { it } + fadeIn(tween(180))
        val exit = if (reduceMotion) fadeOut(snap()) else slideOutVertically(tween(180)) { it }
        AnimatedVisibility(
            visible = showBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = enter,
            exit = exit,
        ) {
            RegionBar(
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = bottom + 18.dp),
                flow = flow,
                onRedraw = { publish(flow.redraw()) },
                onEdit = { publish(flow.edit()) },
                onToggleMark = { publish(flow.toggleMarkMenu()) },
                onTool = { publish(flow.pick(it)) },
                onDone = {
                    val crop = confirmedCrop(flow.model) ?: return@RegionBar
                    if (regionDelivery(RegionEdit(crop, flow.model.marks)) != RegionDelivery.Pending) return@RegionBar
                    finish { onConfirm(RegionEdit(crop, flow.model.marks)) }
                },
            )
        }
    }
}

private class FlowHolder(var flow: RegionFlow)

private class SizeHolder(var width: Int = 0, var height: Int = 0)

@Composable
private fun FrozenFrame(
    image: ImageBitmap,
    bitmap: Bitmap,
    flow: RegionFlow,
    elapsed: Long,
    insetTopPx: Int,
    onFlow: (RegionFlow) -> Unit,
) {
    val holder = remember(bitmap.width, bitmap.height) { FlowHolder(flow) }
    holder.flow = flow
    val report = rememberUpdatedState(onFlow)
    Canvas(
        Modifier.fillMaxSize().pointerInput(bitmap.width, bitmap.height) {
            val handle = 28.dp.toPx()
            fun map() = FrameMap.of(size.width, size.height, bitmap.width, bitmap.height)
            awaitEachGesture {
                val down = awaitFirstDown()
                fun apply(next: RegionFlow) {
                    holder.flow = next
                    report.value(next)
                }
                apply(holder.flow.pointerDown(map(), down.position.x, down.position.y, handle))
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        apply(holder.flow.pointerUp(map()))
                        break
                    }
                    apply(holder.flow.pointerMove(map(), change.position.x, change.position.y))
                    change.consume()
                }
            }
        },
    ) {
        if (bitmap.isRecycled) return@Canvas
        val map = FrameMap.of(size.width.toInt(), size.height.toInt(), bitmap.width, bitmap.height)
        val hint = holder.flow.hintFrame(elapsed, size.width.toInt(), size.height.toInt())
        drawRect(Color.Black)
        drawMapped(image, map)
        val dimTop = insetTopPx.toFloat().coerceIn(0f, size.height)
        drawRect(
            Color(0xFF141414).copy(alpha = 0.48f),
            topLeft = Offset(0f, dimTop),
            size = Size(size.width, (size.height - dimTop).coerceAtLeast(0f)),
        )
        val selection = holder.flow.model.selection
        if (selection != null && hint == null) {
            val view = map.viewRectOf(selection)
            clipRect(view.l, view.t, view.r, view.b) { drawMapped(image, map) }
        }
        holder.flow.model.marks.forEach { drawMark(map, it) }
        holder.flow.previewMosaic()?.let { drawMosaic(map, it) }
        holder.flow.previewBox()?.let { drawBoxMark(map, it) }
        holder.flow.previewSmear()?.let { drawSmear(map, it) }
        holder.flow.previewArrow()?.let { drawArrow(map, it) }
        if (selection != null && hint == null) drawSelection(map.viewRectOf(selection), handles = true, alpha = 1f)
        if (hint != null) {
            drawSelection(
                ViewRect(hint.left, hint.top, hint.right, hint.bottom),
                handles = false,
                alpha = hint.opacity,
            )
            drawCircle(Color.White.copy(alpha = 0.32f * hint.opacity), 16.dp.toPx(), Offset(hint.fingerX, hint.fingerY))
            drawCircle(Color.White, 11.dp.toPx(), Offset(hint.fingerX, hint.fingerY), alpha = hint.opacity)
        }
    }
}

private fun DrawScope.drawMark(map: FrameMap, mark: RegionMark) {
    when (mark) {
        is RegionMark.Mosaic -> drawMosaic(map, mark.rect)
        is RegionMark.Box -> drawBoxMark(map, mark.rect)
        is RegionMark.Smear -> drawSmear(map, mark)
        is RegionMark.Arrow -> drawArrow(map, mark)
        is RegionMark.Label -> drawLabel(map, mark)
    }
}

private fun DrawScope.drawMapped(image: ImageBitmap, map: FrameMap) {
    val w = map.contentW.toInt()
    val h = map.contentH.toInt()
    if (w <= 0 || h <= 0) return
    drawImage(image, dstOffset = androidx.compose.ui.unit.IntOffset(map.originX.toInt(), map.originY.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w, h))
}

private fun DrawScope.drawSelection(view: ViewRect, handles: Boolean, alpha: Float) {
    if (alpha <= 0f) return
    val color = Color.White.copy(alpha = alpha)
    drawRect(
        color,
        topLeft = Offset(view.l, view.t),
        size = Size(view.r - view.l, view.b - view.t),
        style = Stroke(width = 2.dp.toPx()),
    )
    val arm = 16.dp.toPx()
    val thick = 4.dp.toPx()
    fun leg(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, Offset(x1, y1), Offset(x2, y2), thick, StrokeCap.Square)
    }
    leg(view.l, view.t, view.l + arm, view.t)
    leg(view.l, view.t, view.l, view.t + arm)
    leg(view.r, view.t, view.r - arm, view.t)
    leg(view.r, view.t, view.r, view.t + arm)
    leg(view.l, view.b, view.l + arm, view.b)
    leg(view.l, view.b, view.l, view.b - arm)
    leg(view.r, view.b, view.r - arm, view.b)
    leg(view.r, view.b, view.r, view.b - arm)
    if (!handles) return
    val bar = 22.dp.toPx()
    val weight = 4.dp.toPx()
    val cx = (view.l + view.r) / 2f
    val cy = (view.t + view.b) / 2f
    if (view.r - view.l > bar * 2) {
        drawRect(color, Offset(cx - bar / 2f, view.t - weight / 2f), Size(bar, weight))
        drawRect(color, Offset(cx - bar / 2f, view.b - weight / 2f), Size(bar, weight))
    }
    if (view.b - view.t > bar * 2) {
        drawRect(color, Offset(view.l - weight / 2f, cy - bar / 2f), Size(weight, bar))
        drawRect(color, Offset(view.r - weight / 2f, cy - bar / 2f), Size(weight, bar))
    }
}

private fun DrawScope.drawMosaic(map: FrameMap, rect: PxRect) {
    val view = map.viewRectOf(rect)
    val block = (mosaicBlock(rect.width, rect.height) * map.scale).coerceAtLeast(1f)
    var y = view.t
    var row = 0
    while (y < view.b) {
        var x = view.l
        var col = 0
        while (x < view.r) {
            val bw = minOf(block, view.r - x)
            val bh = minOf(block, view.b - y)
            val shade = if ((row + col) % 2 == 0) Color(0xFFBDBDBD) else Color(0xFF8A8A8A)
            drawRect(shade, topLeft = Offset(x, y), size = Size(bw, bh))
            x += block
            col++
        }
        y += block
        row++
    }
}

private fun DrawScope.drawBoxMark(map: FrameMap, rect: PxRect) {
    val view = map.viewRectOf(rect)
    val topLeft = Offset(view.l, view.t)
    val size = Size(view.r - view.l, view.b - view.t)
    drawRect(Color.Black, topLeft, size, style = Stroke(width = 5.dp.toPx()))
    drawRect(Color.White, topLeft, size, style = Stroke(width = 2.5.dp.toPx()))
}

private fun DrawScope.drawSmear(map: FrameMap, smear: RegionMark.Smear) {
    if (smear.points.isEmpty()) return
    val path = Path()
    smear.points.forEachIndexed { index, (x, y) ->
        val (vx, vy) = map.bitmapToView(x, y)
        if (index == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
    }
    val width = (smear.radius * 2f * map.scale).coerceAtLeast(6.dp.toPx())
    val style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(path, Color(SMEAR_INK), style = style)
}

private fun DrawScope.drawLabel(map: FrameMap, label: RegionMark.Label) {
    val view = map.viewRectOf(label.bounds)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (view.b - view.t).coerceAtLeast(1f) * 0.85f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.LEFT
    }
    val x = view.l
    val y = view.b - paint.textSize * 0.12f
    val canvas = drawContext.canvas.nativeCanvas
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = (paint.textSize / 6f).coerceAtLeast(2f)
    paint.color = android.graphics.Color.BLACK
    canvas.drawText(label.text, x, y, paint)
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    canvas.drawText(label.text, x, y, paint)
}

private fun DrawScope.drawArrow(map: FrameMap, arrow: RegionMark.Arrow) {
    val (x1, y1) = map.bitmapToView(arrow.x1, arrow.y1)
    val (x2, y2) = map.bitmapToView(arrow.x2, arrow.y2)
    drawLine(Color.Black, Offset(x1, y1), Offset(x2, y2), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
}

@Composable
private fun CancelKey(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(34.dp).testTag("region-cancel").clip(CircleShape).background(Color.White.copy(alpha = 0.86f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = RegionCopy.cancel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Close, null, Modifier.size(16.dp), tint = Color(0xFF222222))
    }
}

@Composable
private fun RegionBar(
    modifier: Modifier,
    flow: RegionFlow,
    onRedraw: () -> Unit,
    onEdit: () -> Unit,
    onToggleMark: () -> Unit,
    onTool: (RegionTool) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (flow.chrome == RegionChrome.Actions) {
            RoundTool(RegionCopy.redraw, "region-redraw", MikaIcons.Refresh, on = false, primary = false, onClick = onRedraw)
            RoundTool(RegionCopy.edit, "region-edit", MikaIcons.Edit, on = false, primary = false, onClick = onEdit)
        } else {
            RoundTool(RegionCopy.mosaic, "region-mosaic", MikaIcons.Mosaic, on = flow.tool == RegionTool.Mosaic, primary = false) {
                onTool(RegionTool.Mosaic)
            }
            MarkTool(flow, onToggleMark, onTool)
            RoundTool(RegionCopy.text, "region-text", MikaIcons.Letter, on = flow.tool == RegionTool.Text, primary = false) {
                onTool(RegionTool.Text)
            }
        }
        RoundTool(RegionCopy.done, "region-done", MikaIcons.Check, on = false, primary = true, onClick = onDone)
    }
}

@Composable
private fun MarkTool(flow: RegionFlow, onToggle: () -> Unit, onTool: (RegionTool) -> Unit) {
    val active = flow.markMenu || flow.tool == RegionTool.Box || flow.tool == RegionTool.Smear || flow.tool == RegionTool.Arrow
    Box(Modifier.width(72.dp), contentAlignment = Alignment.BottomCenter) {
        if (flow.markMenu) {
            Column(Modifier.align(Alignment.BottomCenter).offset(y = (-78).dp).width(196.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xF01C1C1C))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PopItem(RegionCopy.box, "region-mark-box", MikaIcons.Frame) { onTool(RegionTool.Box) }
                    PopItem(RegionCopy.smear, "region-mark-smear", MikaIcons.Smear) { onTool(RegionTool.Smear) }
                    PopItem(RegionCopy.arrow, "region-mark-arrow", MikaIcons.Arrow) { onTool(RegionTool.Arrow) }
                }
                Box(
                    Modifier.size(10.dp).offset(y = (-6).dp).graphicsLayer { rotationZ = 45f }.background(Color(0xF01C1C1C)),
                )
            }
        }
        RoundTool(RegionCopy.mark, "region-mark", MikaIcons.Mark, on = active, primary = false, onClick = onToggle)
    }
}

@Composable
private fun PopItem(label: String, tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp).testTag(tag).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
        }
        Text(label, style = MikaType.Caption, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun RoundTool(
    label: String,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val filled = primary || on
    Column(
        Modifier.width(64.dp).heightIn(min = 48.dp).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (filled) Color.White else Color.White.copy(alpha = 0.16f))
                .border(1.dp, if (filled) Color.White else Color.White.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (filled) Color(0xFF111111) else Color.White)
        }
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun TextEntry(modifier: Modifier, onPlace: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val focus = remember { FocusRequester() }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            draft, { draft = it },
            Modifier.testTag("region-text-field").weight(1f).focusRequester(focus).semantics { contentDescription = RegionCopy.textHint },
            textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) Text(RegionCopy.textHint, style = MikaType.Body, color = c.ink3)
                    inner()
                }
            },
        )
        Text(
            RegionCopy.cancel,
            Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(4.dp),
            style = MikaType.Caption, color = c.ink2,
        )
        Text(
            RegionCopy.textPlace,
            Modifier.testTag("region-text-place").clip(MikaShapes.Pill).background(c.accent)
                .clickable(enabled = draft.isNotBlank(), role = Role.Button) { onPlace(draft) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style = MikaType.Caption, color = c.onAccent,
        )
    }
}
