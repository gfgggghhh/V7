package com.veinfinder.app

import android.content.Context
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors

/** Built-in phone cameras (and USB cameras that the phone itself exposes through Camera2 as "external"). */
@OptIn(ExperimentalCamera2Interop::class)
class PhoneCameraSource(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val cameraId: String
) : FrameSource {

    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false

    override fun start(onFrame: (Mat) -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (stopped) return@addListener
            try {
                val p = future.get(); provider = p
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { img ->
                    try {
                        val plane = img.planes[0]
                        val w = img.width; val h = img.height
                        val buf = plane.buffer; val stride = plane.rowStride
                        val m = Mat(h, w, CvType.CV_8UC4)
                        if (stride == w * 4) {
                            val bytes = ByteArray(w * h * 4); buf.get(bytes); m.put(0, 0, bytes)
                        } else {
                            val row = ByteArray(w * 4)
                            for (y in 0 until h) { buf.position(y * stride); buf.get(row, 0, w * 4); m.put(y, 0, row) }
                        }
                        val rot = when (img.imageInfo.rotationDegrees) {
                            90 -> Core.ROTATE_90_CLOCKWISE
                            180 -> Core.ROTATE_180
                            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                            else -> -1
                        }
                        if (rot >= 0) { val r = Mat(); Core.rotate(m, r, rot); m.release(); onFrame(r) } else onFrame(m)
                    } finally { img.close() }
                }
                val selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }.build()
                p.unbindAll()
                p.bindToLifecycle(owner, selector, analysis)
            } catch (e: Exception) {
                onError(e.message ?: "Camera error")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    override fun stop() {
        stopped = true
        provider?.unbindAll(); provider = null
        executor.shutdown()
    }
}
