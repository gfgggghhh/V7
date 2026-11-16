package com.veinfinder.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class ViewMode { GRAY, GREEN, NEGATIVE }

/**
 * Vein enhancement.
 *  - illumination is removed with a multi-scale retinex (sigma1 / sigma2),
 *  - noise is smoothed with sigma3,
 *  - k (0..1)  = how much of the enhanced image is mixed with the original gray image,
 *  - b (0..10) = local contrast (CLAHE clip limit).
 */
class VeinProcessor {
    @Volatile var k: Double = 0.9
    @Volatile var b: Double = 10.0

    companion object {
        const val SIGMA1 = 58.4
        const val SIGMA2 = 29.6
        const val SIGMA3 = 2.0
    }

    /** [input] is RGBA / RGB / gray (8-bit). Returns a NEW RGBA Mat ready for Utils.matToBitmap; caller releases it. */
    fun process(input: Mat, mode: ViewMode): Mat {
        val kk = k.coerceIn(0.0, 1.0)
        val bb = b.coerceIn(0.0, 10.0)
        val temps = ArrayList<Mat>()
        fun <T : Mat> T.track(): T { temps.add(this); return this }

        try {
            val gray = Mat().track()
            when (input.channels()) {
                4 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGB2GRAY)
                else -> input.copyTo(gray)
            }

            // float image, +1 so that log() never sees 0
            val f = Mat().track()
            gray.convertTo(f, CvType.CV_32F, 1.0, 1.0)
            val logF = Mat().track()
            Core.log(f, logF)

            val bg1 = Mat().track()
            val bg2 = Mat().track()
            Imgproc.GaussianBlur(f, bg1, Size(), SIGMA1)
            Imgproc.GaussianBlur(f, bg2, Size(), SIGMA2)
            Core.log(bg1, bg1)
            Core.log(bg2, bg2)

            val retinex = Mat().track()
            Core.addWeighted(bg1, 0.5, bg2, 0.5, 0.0, retinex)   // mean of the two backgrounds
            Core.subtract(logF, retinex, retinex)                // log(I) - log(background)
            Imgproc.GaussianBlur(retinex, retinex, Size(), SIGMA3)

            // stretch mean +/- 3 std to 0..255
            val mean = MatOfDouble().also { temps.add(it) }
            val std = MatOfDouble().also { temps.add(it) }
            Core.meanStdDev(retinex, mean, std)
            val sd = std.get(0, 0)[0]
            val mu = mean.get(0, 0)[0]
            val alpha = if (sd > 1e-6) 255.0 / (6.0 * sd) else 1.0
            val beta = 127.5 - mu * alpha
            val enhanced = Mat().track()
            retinex.convertTo(enhanced, CvType.CV_8U, alpha, beta)

            val mixed = Mat().track()
            Core.addWeighted(enhanced, kk, gray, 1.0 - kk, 0.0, mixed)

            val clahe = Imgproc.createCLAHE(maxOf(1.0, bb), Size(8.0, 8.0))
            val result = Mat().track()
            clahe.apply(mixed, result)
            clahe.collectGarbage()

            val out = Mat()
            when (mode) {
                ViewMode.GRAY -> Imgproc.cvtColor(result, out, Imgproc.COLOR_GRAY2RGBA)
                ViewMode.NEGATIVE -> {
                    val inv = Mat().track()
                    Core.bitwise_not(result, inv)
                    Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2RGBA)
                }
                ViewMode.GREEN -> {
                    val zero = Mat.zeros(result.size(), CvType.CV_8UC1).track()
                    val alphaCh = Mat(result.size(), CvType.CV_8UC1, Scalar(255.0)).track()
                    Core.merge(listOf(zero, result, zero, alphaCh), out)   // R=0, G=result, B=0, A=255
                }
            }
            return out
        } finally {
            temps.forEach { it.release() }
        }
    }
}
