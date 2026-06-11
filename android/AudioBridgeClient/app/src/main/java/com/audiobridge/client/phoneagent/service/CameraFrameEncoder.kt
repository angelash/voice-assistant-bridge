package com.audiobridge.client.phoneagent.service

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.File
import java.io.IOException

object CameraFrameEncoder {
    fun writeJpeg(image: ImageProxy, outputFile: File, quality: Int = 80) {
        outputFile.parentFile?.mkdirs()
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        outputFile.outputStream().use { out ->
            val ok = yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            if (!ok) {
                throw IOException("CameraX 帧编码 JPEG 失败")
            }
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val out = ByteArray(ySize + uvSize * 2)

        copyPlane(
            plane = image.planes[0],
            width = width,
            height = height,
            output = out,
            outputOffset = 0,
            outputStride = 1,
        )
        copyPlane(
            plane = image.planes[2],
            width = width / 2,
            height = height / 2,
            output = out,
            outputOffset = ySize,
            outputStride = 2,
        )
        copyPlane(
            plane = image.planes[1],
            width = width / 2,
            height = height / 2,
            output = out,
            outputOffset = ySize + 1,
            outputStride = 2,
        )
        return out
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputStride: Int,
    ) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outputIndex = outputOffset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                output[outputIndex] = buffer.get(rowStart + col * pixelStride)
                outputIndex += outputStride
            }
        }
    }
}
