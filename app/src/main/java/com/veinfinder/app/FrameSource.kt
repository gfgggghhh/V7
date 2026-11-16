package com.veinfinder.app

import org.opencv.core.Mat

interface FrameSource {
    fun start(onFrame: (Mat) -> Unit, onError: (String) -> Unit)
    fun stop()
}