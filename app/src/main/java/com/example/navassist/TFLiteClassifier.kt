package com.example.navassist

import android.content.Context
import android.graphics.*
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteClassifier(
    private val context: Context,
    private val modelName: String,
    private val labelName: String
) {

    private lateinit var interpreter: Interpreter
    private val labels = mutableListOf<String>()
    private val inputSize = 224

    // 🔥 Cooldown (10 seconds to avoid loop)
    private var lastAlertTime = 0L
    private val cooldownMillis = 10000L   // 10 sec

    init {
        loadModel()
        loadLabels()
    }

    // ---------------------------------------------------------
    // LOAD MODEL
    // ---------------------------------------------------------
    private fun loadModel() {
        val fd = context.assets.openFd(modelName)
        val input = FileInputStream(fd.fileDescriptor)

        val buffer: MappedByteBuffer =
            input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )

        interpreter = Interpreter(buffer, Interpreter.Options())
    }

    private fun loadLabels() {
        context.assets.open(labelName)
            .bufferedReader()
            .readLines()
            .forEach { labels.add(it) }
    }

    // ---------------------------------------------------------
    // CLASSIFY FRAME
    // ---------------------------------------------------------
    fun classify(image: ImageProxy): Pair<String, Float> {

        val bmp = imageProxyToBitmap(image)
        val resized = Bitmap.createScaledBitmap(bmp, inputSize, inputSize, true)

        // Model input
        val input = Array(1) {
            Array(inputSize) {
                Array(inputSize) {
                    FloatArray(3)
                }
            }
        }

        // Model output
        val output = Array(1) { FloatArray(labels.size) }

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = resized.getPixel(x, y)
                input[0][y][x][0] = ((px shr 16) and 0xFF) / 255f   // R
                input[0][y][x][1] = ((px shr 8) and 0xFF) / 255f    // G
                input[0][y][x][2] = (px and 0xFF) / 255f            // B
            }
        }

        interpreter.run(input, output)

        val index = output[0].indices.maxBy { output[0][it] }
        val label = labels[index]
        val confidence = output[0][index]

        // ---------------------------------------------------------
        //  🔥 Apply 10-second cooldown
        // ---------------------------------------------------------
        val now = System.currentTimeMillis()
        val canAlert = (now - lastAlertTime) > cooldownMillis

        if (canAlert) {
            lastAlertTime = now
            return Pair(label, confidence)
        }

        // Skip speaking during cooldown
        return Pair("none", 0f)
    }

    // ---------------------------------------------------------
    // Convert ImageProxy → Bitmap
    // ---------------------------------------------------------
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)

        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    fun close() {
        interpreter.close()
    }
}
