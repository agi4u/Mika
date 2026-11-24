package com.wangzan101.mika.capsule.region

import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区域截图:选区映射、裁剪、文字脱敏、长短按和取消后的输入状态。 */
class RegionCaptureTest {
    @Test fun longPressDoesNotAlsoClickAndShortPressStillClicks() {
        val press = CameraPress(slopPx = 8f)
        press.down()
        assertTrue(press.longPress())
        assertEquals(CameraPress.Release.Ignore, press.up())
        press.down()
        assertEquals(CameraPress.Release.Click, press.up())
    }

    @Test fun slidingOffBeforeTheTimeoutIsNeitherClickNorLongPress() {
        val press = CameraPress(slopPx = 8f)
        press.down()
        assertEquals(CameraPress.Phase.Drag, press.move(20f))
        assertFalse(press.longPress())
        assertEquals(CameraPress.Release.Ignore, press.up())
    }

    @Test fun cancelDropsThePress() {
        val press = CameraPress(slopPx = 8f)
        press.down()
        press.cancel()
        assertEquals(CameraPress.Release.Ignore, press.up())
    }

    @Test fun identicalSizesAreOneToOneAndScaledDisplaysMapBack() {
        val same = FrameMap.of(1080, 2400, 1080, 2400)
        assertEquals(1f, same.scale)
        assertEquals(0f, same.originX)
        assertEquals(100f to 200f, same.viewToBitmap(100f, 200f))

        // 1440×3200 的截图铺在 1080×2400 的视图上,宽高比相同,贴满而不是缩成编辑器
        val scaled = FrameMap.of(1080, 2400, 1440, 3200)
        assertEquals(0f, scaled.originX)
        assertEquals(0f, scaled.originY)
        assertEquals(100f, scaled.viewToBitmap(75f, 150f)!!.first, 0.01f)
        assertEquals(200f, scaled.viewToBitmap(75f, 150f)!!.second, 0.01f)
        val (edgeX, edgeY) = scaled.viewToBitmapClamped(1080f, 2400f)
        assertEquals(1440f, edgeX, 0.01f)
        assertEquals(3200f, edgeY, 0.01f)
    }

    @Test fun letterboxOutsideTheImageIsNotPartOfTheBitmap() {
        val map = FrameMap.of(1000, 2200, 1000, 2000)
        assertEquals(100f, map.originY, 0.01f)
        assertNull(map.viewToBitmap(0f, 50f))
        assertEquals(0f to 0f, map.viewToBitmap(0f, 100f))
        assertEquals(0f to 0f, map.viewToBitmapClamped(0f, 50f))
    }

    @Test fun rotationChangesAspectButInsetJitterDoesNot() {
        assertTrue(aspectShift(1080, 2400, 2400, 1080))
        assertFalse(aspectShift(1080, 2400, 1080, 2410))
    }

    @Test fun dragClampsToTheBitmapAndDoesNotExpandASmallSelection() {
        val map = FrameMap.of(200, 400, 200, 400)
        var model = EditorModel(200, 400)
        model = RegionGestures.down(model, map, 10f, 20f, 16f)
        model = RegionGestures.move(model, map, 500f, 900f)
        model = RegionGestures.up(model)
        assertEquals(PxRect(10, 20, 200, 400), model.selection)
        assertEquals(model.selection, confirmedCrop(model))

        var tiny = EditorModel(200, 400)
        tiny = RegionGestures.down(tiny, map, 10f, 10f, 16f)
        tiny = RegionGestures.move(tiny, map, 14f, 16f)
        tiny = RegionGestures.up(tiny)
        assertEquals(PxRect(10, 10, 14, 16), tiny.selection)
        assertNull(confirmedCrop(tiny))

        var tap = EditorModel(200, 400)
        tap = RegionGestures.down(tap, map, 10f, 10f, 16f)
        tap = RegionGestures.up(tap)
        assertNull(tap.selection)
    }

    @Test fun selectionCanMoveAndResizeWithoutLeavingTheScreen() {
        val map = FrameMap.of(200, 400, 200, 400)
        var model = EditorModel(200, 400, selection = PxRect(10, 20, 80, 100))
        model = RegionGestures.down(model, map, 40f, 60f, 10f)
        assertEquals(BoxHandle.Move, model.drag?.handle)
        model = RegionGestures.move(model, map, 70f, 70f)
        model = RegionGestures.up(model)
        assertEquals(PxRect(40, 30, 110, 110), model.selection)

        model = RegionGestures.down(model, map, 110f, 70f, 16f)
        assertEquals(BoxHandle.E, model.drag?.handle)
        model = RegionGestures.move(model, map, 300f, 70f)
        model = RegionGestures.up(model)
        assertEquals(200, model.selection!!.r)
        assertEquals(40, model.selection!!.l)
    }

