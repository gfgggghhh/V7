package com.veinfinder.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.abs

/** USB (UVC) webcams, through github.com/shiyinghan/UVCAndroid (com.herohan:UVCAndroid). */
class UvcCameraSource(
    @Suppress("unused") private val ctx: Context,
    private val device: UsbDevice
) : FrameSource {

    private var helper: ICameraHelper? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    @Volatile private var width = 640
    @Volatile private var height = 480
    @Volatile private var stopped = false

    override fun start(onFrame: (Mat) -> Unit, onError: (String) -> Unit) {
        stopped = false
        val fail = onError
        val h = CameraHelper()
        helper = h

        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(d: UsbDevice?) {}

            // USB permission granted -> open with the supported size closest to 640x480
            override fun onDeviceOpen(d: UsbDevice?, isFirstOpen: Boolean) {
                if (stopped) return
                val best = try {
                    h.supportedSizeList?.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }
                } catch (e: Exception) { null }
                if (best != null) h.openCamera(best) else h.openCamera()
            }

            override fun onCameraOpen(d: UsbDevice?) {
                if (stopped) return
                try {
                    h.previewSize?.let { width = it.width; height = it.height }
                    val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(width, height) }
                    val sf = Surface(tex)
                    texture = tex; surface = sf

                    h.setFrameCallback(IFrameCallback { buf ->
                        if (stopped) return@IFrameCallback
                        val w = width; val hh = height
                        val need = w * hh * 4
                        buf.rewind()
                        if (buf.remaining() < need) return@IFrameCallback
                        val bytes = ByteArray(need)
                        buf.get(bytes)
                        val m = Mat(hh, w, CvType.CV_8UC4)
                        m.put(0, 0, bytes)
                        onFrame(m)
                    }, UVCCamera.PIXEL_FORMAT_RGBX)

                    h.addSurface(sf, false)
                    h.startPreview()
                } catch (e: Exception) {
                    fail(e.message ?: "USB camera error")
                }
            }

            override fun onCameraClose(d: UsbDevice?) {}
            override fun onDeviceClose(d: UsbDevice?) {}

            override fun onDetach(d: UsbDevice?) {
                if (!stopped && d?.deviceName == device.deviceName) fail("USB camera disconnected")
            }

            override fun onCancel(d: UsbDevice?) {
                if (!stopped) fail("USB permission denied")
            }

            override fun onError(d: UsbDevice?, e: CameraException?) {
                if (!stopped) fail(e?.message ?: "USB camera error")
            }
        })

        h.selectDevice(device)      // asks for USB permission, then onDeviceOpen() is called
    }

    override fun stop() {
        stopped = true
        val h = helper; helper = null
        val sf = surface; surface = null
        val tex = texture; texture = null
        try { h?.stopPreview() } catch (_: Exception) {}
        try { if (sf != null) h?.removeSurface(sf) } catch (_: Exception) {}
        try { h?.closeCamera() } catch (_: Exception) {}
        try { h?.release() } catch (_: Exception) {}
        // the library works on its own thread: free the surface a bit later
        Handler(Looper.getMainLooper()).postDelayed({
            try { sf?.release() } catch (_: Exception) {}
            try { tex?.release() } catch (_: Exception) {}
        }, 500)
    }
}
