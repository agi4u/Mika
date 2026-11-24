package com.wangzan101.mika.capsule

import android.graphics.Bitmap
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayConceal
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG
import com.wangzan101.mika.capsule.region.MIN_CONFIRM
import com.wangzan101.mika.capsule.region.RegionBitmap
import com.wangzan101.mika.capsule.region.RegionCaptureOverlay
import com.wangzan101.mika.capsule.region.RegionEdit
import com.wangzan101.mika.capsule.region.RegionInputState
import com.wangzan101.mika.capsule.region.RegionText
import com.wangzan101.mika.capsule.region.regionInputAfter
import com.wangzan101.mika.capsule.region.spansOf
import com.wangzan101.mika.chat.ui.screenThumbnail
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreen
import com.wangzan101.mika.core.screen.user.UserScreenText
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.media.BitmapImageOps
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 长按卡片里的相机:藏起自己的浮层后马上截整屏,铺成 1:1 冻结帧让用户框选。取消不改已有输入和待发图。 */
internal fun CapsuleController.startRegionCapture() {
    Log.i(TAG, "gesture=camera-long-press")
    if (capturing) return
    capturing = true
    val snapshot = inputUi
    scope.launch {
        var conceal: OverlayConceal? = null
        var bitmap: Bitmap? = null
        var reopen = false
        var ocr: kotlinx.coroutines.Deferred<List<VisibleTextLine>?>? = null
        try {
            val fresh = takeFreshShot(keepHidden = true)
            if (fresh != null) {
                conceal = fresh.conceal
                when (val shot = fresh.shot) {
                    is ScreenAccessibility.MainShot.Failed -> Log.w(TAG, "region screenshot failed: ${shot.code}")
                    is ScreenAccessibility.MainShot.Secure -> attachBlocked(snapshot, fresh)
                    is ScreenAccessibility.MainShot.Ok -> {
                        val bmp = shot.bitmap
                        bitmap = bmp
                        if (BitmapImageOps.isMostlyBlack(bmp)) {
                            bmp.recycle()
                            bitmap = null
                            attachBlocked(snapshot, fresh)
                        } else {
                            val screenW = fresh.area.width.takeIf { it > 0 } ?: bmp.width
                            val screenH = fresh.area.height.takeIf { it > 0 } ?: bmp.height
                            Log.i(TAG, "region-bitmap ${bmp.width}x${bmp.height} area=${screenW}x${screenH} inset=${fresh.area.insetLeft},${fresh.area.insetTop},${fresh.area.insetRight},${fresh.area.insetBottom}")
                            val snap = fresh.read?.let { MainScreenCapture.snapshot(it, screenW, screenH) }
                            if (snap != null && UserScreenText.needsOcr(snap)) {
                                val copy = runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                                if (copy != null) ocr = async(Dispatchers.Default) { readOcr(copy) }
                            }
                            val edit = editRegion(bmp, fresh.area.insetTop, fresh.area.insetBottom)
                            val crop = edit?.crop
                            Log.i(
                                TAG,
                                if (edit == null || crop == null) "region-result=cancel"
                                else "region-result=confirm crop=${crop.l},${crop.t},${crop.r},${crop.b} ${crop.width}x${crop.height} marks=${edit.marks.joinToString(",") { it::class.simpleName ?: "?" }}",
                            )
                            if (edit == null || crop == null || crop.width < MIN_CONFIRM || crop.height < MIN_CONFIRM) {
                                restoreRegionInput(snapshot, null)
                                reopen = snapshot.open && expanded
                            } else if (fresh.sources.any { !(context.applicationContext as MikaApp).appAccess.current().canShare(it) }) {
                                Toast.makeText(context, tr(R.string.appaccess_share_expired), Toast.LENGTH_SHORT).show()
                                restoreRegionInput(snapshot, null)
                                reopen = snapshot.open && expanded
                            } else {
                                val recognized = ocr?.await()
                                ocr = null
                                val spans = spansOf(
                                    fresh.read?.entries?.map { it.element }.orEmpty(),
                                    screenW, screenH, recognized, bmp.width, bmp.height,
                                )
                                val captured = withContext(Dispatchers.Default) { encodeRegion(bmp, edit, spans, fresh) }
                                val thumb = captured.jpeg?.let { bytes -> withContext(Dispatchers.Default) { screenThumbnail(bytes) } }
                                restoreRegionInput(snapshot, PendingScreen(captured, thumb))
                                reopen = snapshot.open && expanded
                                flash++
                                wake()
                            }
                        }
                    }
                }
            }
        } finally {
            ocr?.cancel()
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            conceal?.restore()
            capturing = false
            syncWindowVisibility()
            syncPanelWindow()
        }
        if (reopen) openInput()
        refresh()
    }
}

