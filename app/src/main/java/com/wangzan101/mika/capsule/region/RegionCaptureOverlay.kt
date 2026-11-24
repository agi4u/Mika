package com.wangzan101.mika.capsule.region

import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

internal object RegionCopy {
    val action: String get() = tr(R.string.region_capture_action)
    val cancel: String get() = tr(R.string.region_capture_cancel)
    val done: String get() = tr(R.string.region_capture_done)
    val doneSkip: String get() = tr(R.string.region_capture_done_skip)
    val mosaic: String get() = tr(R.string.region_capture_mosaic)
    val arrow: String get() = tr(R.string.region_capture_arrow)
    val text: String get() = tr(R.string.region_capture_text)
    val textHint: String get() = tr(R.string.region_capture_text_hint)
    val textPlace: String get() = tr(R.string.region_capture_text_place)
    val hint: String get() = tr(R.string.region_capture_hint)
    val adjust: String get() = tr(R.string.region_capture_adjust)
    val frozen: String get() = tr(R.string.region_capture_frozen)
    val undo: String get() = tr(R.string.region_capture_undo)
}

/**
 * 1:1 铺满屏幕的冻结帧。先拖出选区,松手后拖边角或选区本身调整。
 * 马赛克、箭头、文字都是可选的,完成不要求先标注。
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
    var model by remember(bitmap.width, bitmap.height) { mutableStateOf(EditorModel(bitmap.width, bitmap.height)) }
    var closed by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<IntArray?>(null) }
    val density = LocalDensity.current
    val top = with(density) { insetTopPx.toDp() }
    val bottom = with(density) { insetBottomPx.toDp() }
    val textPx = with(density) { 18.sp.toPx() }.toInt().coerceAtLeast(1)
    fun finish(block: () -> Unit) {
        if (closed) return
        closed = true
        block()
    }
    LaunchedEffect(model.textAt != null) { onIme(model.textAt != null) }
    // 窗口被系统拆掉时也要结束这次框选,否则 capturing 会一直为真,胶囊再点不开。
    DisposableEffect(Unit) {
        onDispose {
            Log.i("MikaRegion", "overlay-disposed")
            finish(onCancel)
        }
    }
    Box(
        Modifier.fillMaxSize().testTag("region-overlay").onSizeChanged { size ->
            val prev = base
            if (size.width > 0 && size.height > 0) {
                val map = FrameMap.of(size.width, size.height, bitmap.width, bitmap.height)
                Log.i(
                    "MikaRegion",
                    "frame view=${size.width}x${size.height} bmp=${bitmap.width}x${bitmap.height} scale=${map.scale} origin=${map.originX},${map.originY} content=${map.contentW}x${map.contentH} inset=$insetTopPx,$insetBottomPx",
                )
            }
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
            model = model,
            onModel = { if (!closed) model = it },
        )
        val hint = if (model.selection == null) RegionCopy.hint else RegionCopy.adjust
        Text(
            hint,
            Modifier.align(Alignment.TopCenter).padding(top = top + 12.dp, start = 16.dp, end = 16.dp)
                .clip(MikaShapes.Pill).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
            style = MikaType.Caption, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (model.textAt != null) {
            TextEntry(
                Modifier.align(Alignment.TopCenter).padding(top = top + 52.dp, start = 16.dp, end = 16.dp),
                onPlace = { model = RegionGestures.placeText(model, it, textPx) },
                onDismiss = { model = RegionGestures.dismissText(model) },
            )
        }
        EditorBar(
            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom + 12.dp),
            model = model,
            onTool = { model = RegionGestures.tool(model, it) },
            onUndo = { model = RegionGestures.undo(model) },
            onCancel = { finish(onCancel) },
            onDone = {
                val crop = confirmedCrop(model) ?: return@EditorBar
                finish { onConfirm(RegionEdit(crop, model.marks)) }
            },
        )
    }
}

private class EditorHolder(var model: EditorModel, var dragging: Boolean = false)

@Composable
private fun FrozenFrame(image: ImageBitmap, bitmap: Bitmap, model: EditorModel, onModel: (EditorModel) -> Unit) {
    // 手势进行中不能等下一帧重组才读到新选区,否则移动和松手都还拿着按下时的旧模型。
    val holder = remember(bitmap.width, bitmap.height) { EditorHolder(model) }
    val report = rememberUpdatedState(onModel)
    SideEffect { if (!holder.dragging) holder.model = model }
    Canvas(
        Modifier.fillMaxSize().pointerInput(bitmap.width, bitmap.height) {
            val handle = 28.dp.toPx()
            fun apply(next: EditorModel) {
                holder.model = next
                report.value(next)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                fun map() = FrameMap.of(size.width, size.height, bitmap.width, bitmap.height)
                holder.dragging = true
                apply(RegionGestures.down(holder.model, map(), down.position.x, down.position.y, handle))
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            apply(RegionGestures.up(holder.model))
                            break
                        }
                        apply(RegionGestures.move(holder.model, map(), change.position.x, change.position.y))
                        change.consume()
                    }
                } finally {
                    holder.dragging = false
                }
            }
        },
    ) {
        if (bitmap.isRecycled) return@Canvas
        val map = FrameMap.of(size.width.toInt(), size.height.toInt(), bitmap.width, bitmap.height)
        drawRect(Color.Black)
        drawMapped(image, map)
        val selection = model.selection
        if (selection != null) {
            val view = map.viewRectOf(selection)
            drawRect(Color.Black.copy(alpha = 0.45f))
            clipRect(view.l, view.t, view.r, view.b) { drawMapped(image, map) }
        }
        val mosaic = RegionGestures.previewMosaic(model)
        if (mosaic != null) drawMosaic(map, mosaic)
        model.marks.filterIsInstance<RegionMark.Mosaic>().forEach { drawMosaic(map, it.rect) }
        val arrow = RegionGestures.previewArrow(model)
        if (arrow != null) drawArrow(map, arrow)
        model.marks.filterIsInstance<RegionMark.Arrow>().forEach { drawArrow(map, it) }
        model.marks.filterIsInstance<RegionMark.Label>().forEach { drawLabel(map, it) }
        if (selection != null) {
            val view = map.viewRectOf(selection)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            drawRect(Color.White, topLeft = Offset(view.l, view.t), size = Size(view.r - view.l, view.b - view.t), style = stroke)
            val handle = 11.dp.toPx()
            val handleStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
            listOf(view.l to view.t, view.r to view.t, view.l to view.b, view.r to view.b).forEach { (x, y) ->
                drawRect(Color.White, topLeft = Offset(x - handle, y - handle), size = Size(handle * 2, handle * 2))
                drawRect(Color.Black, topLeft = Offset(x - handle, y - handle), size = Size(handle * 2, handle * 2), style = handleStroke)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapped(image: ImageBitmap, map: FrameMap) {
    val w = map.contentW.toInt()
    val h = map.contentH.toInt()
    if (w <= 0 || h <= 0) return
    drawImage(image, dstOffset = androidx.compose.ui.unit.IntOffset(map.originX.toInt(), map.originY.toInt()), dstSize = androidx.compose.ui.unit.IntSize(w, h))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMosaic(map: FrameMap, rect: PxRect) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(map: FrameMap, label: RegionMark.Label) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(map: FrameMap, arrow: RegionMark.Arrow) {
    val (x1, y1) = map.bitmapToView(arrow.x1, arrow.y1)
    val (x2, y2) = map.bitmapToView(arrow.x2, arrow.y2)
    drawLine(Color.Black, Offset(x1, y1), Offset(x2, y2), strokeWidth = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
private fun EditorBar(
    modifier: Modifier,
    model: EditorModel,
    onTool: (RegionTool) -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val c = MikaTheme.colors
    val ready = model.selection != null
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolKey(RegionCopy.mosaic, "region-mosaic", ready && model.tool == RegionTool.Mosaic, ready) { onTool(RegionTool.Mosaic) }
            ToolKey(RegionCopy.arrow, "region-arrow", ready && model.tool == RegionTool.Arrow, ready) { onTool(RegionTool.Arrow) }
            ToolKey(RegionCopy.text, "region-text", ready && model.tool == RegionTool.Text, ready) { onTool(RegionTool.Text) }
            if (model.marks.isNotEmpty()) ToolKey(RegionCopy.undo, "region-undo", on = false, enabled = true, onClick = onUndo)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.testTag("region-cancel").weight(1f).heightIn(min = 40.dp).clip(MikaShapes.Pill).background(c.fill)
                    .clickable(role = Role.Button, onClick = onCancel).semantics { contentDescription = RegionCopy.cancel },
                contentAlignment = Alignment.Center,
            ) { Text(RegionCopy.cancel, style = MikaType.Label, color = c.ink) }
            val doneLabel = if (model.marks.isEmpty()) RegionCopy.doneSkip else RegionCopy.done
            Box(
                Modifier.testTag("region-done").weight(1f).heightIn(min = 40.dp).clip(MikaShapes.Pill)
                    .background(if (model.canConfirm()) c.accent else c.ink3.copy(alpha = 0.35f))
                    .clickable(enabled = model.canConfirm(), role = Role.Button, onClick = onDone)
                    .semantics { contentDescription = doneLabel },
                contentAlignment = Alignment.Center,
            ) { Text(RegionCopy.done, style = MikaType.Label, color = c.onAccent) }
        }
    }
}

@Composable
private fun ToolKey(label: String, tag: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag(tag).heightIn(min = 32.dp).clip(MikaShapes.Pill)
            .background(if (on) c.accentSoft else c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; selected = on }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MikaType.Caption, color = if (on) c.accent else c.ink, maxLines = 1) }
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