    @Test fun aSecondDragOutsideReplacesTheSelection() {
        val map = FrameMap.of(200, 400, 200, 400)
        var model = EditorModel(200, 400, selection = PxRect(10, 20, 40, 50))
        model = RegionGestures.down(model, map, 100f, 100f, 12f)
        assertEquals(BoxHandle.Create, model.drag?.handle)
        model = RegionGestures.move(model, map, 160f, 180f)
        model = RegionGestures.up(model)
        assertEquals(PxRect(100, 100, 160, 180), model.selection)
    }

    @Test fun markupIsOptionalAndUndoDropsOnlyTheLastMark() {
        val map = FrameMap.of(200, 400, 200, 400)
        var model = EditorModel(200, 400, selection = PxRect(0, 0, 100, 100), tool = RegionTool.Mosaic)
        model = RegionGestures.down(model, map, 10f, 10f, 12f)
        model = RegionGestures.move(model, map, 40f, 50f)
        model = RegionGestures.up(model)
        model = RegionGestures.tool(model, RegionTool.Arrow)
        model = RegionGestures.down(model, map, 12f, 12f, 12f)
        model = RegionGestures.move(model, map, 60f, 70f)
        model = RegionGestures.up(model)
        assertEquals(2, model.marks.size)
        assertTrue(confirmedCrop(model) == PxRect(0, 0, 100, 100))
        model = RegionGestures.undo(model)
        assertEquals(1, model.marks.size)
        assertTrue(model.marks.single() is RegionMark.Mosaic)
    }

    @Test fun chineseLabelIsShiftedInsideTheSelection() {
        var model = EditorModel(200, 400, selection = PxRect(0, 0, 100, 50), textAt = 90f to 40f)
        model = RegionGestures.placeText(model, "汉字", textPx = 20)
        val label = model.marks.single() as RegionMark.Label
        assertEquals("汉字", label.text)
        assertTrue(PxRect(0, 0, 100, 50).contains(label.bounds))
        assertTrue(label.bounds.r <= 100 && label.bounds.b <= 50)
    }

    @Test fun cancelKeepsDraftAndPendingWhileConfirmOnlyReplacesThePicture() {
        val previous = RegionInputState("你好，别删", open = true, screenToken = "old")
        assertEquals(previous, regionInputAfter(previous, null))
        val confirmed = regionInputAfter(previous, "region")
        assertEquals("你好，别删", confirmed.draft)
        assertTrue(confirmed.open)
        assertEquals("region", confirmed.screenToken)
        val empty = RegionInputState("还在", open = false, screenToken = null)
        assertEquals(empty, regionInputAfter(empty, null))
        assertEquals("region", regionInputAfter(empty, "region").screenToken)
    }

    @Test fun textOutsideTheCropOrUnderMosaicIsDroppedAndCoordinatesAreCropLocal() {
        val crop = PxRect(0, 80, 400, 400)
        val spans = listOf(
            RegionSpan("选区内", PxRect(0, 100, 200, 180)),
            RegionSpan("整屏机密", PxRect(800, 100, 980, 180)),
            RegionSpan("跨界", PxRect(390, 120, 450, 160)),
            RegionSpan("中心在里", PxRect(120, 70, 180, 110)),
            RegionSpan("密码1234", PxRect(10, 100, 80, 140), secret = true),
            RegionSpan("支付成功 38元", PxRect(0, 200, 300, 280)),
            RegionSpan("支付成功", PxRect(10, 210, 120, 250)),
            RegionSpan("38元", PxRect(140, 210, 220, 250)),
            RegionSpan("认出来的", PxRect(20, 300, 80, 340), fromOcr = true),
        )
        val keptOnly = RegionText.apply(spans, emptyList(), crop)
        assertEquals(listOf("选区内", "支付成功", "38元", "认出来的"), keptOnly.first.map { it.text })
        assertFalse(keptOnly.first.any { it.text.contains("整屏") || it.text.contains("密码") || it.text.contains("跨界") })
        val inside = keptOnly.first.first { it.text == "选区内" }
        assertEquals(normalize(100, crop.width), inside.x)
        assertEquals(normalize(60, crop.height), inside.y)
        assertNotEquals(normalize(100, 1000), inside.x)
        assertNotEquals(normalize(140, 2000), inside.y)
        assertTrue(keptOnly.second)

        val covered = RegionText.apply(spans, listOf(RegionMark.Mosaic(PxRect(0, 90, 220, 360))), crop)
        assertFalse(covered.first.any { it.text == "选区内" || it.text == "支付成功" || it.text == "38元" || it.text == "认出来的" })
        assertFalse(covered.first.any { it.text.contains("支付成功 38") })
        assertFalse(covered.second)

        val noted = RegionText.apply(
            spans,
            listOf(
                RegionMark.Mosaic(PxRect(0, 90, 220, 360)),
                RegionMark.Label("补一句", PxRect(240, 100, 320, 140)),
            ),
            crop,
        )
        assertEquals(listOf("补一句"), noted.first.map { it.text })
        assertFalse(noted.second)
    }

