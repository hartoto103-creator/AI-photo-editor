package com.example.objectremover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class InteractiveSegmenterHelper(
    private val context: Context
) {

    private companion object {
        const val TAG = "Detection"
        const val MODEL_ASSET = "interactive_segmentation.task"

        const val MASK_COLOR = Color.RED

        const val MASK_MARGIN_FRACTION = 0.02f
        const val MIN_MASK_MARGIN_PX = 2
        const val MAX_MASK_MARGIN_PX = 12

        const val CONFIDENCE_THRESHOLD = 0.5f
        const val MIN_THRESHOLD = 0.4f
        const val SEED_RELATIVE_FACTOR = 0.9f

        const val CORE_CONFIDENCE = 0.9f
        const val COLOR_TOLERANCE_SIGMA = 3f
        const val PEEL_MAX_ITERATIONS = 32
        const val MIN_CORE_PIXELS = 200
        const val MIN_RETAINED_FRACTION = 0.35f
        val CHANNEL_DEVIATION_FLOOR = floatArrayOf(14f, 7f, 7f)

        const val MODEL_INPUT_MAX = 1024
        const val PROCESS_SIZE = 1024

        const val FOCUS_PADDING = 0.3f
        const val FOCUS_MAX_SPAN_FRACTION = 0.6f
        const val MIN_CROP_SIZE_PX = 480
        const val CROP_GROWTH_FACTOR = 1.6f
        const val MAX_CROP_ATTEMPTS = 3

        const val TAP_CLUSTER_OFFSET = 0.015f
        const val SEED_SEARCH_RADIUS = 24
    }

    private class RawSegmentation(
        val component: BooleanArray,
        val confidence: FloatArray,
        val width: Int,
        val height: Int,
        val touchesLeft: Boolean,
        val touchesTop: Boolean,
        val touchesRight: Boolean,
        val touchesBottom: Boolean,
        val selectedPercent: Float
    ) {
        val isEmpty: Boolean get() = selectedPercent <= 0f
    }

    private val segmenter: InteractiveSegmenter

    private var sourceBitmap: Bitmap? = null

    private var accumulatedMask: Bitmap? = null

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val options = InteractiveSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .build()

        segmenter = InteractiveSegmenter.createFromOptions(context, options)
    }

    fun setInputImage(bitmap: Bitmap) {
        if (sourceBitmap !== bitmap) clearSelection()
        sourceBitmap = bitmap
    }

    fun clearSelection() {
        accumulatedMask?.recycle()
        accumulatedMask = null
    }

    fun createMaskAt(x: Float, y: Float): Bitmap {
        val bitmap = checkNotNull(sourceBitmap) {
            "setInputImage() must be called before createMaskAt()"
        }

        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        val tapX = (x.coerceIn(0f, 1f) * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val tapY = (y.coerceIn(0f, 1f) * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)

        Log.d(TAG, "Tap (image px): ($tapX, $tapY)")

        val fullRect = Rect(0, 0, imageWidth, imageHeight)
        val globalResult = segmentRegion(bitmap, fullRect, tapX, tapY)

        Log.d(TAG, "Stage 1 (full photo): selected=${globalResult.selectedPercent}%")

        val (finalResult, finalRect) = refineWithFocusCrop(bitmap, globalResult, fullRect, tapX, tapY)
            ?: (globalResult to fullRect)

        expandMask(finalResult)

        val cropMask = toMaskBitmap(finalResult, finalRect.width(), finalRect.height())
        val objectMask = embedIntoFullSize(cropMask, finalRect, imageWidth, imageHeight)

        return mergeIntoSelection(objectMask)
    }

    fun segmentAt(x: Float, y: Float): MPImage {
        val bitmap = checkNotNull(sourceBitmap) {
            "setInputImage() must be called before segmentAt()"
        }
        segmenter.setImage(BitmapImageBuilder(bitmap).build())
        return segmenter.segment(listOf(buildPositiveStroke(x, y)))
    }

    fun close() {
        clearSelection()
        segmenter.close()
    }

    private fun expandMask(result: RawSegmentation) {
        val bounds = boundsOf(result, Rect(0, 0, result.width, result.height)) ?: return
        val span = max(bounds.width(), bounds.height())
        val radius = (span * MASK_MARGIN_FRACTION).roundToInt()
            .coerceIn(MIN_MASK_MARGIN_PX, MAX_MASK_MARGIN_PX)

        val width = result.width
        val height = result.height
        val mask = result.component

        val boundary = ArrayList<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!mask[index]) continue
                if (isExposed(mask, width, height, x, y)) boundary.add(index)
            }
        }

        val radiusSquared = radius * radius
        for (index in boundary) {
            val cx = index % width
            val cy = index / width
            for (dy in -radius..radius) {
                val py = cy + dy
                if (py !in 0 until height) continue
                for (dx in -radius..radius) {
                    val px = cx + dx
                    if (px !in 0 until width) continue
                    if (dx * dx + dy * dy <= radiusSquared) mask[py * width + px] = true
                }
            }
        }
    }

    private fun isExposed(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        val index = y * width + x
        return (x > 0 && !mask[index - 1]) ||
                (x < width - 1 && !mask[index + 1]) ||
                (y > 0 && !mask[index - width]) ||
                (y < height - 1 && !mask[index + width])
    }

    private fun mergeIntoSelection(objectMask: Bitmap): Bitmap {
        var selection = accumulatedMask

        if (selection == null || selection.width != objectMask.width || selection.height != objectMask.height) {
            selection?.recycle()
            selection = Bitmap.createBitmap(objectMask.width, objectMask.height, Bitmap.Config.ARGB_8888)
            accumulatedMask = selection
        }

        Canvas(selection).drawBitmap(objectMask, 0f, 0f, null)
        objectMask.recycle()

        return selection.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun refineWithFocusCrop(
        bitmap: Bitmap,
        globalResult: RawSegmentation,
        fullRect: Rect,
        tapX: Int,
        tapY: Int
    ): Pair<RawSegmentation, Rect>? {
        val objectBounds = boundsOf(globalResult, fullRect) ?: return null

        val shortSide = min(bitmap.width, bitmap.height)
        val longSide = max(bitmap.width, bitmap.height)
        val objectSpan = max(objectBounds.width(), objectBounds.height())

        var cropSide = max(
            MIN_CROP_SIZE_PX,
            (objectSpan * (1f + 2f * FOCUS_PADDING)).roundToInt()
        )

        if (cropSide > shortSide || cropSide > longSide * FOCUS_MAX_SPAN_FRACTION) return null

        var centerX = objectBounds.centerX()
        var centerY = objectBounds.centerY()
        var best: Pair<RawSegmentation, Rect>? = null

        repeat(MAX_CROP_ATTEMPTS) { attempt ->
            val rect = computeCropRect(centerX, centerY, cropSide, bitmap.width, bitmap.height)
            val result = segmentRegion(bitmap, rect, tapX, tapY)
            val truncated = isTruncatedByCrop(result, rect, bitmap.width, bitmap.height)

            Log.d(
                TAG,
                "Stage 2 attempt $attempt: crop=${rect.width()}x${rect.height()} " +
                        "selected=${result.selectedPercent}% truncated=$truncated"
            )

            if (result.isEmpty) {
                return best?.takeUnless { isTruncatedByCrop(it.first, it.second, bitmap.width, bitmap.height) }
            }

            best = result to rect

            if (!truncated || cropSide >= shortSide) {
                return best
            }

            boundsOf(result, rect)?.let {
                centerX = it.centerX()
                centerY = it.centerY()
            }
            cropSide = min((cropSide * CROP_GROWTH_FACTOR).roundToInt(), shortSide)
        }

        return best?.takeUnless { isTruncatedByCrop(it.first, it.second, bitmap.width, bitmap.height) }
    }

    private fun segmentRegion(bitmap: Bitmap, rect: Rect, tapX: Int, tapY: Int): RawSegmentation {
        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val input = downscaleForModel(cropped, bitmap)

        val localX = ((tapX - rect.left).toFloat() / rect.width()).coerceIn(0f, 1f)
        val localY = ((tapY - rect.top).toFloat() / rect.height()).coerceIn(0f, 1f)

        try {
            segmenter.setImage(BitmapImageBuilder(input).build())
            return runSegmentation(localX, localY, input)
        } finally {
            if (input !== bitmap) input.recycle()
        }
    }

    private fun downscaleForModel(cropped: Bitmap, original: Bitmap): Bitmap {
        val longest = max(cropped.width, cropped.height)
        if (longest <= MODEL_INPUT_MAX) return cropped

        val scale = MODEL_INPUT_MAX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            max(1, (cropped.width * scale).roundToInt()),
            max(1, (cropped.height * scale).roundToInt()),
            true
        )
        if (scaled !== cropped && cropped !== original) cropped.recycle()
        return scaled
    }

    private fun runSegmentation(seedX: Float, seedY: Float, input: Bitmap): RawSegmentation {
        val mask = segmenter.segment(listOf(buildPositiveStroke(seedX, seedY)))

        val sourceWidth = mask.width
        val sourceHeight = mask.height
        val scale = min(1f, PROCESS_SIZE.toFloat() / max(sourceWidth, sourceHeight))
        val workWidth = max(1, (sourceWidth * scale).roundToInt())
        val workHeight = max(1, (sourceHeight * scale).roundToInt())

        val confidence = ByteBufferExtractor.extract(mask).asFloatBuffer()

        val seedSourceX = (seedX.coerceIn(0f, 1f) * (sourceWidth - 1)).roundToInt()
        val seedSourceY = (seedY.coerceIn(0f, 1f) * (sourceHeight - 1)).roundToInt()
        val seedConfidence = confidence.get(seedSourceY * sourceWidth + seedSourceX)
        val threshold = min(
            CONFIDENCE_THRESHOLD,
            max(MIN_THRESHOLD, seedConfidence * SEED_RELATIVE_FACTOR)
        )

        val workConfidence = FloatArray(workWidth * workHeight)
        val binary = BooleanArray(workWidth * workHeight)
        for (yWork in 0 until workHeight) {
            val sourceY = (yWork.toFloat() / workHeight * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
            for (xWork in 0 until workWidth) {
                val sourceX = (xWork.toFloat() / workWidth * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
                val index = yWork * workWidth + xWork
                val value = confidence.get(sourceY * sourceWidth + sourceX)
                workConfidence[index] = value
                binary[index] = value >= threshold
            }
        }

        Log.d(TAG, "Mask: seedConfidence=$seedConfidence threshold=$threshold")

        val workSeedX = (seedX.coerceIn(0f, 1f) * (workWidth - 1)).roundToInt()
        val workSeedY = (seedY.coerceIn(0f, 1f) * (workHeight - 1)).roundToInt()

        val initial = extractConnectedComponent(binary, workConfidence, workWidth, workHeight, workSeedX, workSeedY)

        return trimColorOutliers(initial, input, workSeedX, workSeedY)
    }

    private fun trimColorOutliers(
        initial: RawSegmentation,
        input: Bitmap,
        seedX: Int,
        seedY: Int
    ): RawSegmentation {
        if (initial.isEmpty) return initial

        val width = initial.width
        val height = initial.height
        val confidence = initial.confidence
        val pixels = readPixels(input, width, height)

        var coreCount = 0
        val sum = DoubleArray(3)
        val sumSquares = DoubleArray(3)

        for (index in initial.component.indices) {
            if (!initial.component[index] || confidence[index] < CORE_CONFIDENCE) continue
            coreCount++
            for (channel in 0..2) {
                val value = channelValue(pixels[index], channel).toDouble()
                sum[channel] += value
                sumSquares[channel] += value * value
            }
        }

        if (coreCount < MIN_CORE_PIXELS) return initial

        val mean = FloatArray(3)
        val tolerance = FloatArray(3)
        for (channel in 0..2) {
            val average = sum[channel] / coreCount
            val variance = max(0.0, sumSquares[channel] / coreCount - average * average)
            mean[channel] = average.toFloat()
            tolerance[channel] =
                max(sqrt(variance).toFloat(), CHANNEL_DEVIATION_FLOOR[channel]) * COLOR_TOLERANCE_SIGMA
        }

        fun isOutlier(pixel: Int): Boolean {
            var distance = 0f
            for (channel in 0..2) {
                val delta = (channelValue(pixel, channel) - mean[channel]) / tolerance[channel]
                distance += delta * delta
            }
            return distance > 1f
        }

        val mask = initial.component.copyOf()
        val removable = IntArray(width * height)

        for (iteration in 0 until PEEL_MAX_ITERATIONS) {
            var removed = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (!mask[index] || confidence[index] >= CORE_CONFIDENCE) continue
                    if (isExposed(mask, width, height, x, y) && isOutlier(pixels[index])) {
                        removable[removed++] = index
                    }
                }
            }
            if (removed == 0) break
            for (i in 0 until removed) mask[removable[i]] = false
        }

        val refined = extractConnectedComponent(mask, confidence, width, height, seedX, seedY)

        Log.d(TAG, "Color trim: ${initial.selectedPercent}% -> ${refined.selectedPercent}%")

        return if (refined.isEmpty || refined.selectedPercent < initial.selectedPercent * MIN_RETAINED_FRACTION) {
            initial
        } else {
            refined
        }
    }

    private fun readPixels(input: Bitmap, width: Int, height: Int): IntArray {
        val scaled =
            if (input.width == width && input.height == height) input
            else Bitmap.createScaledBitmap(input, width, height, true)

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== input) scaled.recycle()
        return pixels
    }

    private fun channelValue(pixel: Int, channel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when (channel) {
            0 -> 0.299f * r + 0.587f * g + 0.114f * b
            1 -> -0.169f * r - 0.331f * g + 0.5f * b
            else -> 0.5f * r - 0.419f * g - 0.081f * b
        }
    }

    private fun extractConnectedComponent(
        binary: BooleanArray,
        confidence: FloatArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int
    ): RawSegmentation {
        val component = BooleanArray(width * height)
        var touchesLeft = false
        var touchesTop = false
        var touchesRight = false
        var touchesBottom = false
        var selected = 0

        val start = findNearestSet(binary, width, height, seedX, seedY)

        if (start != null) {
            val queue = IntArray(width * height)
            var head = 0
            var tail = 0

            val startIndex = start.second * width + start.first
            queue[tail++] = startIndex
            component[startIndex] = true

            while (head < tail) {
                val index = queue[head++]
                val px = index % width
                val py = index / width

                if (px == 0) touchesLeft = true
                if (px == width - 1) touchesRight = true
                if (py == 0) touchesTop = true
                if (py == height - 1) touchesBottom = true
                selected++

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val neighbor = ny * width + nx
                        if (binary[neighbor] && !component[neighbor]) {
                            component[neighbor] = true
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
        }

        return RawSegmentation(
            component = component,
            confidence = confidence,
            width = width,
            height = height,
            touchesLeft = touchesLeft,
            touchesTop = touchesTop,
            touchesRight = touchesRight,
            touchesBottom = touchesBottom,
            selectedPercent = selected.toFloat() / (width * height) * 100f
        )
    }

    private fun findNearestSet(
        mask: BooleanArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int
    ): Pair<Int, Int>? {
        if (mask[seedY * width + seedX]) return seedX to seedY

        for (radius in 1..SEED_SEARCH_RADIUS) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = seedX + dx
                    val py = seedY + dy
                    if (px !in 0 until width || py !in 0 until height) continue
                    if (mask[py * width + px]) return px to py
                }
            }
        }
        return null
    }

    private fun buildPositiveStroke(centerX: Float, centerY: Float): Stroke {
        val x = centerX.coerceIn(0f, 1f)
        val y = centerY.coerceIn(0f, 1f)
        val offsets = listOf(
            0f to 0f, -1f to -1f, 0f to -1f, 1f to -1f,
            -1f to 0f, 1f to 0f, -1f to 1f, 0f to 1f, 1f to 1f
        )

        val points = offsets.map { (dx, dy) ->
            NormalizedKeypoint.create(
                (x + dx * TAP_CLUSTER_OFFSET).coerceIn(0f, 1f),
                (y + dy * TAP_CLUSTER_OFFSET).coerceIn(0f, 1f)
            )
        }

        return Stroke.builder()
            .setBrushMode(Stroke.BrushMode.POSITIVE)
            .setPoints(points)
            .setCompleted(true)
            .build()
    }

    private fun boundsOf(result: RawSegmentation, rect: Rect): Rect? {
        var minX = result.width
        var maxX = -1
        var minY = result.height
        var maxY = -1

        for (y in 0 until result.height) {
            val row = y * result.width
            for (x in 0 until result.width) {
                if (!result.component[row + x]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (maxX < minX || maxY < minY) return null

        val scaleX = rect.width().toFloat() / result.width
        val scaleY = rect.height().toFloat() / result.height

        return Rect(
            rect.left + (minX * scaleX).toInt(),
            rect.top + (minY * scaleY).toInt(),
            rect.left + ((maxX + 1) * scaleX).toInt(),
            rect.top + ((maxY + 1) * scaleY).toInt()
        )
    }

    private fun isTruncatedByCrop(result: RawSegmentation, rect: Rect, imageWidth: Int, imageHeight: Int): Boolean =
        (result.touchesLeft && rect.left > 0) ||
                (result.touchesTop && rect.top > 0) ||
                (result.touchesRight && rect.right < imageWidth) ||
                (result.touchesBottom && rect.bottom < imageHeight)

    private fun computeCropRect(
        centerX: Int,
        centerY: Int,
        cropSize: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Rect {
        val size = min(cropSize, min(imageWidth, imageHeight))
        val left = (centerX - size / 2).coerceIn(0, imageWidth - size)
        val top = (centerY - size / 2).coerceIn(0, imageHeight - size)
        return Rect(left, top, left + size, top + size)
    }

    private fun toMaskBitmap(result: RawSegmentation, targetWidth: Int, targetHeight: Int): Bitmap {
        val pixels = IntArray(result.width * result.height) { index ->
            if (result.component[index]) MASK_COLOR else Color.TRANSPARENT
        }

        val small = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        small.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)

        if (result.width == targetWidth && result.height == targetHeight) return small

        val scaled = Bitmap.createScaledBitmap(small, targetWidth, targetHeight, false)
        small.recycle()
        return scaled
    }

    private fun embedIntoFullSize(
        cropMask: Bitmap,
        cropRect: Rect,
        fullWidth: Int,
        fullHeight: Int
    ): Bitmap {
        if (cropMask.width == fullWidth && cropMask.height == fullHeight) return cropMask

        val fullMask = Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888)
        Canvas(fullMask).drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), null)
        cropMask.recycle()
        return fullMask
    }
}