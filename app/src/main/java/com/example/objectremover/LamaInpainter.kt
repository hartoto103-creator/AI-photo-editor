package com.example.objectremover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LamaInpainter private constructor(
    private val context: Context
) {

    companion object {

        private const val MODEL_INPUT_SIZE = 512

        private const val IMAGE_INPUT_NAME = "image"
        private const val MASK_INPUT_NAME = "mask"
        private const val OUTPUT_NAME = "output"

        private const val MODEL_FILE_NAME =
            "inpainting_lama_2025jan.onnx"

        private const val ROI_PADDING_FACTOR = 1.2f
        private const val MIN_ROI_PADDING = 48
        private const val MIN_ROI_SIZE = 256

        @Volatile
        private var INSTANCE: LamaInpainter? = null

        fun getInstance(context: Context): LamaInpainter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LamaInpainter(
                    context.applicationContext
                ).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val ortEnvironment: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    private val session: OrtSession by lazy {

        val modelBytes =
            context.assets
                .open(MODEL_FILE_NAME)
                .use { it.readBytes() }

        val options =
            OrtSession.SessionOptions()

        ortEnvironment.createSession(
            modelBytes,
            options
        )
    }

    /**
     * Main entry point used by MainActivity.
     */
    suspend fun inpaint(
        image: Bitmap,
        mask: Bitmap
    ): Bitmap = withContext(Dispatchers.Default) {

        require(image.width == mask.width) {
            "Image and mask width must be identical."
        }

        require(image.height == mask.height) {
            "Image and mask height must be identical."
        }

        val original =
            image.copy(
                Bitmap.Config.ARGB_8888,
                true
            )

        val roi =
            computeRoi(
                mask = mask,
                imageWidth = image.width,
                imageHeight = image.height
            )

        if (roi != null) {

            inpaintRoi(
                original = original,
                mask = mask,
                roi = roi
            )

        } else {

            inpaintFullImagePreserveOriginal(
                original = original,
                mask = mask
            )
        }
    }

    /**
     * Finds a square ROI around the selected object.
     */
    private fun computeRoi(
        mask: Bitmap,
        imageWidth: Int,
        imageHeight: Int
    ): Rect? {

        var minX = imageWidth
        var minY = imageHeight
        var maxX = -1
        var maxY = -1

        val pixels =
            IntArray(imageWidth * imageHeight)

        mask.getPixels(
            pixels,
            0,
            imageWidth,
            0,
            0,
            imageWidth,
            imageHeight
        )

        for (y in 0 until imageHeight) {

            val rowStart =
                y * imageWidth

            for (x in 0 until imageWidth) {

                val alpha =
                    (pixels[rowStart + x] ushr 24) and 0xFF

                if (alpha > 0) {

                    if (x < minX) {
                        minX = x
                    }

                    if (x > maxX) {
                        maxX = x
                    }

                    if (y < minY) {
                        minY = y
                    }

                    if (y > maxY) {
                        maxY = y
                    }
                }
            }
        }

        if (
            maxX < minX ||
            maxY < minY
        ) {
            return null
        }

        val objectWidth =
            maxX - minX + 1

        val objectHeight =
            maxY - minY + 1

        val objectSize =
            max(
                objectWidth,
                objectHeight
            )

        val minimumPaddedSize =
            objectSize +
                    MIN_ROI_PADDING * 2

        var roiSize =
            max(
                MIN_ROI_SIZE,
                max(
                    minimumPaddedSize,
                    (
                            objectSize *
                                    (1f + ROI_PADDING_FACTOR)
                            ).roundToInt()
                )
            )

        val maxAllowedSize =
            min(
                imageWidth,
                imageHeight
            )

        roiSize =
            min(
                roiSize,
                maxAllowedSize
            )

        if (roiSize < objectSize) {
            return null
        }

        val objectCenterX =
            (minX + maxX) / 2

        val objectCenterY =
            (minY + maxY) / 2

        var left =
            objectCenterX -
                    roiSize / 2

        var top =
            objectCenterY -
                    roiSize / 2

        left =
            left.coerceIn(
                0,
                imageWidth - roiSize
            )

        top =
            top.coerceIn(
                0,
                imageHeight - roiSize
            )

        val right =
            left + roiSize

        val bottom =
            top + roiSize

        if (
            minX < left ||
            minY < top ||
            maxX >= right ||
            maxY >= bottom
        ) {
            return null
        }

        return Rect(
            left,
            top,
            right,
            bottom
        )
    }

    /**
     * Runs LaMa on the selected ROI.
     */
    private fun inpaintRoi(
        original: Bitmap,
        mask: Bitmap,
        roi: Rect
    ): Bitmap {

        val cropWidth =
            roi.width()

        val cropHeight =
            roi.height()

        val imageCrop =
            Bitmap.createBitmap(
                original,
                roi.left,
                roi.top,
                cropWidth,
                cropHeight
            )

        val maskCrop =
            Bitmap.createBitmap(
                mask,
                roi.left,
                roi.top,
                cropWidth,
                cropHeight
            )

        val modelImage =
            Bitmap.createScaledBitmap(
                imageCrop,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

        val modelMask =
            Bitmap.createScaledBitmap(
                maskCrop,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

        val output512 =
            runInference(
                modelImage,
                modelMask
            )

        val outputCrop =
            if (
                cropWidth == MODEL_INPUT_SIZE &&
                cropHeight == MODEL_INPUT_SIZE
            ) {

                output512

            } else {

                Bitmap.createScaledBitmap(
                    output512,
                    cropWidth,
                    cropHeight,
                    true
                )
            }

        val result =
            compositeMaskedPixels(
                original = original,
                generated = outputCrop,
                mask = maskCrop,
                destinationLeft = roi.left,
                destinationTop = roi.top
            )

        if (modelImage !== imageCrop) {
            modelImage.recycle()
        }

        if (modelMask !== maskCrop) {
            modelMask.recycle()
        }

        if (output512 !== outputCrop) {
            output512.recycle()
        }

        imageCrop.recycle()
        maskCrop.recycle()

        if (!outputCrop.isRecycled) {
            outputCrop.recycle()
        }

        return result
    }

    /**
     * Fallback when a safe ROI cannot be created.
     */
    private fun inpaintFullImagePreserveOriginal(
        original: Bitmap,
        mask: Bitmap
    ): Bitmap {

        val modelImage =
            if (
                original.width == MODEL_INPUT_SIZE &&
                original.height == MODEL_INPUT_SIZE
            ) {

                original

            } else {

                Bitmap.createScaledBitmap(
                    original,
                    MODEL_INPUT_SIZE,
                    MODEL_INPUT_SIZE,
                    true
                )
            }

        val modelMask =
            if (
                mask.width == MODEL_INPUT_SIZE &&
                mask.height == MODEL_INPUT_SIZE
            ) {

                mask

            } else {

                Bitmap.createScaledBitmap(
                    mask,
                    MODEL_INPUT_SIZE,
                    MODEL_INPUT_SIZE,
                    true
                )
            }

        val output512 =
            runInference(
                modelImage,
                modelMask
            )

        val generated =
            if (
                original.width == MODEL_INPUT_SIZE &&
                original.height == MODEL_INPUT_SIZE
            ) {

                output512

            } else {

                Bitmap.createScaledBitmap(
                    output512,
                    original.width,
                    original.height,
                    true
                )
            }

        val result =
            compositeMaskedPixels(
                original = original,
                generated = generated,
                mask = mask,
                destinationLeft = 0,
                destinationTop = 0
            )

        if (modelImage !== original) {
            modelImage.recycle()
        }

        if (modelMask !== mask) {
            modelMask.recycle()
        }

        if (generated !== output512) {
            generated.recycle()
        }

        if (output512 !== generated) {
            output512.recycle()
        }

        return result
    }

    /**
     * Composites only the selected area.
     */
    private fun compositeMaskedPixels(
        original: Bitmap,
        generated: Bitmap,
        mask: Bitmap,
        destinationLeft: Int,
        destinationTop: Int
    ): Bitmap {

        val result =
            original.copy(
                Bitmap.Config.ARGB_8888,
                true
            )

        val generatedWidth =
            generated.width

        val generatedHeight =
            generated.height

        val workingMask =
            if (
                mask.width != generatedWidth ||
                mask.height != generatedHeight
            ) {

                Bitmap.createScaledBitmap(
                    mask,
                    generatedWidth,
                    generatedHeight,
                    true
                )

            } else {

                mask
            }

        for (y in 0 until generatedHeight) {

            val targetY =
                destinationTop + y

            if (
                targetY < 0 ||
                targetY >= result.height
            ) {
                continue
            }

            val sourceStartX =
                if (destinationLeft < 0) {
                    -destinationLeft
                } else {
                    0
                }

            val targetStartX =
                maxOf(
                    destinationLeft,
                    0
                )

            val availableWidth =
                minOf(
                    generatedWidth - sourceStartX,
                    result.width - targetStartX
                )

            if (availableWidth <= 0) {
                continue
            }

            val generatedPixels =
                IntArray(availableWidth)

            val maskPixels =
                IntArray(availableWidth)

            val originalPixels =
                IntArray(availableWidth)

            generated.getPixels(
                generatedPixels,
                0,
                availableWidth,
                sourceStartX,
                y,
                availableWidth,
                1
            )

            workingMask.getPixels(
                maskPixels,
                0,
                availableWidth,
                sourceStartX,
                y,
                availableWidth,
                1
            )

            result.getPixels(
                originalPixels,
                0,
                availableWidth,
                targetStartX,
                targetY,
                availableWidth,
                1
            )

            for (x in 0 until availableWidth) {

                val maskAlpha =
                    (maskPixels[x] ushr 24) and 0xFF

                if (maskAlpha == 0) {
                    continue
                }

                val originalPixel =
                    originalPixels[x]

                val generatedPixel =
                    generatedPixels[x]

                val blendAlpha = when {
                    maskAlpha >= 220 -> 255
                    maskAlpha <= 20 -> 0
                    else -> maskAlpha
                }

                if (blendAlpha == 0) {
                    continue
                }

                if (blendAlpha >= 255) {
                    originalPixels[x] = generatedPixel
                    continue
                }

                val inverseAlpha = 255 - blendAlpha

                val originalRed =
                    (originalPixel shr 16) and 0xFF

                val originalGreen =
                    (originalPixel shr 8) and 0xFF

                val originalBlue =
                    originalPixel and 0xFF

                val generatedRed =
                    (generatedPixel shr 16) and 0xFF

                val generatedGreen =
                    (generatedPixel shr 8) and 0xFF

                val generatedBlue =
                    generatedPixel and 0xFF

                val red =
                    (
                            originalRed * inverseAlpha +
                                    generatedRed * maskAlpha
                            ) / 255

                val green =
                    (
                            originalGreen * inverseAlpha +
                                    generatedGreen * maskAlpha
                            ) / 255

                val blue =
                    (
                            originalBlue * inverseAlpha +
                                    generatedBlue * maskAlpha
                            ) / 255

                originalPixels[x] =
                    (255 shl 24) or
                            (
                                    red.coerceIn(
                                        0,
                                        255
                                    ) shl 16
                                    ) or
                            (
                                    green.coerceIn(
                                        0,
                                        255
                                    ) shl 8
                                    ) or
                            blue.coerceIn(
                                0,
                                255
                            )
            }

            result.setPixels(
                originalPixels,
                0,
                availableWidth,
                targetStartX,
                targetY,
                availableWidth,
                1
            )
        }

        if (workingMask !== mask) {
            workingMask.recycle()
        }

        return result
    }

    /**
     * Runs ONNX inference.
     */
    private fun runInference(
        image: Bitmap,
        mask: Bitmap
    ): Bitmap {

        val imageTensor =
            preprocessImage(image)

        val maskTensor =
            preprocessMask(mask)

        val imageOnnxTensor =
            OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(imageTensor),
                longArrayOf(
                    1,
                    3,
                    MODEL_INPUT_SIZE.toLong(),
                    MODEL_INPUT_SIZE.toLong()
                )
            )

        val maskOnnxTensor =
            OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(maskTensor),
                longArrayOf(
                    1,
                    1,
                    MODEL_INPUT_SIZE.toLong(),
                    MODEL_INPUT_SIZE.toLong()
                )
            )

        try {

            val inputs =
                mapOf(
                    IMAGE_INPUT_NAME to imageOnnxTensor,
                    MASK_INPUT_NAME to maskOnnxTensor
                )

            session.run(inputs).use { result ->

                val outputTensor =
                    result
                        .get(OUTPUT_NAME)
                        .get() as OnnxTensor

                return postprocessOutput(
                    outputTensor
                )
            }

        } finally {

            imageOnnxTensor.close()
            maskOnnxTensor.close()
        }
    }

    /**
     * Converts RGB bitmap data into NCHW float32 data.
     *
     * Output range:
     * [0, 1]
     */
    private fun preprocessImage(
        bitmap: Bitmap
    ): FloatArray {

        val size =
            MODEL_INPUT_SIZE *
                    MODEL_INPUT_SIZE

        val pixels =
            IntArray(size)

        bitmap.getPixels(
            pixels,
            0,
            MODEL_INPUT_SIZE,
            0,
            0,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE
        )

        val output =
            FloatArray(size * 3)

        val redOffset = 0
        val greenOffset = size
        val blueOffset = size * 2

        var index = 0

        for (pixel in pixels) {

            output[redOffset + index] =
                ((pixel shr 16) and 0xFF) / 255f

            output[greenOffset + index] =
                ((pixel shr 8) and 0xFF) / 255f

            output[blueOffset + index] =
                (pixel and 0xFF) / 255f

            index++
        }

        return output
    }

    /**
     * Converts mask alpha into NCHW float32 data.
     *
     * The mask is slightly dilated before being sent to LaMa.
     *
     * 1 = remove
     * 0 = keep
     */
    private fun preprocessMask(
        bitmap: Bitmap
    ): FloatArray {

        val size =
            MODEL_INPUT_SIZE *
                    MODEL_INPUT_SIZE

        val pixels =
            IntArray(size)

        bitmap.getPixels(
            pixels,
            0,
            MODEL_INPUT_SIZE,
            0,
            0,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE
        )

        val output =
            FloatArray(size)

        val radius = 6

        for (y in 0 until MODEL_INPUT_SIZE) {

            for (x in 0 until MODEL_INPUT_SIZE) {

                var maxAlpha = 0

                for (dy in -radius..radius) {

                    val sampleY =
                        y + dy

                    if (
                        sampleY < 0 ||
                        sampleY >= MODEL_INPUT_SIZE
                    ) {
                        continue
                    }

                    for (dx in -radius..radius) {

                        val sampleX =
                            x + dx

                        if (
                            sampleX < 0 ||
                            sampleX >= MODEL_INPUT_SIZE
                        ) {
                            continue
                        }

                        val sampleIndex =
                            sampleY *
                                    MODEL_INPUT_SIZE +
                                    sampleX

                        val alpha =
                            (
                                    pixels[sampleIndex]
                                            ushr 24
                                    ) and 0xFF

                        if (alpha > maxAlpha) {
                            maxAlpha = alpha
                        }
                    }
                }

                val index =
                    y *
                            MODEL_INPUT_SIZE +
                            x

                output[index] =
                    if (maxAlpha > 0) 1f else 0f

            }
        }

        return output
    }

    /**
     * Converts the ONNX output into an ARGB bitmap.
     *
     * The output range is detected automatically.
     */
    private fun postprocessOutput(
        outputTensor: OnnxTensor
    ): Bitmap {

        val buffer =
            outputTensor.floatBuffer.duplicate()

        buffer.rewind()

        val expectedValues =
            MODEL_INPUT_SIZE *
                    MODEL_INPUT_SIZE *
                    3

        if (buffer.remaining() < expectedValues) {
            throw IllegalStateException(
                "Unexpected LaMa output size: ${buffer.remaining()}"
            )
        }

        val values =
            FloatArray(expectedValues)

        buffer.get(
            values,
            0,
            expectedValues
        )

        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY

        for (value in values) {

            if (value < minValue) {
                minValue = value
            }

            if (value > maxValue) {
                maxValue = value
            }
        }

        Log.d(
            "LamaDelete",
            "LaMa output range: min=$minValue max=$maxValue"
        )

        val pixels =
            IntArray(
                MODEL_INPUT_SIZE *
                        MODEL_INPUT_SIZE
            )

        val planeSize =
            MODEL_INPUT_SIZE *
                    MODEL_INPUT_SIZE

        for (i in pixels.indices) {

            val red =
                convertModelValueToByte(
                    values[i],
                    minValue,
                    maxValue
                )

            val green =
                convertModelValueToByte(
                    values[planeSize + i],
                    minValue,
                    maxValue
                )

            val blue =
                convertModelValueToByte(
                    values[planeSize * 2 + i],
                    minValue,
                    maxValue
                )

            pixels[i] =
                (255 shl 24) or
                        (red shl 16) or
                        (green shl 8) or
                        blue
        }

        return Bitmap.createBitmap(
            pixels,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
    }

    /**
     * Converts a model output value into an 8-bit color value.
     */
    private fun convertModelValueToByte(
        value: Float,
        minValue: Float,
        maxValue: Float
    ): Int {

        val normalized =
            when {

                maxValue > 1.5f -> {
                    value / 255f
                }

                minValue < -0.1f -> {
                    (value + 1f) / 2f
                }

                else -> {
                    value
                }
            }

        return (
                normalized
                    .coerceIn(0f, 1f) *
                        255f
                ).roundToInt()
    }

    fun close() {
        session.close()
    }
}