/** 取消留下原来的待发图;确认只把待发图换成选区,输入框里的字不动。 */
internal fun CapsuleController.restoreRegionInput(snapshot: CardInputUi, pending: PendingScreen?) {
    val previous = if (snapshot.screen != null) PREVIOUS_SCREEN else null
    val replacement = if (pending != null) REGION_SCREEN else null
    val after = regionInputAfter(RegionInputState(snapshot.draft, snapshot.open, previous), replacement)
    val screen = if (after.screenToken == REGION_SCREEN) pending else snapshot.screen
    // open 先写成 false,好让 openInput 真正把焦点和输入法要回来
    inputUi = snapshot.copy(draft = after.draft, open = false, screen = screen)
}

private fun CapsuleController.attachBlocked(snapshot: CardInputUi, fresh: FreshShot) {
    val pkg = fresh.read?.topPackage
    val shot = CapturedScreen(null, UserScreen("", System.currentTimeMillis(), pkg, pkg?.let { appLabel(it) }, blocked = true), fresh.sources)
    inputUi = snapshot.copy(open = false, screen = PendingScreen(shot, null))
    flash++
    wake()
}

private suspend fun readOcr(copy: Bitmap): List<VisibleTextLine>? = try {
    // PhoneOcr 会回收这份副本。原图留给冻结帧,确认前不做第二次识别。
    PhoneOcr.read(copy)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "region text recognition failed", e)
    null
}

private fun CapsuleController.encodeRegion(
    bitmap: Bitmap,
    edit: RegionEdit,
    spans: List<com.wangzan101.mika.capsule.region.RegionSpan>,
    fresh: FreshShot,
): CapturedScreen {
    val cropped = RegionBitmap.render(bitmap, edit.crop, edit.marks)
    try {
        val jpeg = RegionBitmap.jpeg(cropped)
        val (lines, fromOcr) = RegionText.apply(spans, edit.marks, edit.crop)
        val pkg = fresh.read?.topPackage
        return CapturedScreen(
            jpeg,
            UserScreen("", System.currentTimeMillis(), pkg, pkg?.let { appLabel(it) }, lines = lines, fromOcr = fromOcr),
            fresh.sources,
        )
    } finally {
        if (!cropped.isRecycled) cropped.recycle()
    }
}

private suspend fun CapsuleController.editRegion(bitmap: Bitmap, insetTop: Int, insetBottom: Int): RegionEdit? =
    suspendCancellableCoroutine { cont ->
        val host = windowOn?.overlays()
        if (host == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        var window: OverlayWindow? = null
        fun finish(result: RegionEdit?, rotated: Boolean) {
            if (!cont.isActive) return
            val w = window
            window = null
            w?.close()
            if (rotated) Toast.makeText(context, tr(R.string.region_capture_rotated), Toast.LENGTH_SHORT).show()
            cont.resume(result)
        }
        val opened = host.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), secure = true) {
            MikaTheme(reduceMotion = animationsOff(context)) {
                RegionCaptureOverlay(
                    bitmap = bitmap,
                    insetTopPx = insetTop,
                    insetBottomPx = insetBottom,
                    onCancel = { finish(null, false) },
                    onConfirm = { finish(it, false) },
                    onRotated = { finish(null, true) },
                    onIme = { show -> window?.setFocusable(true, showIme = show) },
                )
            }
        }
        if (opened == null) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }
        window = opened
        opened.setFullScreen(true)
        opened.fillContent()
        opened.setFocusable(true, showIme = false)
        opened.onBack { finish(null, false) }
        cont.invokeOnCancellation { opened.close() }
    }

private const val PREVIOUS_SCREEN = "previous"
private const val REGION_SCREEN = "region"