    @Test fun treeSpansUseScreenSpaceAndOcrSpansUseBitmapSpace() {
        val elements = listOf(
            element("左边", listOf(0, 0, 499, 50)),
            element("右边整屏", listOf(900, 0, 999, 50)),
        )
        val ocr = listOf(VisibleTextLine("图里的字", listOf(0, 0, 100, 40)))
        val spans = spansOf(elements, screenW = 1080, screenH = 2400, ocr, bitmapW = 1440, bitmapH = 3200)
        val left = spans.first { it.text == "左边" }.bounds
        val right = spans.first { it.text == "右边整屏" }.bounds
        val recognized = spans.first { it.text == "图里的字" }
        assertTrue(recognized.fromOcr)
        assertTrue(left.r < 800)
        assertTrue(right.l > 1200)
        val (lines, _) = RegionText.apply(spans, emptyList(), PxRect(0, 0, 730, 170))
        assertTrue(lines.any { it.text == "左边" })
        assertTrue(lines.any { it.text == "图里的字" })
        assertFalse(lines.any { it.text == "右边整屏" })
    }

    @Test fun mosaicDestroysCoveredPixelsAndCropDropsTheRest() {
        val width = 4
        val height = 4
        val pixels = IntArray(width * height) { 0xFF00FF00.toInt() }
        pixels[1 * width + 1] = 0xFF000000.toInt()
        pixels[1 * width + 2] = 0xFFFFFFFF.toInt()
        pixels[2 * width + 1] = 0xFF000000.toInt()
        pixels[2 * width + 2] = 0xFFFFFFFF.toInt()
        val outside = 0xFF112233.toInt()
        pixels[0] = outside
        val mosaicked = mosaicPixels(pixels, width, height, PxRect(1, 1, 3, 3), block = 2)
        assertEquals(outside, mosaicked[0])
        assertEquals(0xFF00FF00.toInt(), mosaicked[3])
        val flat = mosaicked[1 * width + 1]
        assertEquals(flat, mosaicked[1 * width + 2])
        assertEquals(flat, mosaicked[2 * width + 1])
        assertEquals(flat, mosaicked[2 * width + 2])
        assertEquals(0xFF7F7F7F.toInt(), flat)
        assertNotEquals(0xFF000000.toInt(), flat)
        assertNotEquals(0xFFFFFFFF.toInt(), flat)

        val wide = IntArray(6) { 0xFF010101.toInt() }
        wide[2] = 0xFF123456.toInt()
        val cropped = cropPixels(wide, 3, 2, PxRect(0, 0, 2, 2))
        assertEquals(4, cropped.size)
        assertFalse(cropped.contains(0xFF123456.toInt()))
    }

    @Test fun normRoundTripStaysOnTheSamePixelBand() {
        val size = 1080
        val px = 540
        val back = normToPx(com.wangzan101.mika.core.screen.uistate.normalize(px, size), size)
        assertTrue(kotlin.math.abs(back - px) <= 1)
        assertEquals(size, normToPx(999, size))
        assertEquals(0, normToPx(0, size))
    }

    private fun element(name: String, bounds: List<Int>, secret: Boolean = false) = UiElement(
        ref = name, role = "text", name = name, centerX = 0, centerY = 0, bounds = bounds,
        resourceId = null, clickable = false, editable = false, scrollable = false, secret = secret,
    )
}
