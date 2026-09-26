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
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class InteractiveSegmenterHelper(
    private val context: Context
) {

    companion object {

        /**
         * The binary-mask threshold is no longer a fixed constant. A fixed
         * cutoff (previously 0.45f, then 0.5f) assumes every tapped object
         * produces roughly the same confidence range, but Logcat has shown
         * that's false: a well-lit object on a full photo averaged ~0.29,
         * while a reflective phone on a dark table produced almost nothing
         * above 0.5 at all (selected ~0.03%). Instead, each pass reads the
         * model's own raw confidence AT THE EXACT TAP PIXEL and derives its
         * threshold from that - see THRESHOLD_RELATIVE_FACTOR below. This
         * makes the cutoff adapt automatically to how confident the model
         * happens to be about this particular object/lighting, instead of
         * guessing one global number that can't fit every case.
         */
        private const val THRESHOLD_RELATIVE_FACTOR = 0.55f

        /** Hard floor/ceiling so the adaptive threshold never gets degenerate. */
        private const val MIN_THRESHOLD = 0.15f
        private const val MAX_THRESHOLD = 0.75f

        /** Max resolution used for threshold/connected-component processing. */
        private const val PROCESS_SIZE = 768

        // ---- ROI-crop tuning ----
        // Detection segments a CROP around the tap instead of the whole
        // photo, since MediaPipe downsamples internally and a small object
        // in a huge photo loses all detail. See createMaskAt().
        private const val INITIAL_CROP_FRACTION = 0.35f
        private const val MIN_CROP_SIZE_PX = 480
        private const val CROP_GROWTH_FACTOR = 1.6f
        private const val MAX_CROP_ATTEMPTS = 3

        /** Negative points near the crop's four corners (very likely background). */
        private const val NEGATIVE_CORNER_INSET = 0.04f

        /**
         * Small, tight point cluster placed directly at the tap for the
         * PROBE pass. This just seeds a starting segmentation - it is
         * intentionally small so it can't itself spill onto neighboring
         * surfaces.
         */
        private const val PROBE_POINT_OFFSET = 0.015f

        /**
         * How many extra positive points to sample for the REFINE pass.
         */
        private const val REFINE_POINT_COUNT = 10

        /**
         * How far to pad the probe pass's own bounding box outward before
         * sampling REFINE points, as a fraction of that box's own
         * width/height (applied per axis). Padding scales with the
         * object's own detected extent - not a fixed radius - so an
         * elongated object (e.g. a phone) gets padding stretched along its
         * length rather than a blind circle that spills sideways. Some
         * sampled points will land just outside the probe's current mask
         * on purpose: those are what actually teach the second pass to
         * grow the selection, since points strictly inside an
         * already-too-small mask give the model no new information.
         */
        private const val GROWTH_PAD_FACTOR = 0.45f

        /** Neighborhood search radius (in work-resolution px) used when snapping a sample point onto the mask. */
        private const val NEAREST_TRUE_SEARCH_RADIUS = 6
    }

    private val segmenter: InteractiveSegmenter

    /**
     * Full-resolution source photo. Stored, not immediately pushed into the
     * segmenter - createMaskAt() builds a fresh crop around every tap
     * instead. Brush/Lasso never touch this class at all.
     */
    private var sourceBitmap: Bitmap? = null

    init {

        val baseOptions =
            com.google.mediapipe.tasks.core.BaseOptions
                .builder()
                .setModelAssetPath("interactive_segmentation.task")
                .build()

        val options =
            InteractiveSegmenterOptions
                .builder()
                .setBaseOptions(baseOptions)
                .build()

        segmenter = InteractiveSegmenter.createFromOptions(context, options)
    }

    /** Stores the current photo. Actually pushed into the segmenter per-crop in createMaskAt(). */
    fun setInputImage(bitmap: Bitmap) {
        sourceBitmap = bitmap
    }

    // =====================================================================
    // STROKE BUILDERS
    // =====================================================================

    private fun createNegativeCornerStroke(): Stroke {

        val inset = NEGATIVE_CORNER_INSET

        return Stroke.builder()
            .setBrushMode(Stroke.BrushMode.NEGATIVE)
            .setPoints(
                listOf(
                    NormalizedKeypoint.create(inset, inset),
                    NormalizedKeypoint.create(1f - inset, inset),
                    NormalizedKeypoint.create(inset, 1f - inset),
                    NormalizedKeypoint.create(1f - inset, 1f - inset)
                )
            )
            .setCompleted(true)
            .build()
    }

    /**
     * Builds the positive stroke for one segmentation pass: a tight 8-point
     * cluster right at the tap, plus whatever [extraPoints] were supplied
     * (empty for the probe pass; the refine pass's on-object samples for
     * the second pass).
     */
    private fun buildPositiveStroke(
        centerX: Float,
        centerY: Float,
        extraPoints: List<Pair<Float, Float>>
    ): Stroke {

        val safeX = centerX.coerceIn(0f, 1f)
        val safeY = centerY.coerceIn(0f, 1f)
        val offset = PROBE_POINT_OFFSET

        val points = mutableListOf(
            NormalizedKeypoint.create(safeX, safeY),
            NormalizedKeypoint.create((safeX - offset).coerceIn(0f, 1f), (safeY - offset).coerceIn(0f, 1f)),
            NormalizedKeypoint.create(safeX, (safeY - offset).coerceIn(0f, 1f)),
            NormalizedKeypoint.create((safeX + offset).coerceIn(0f, 1f), (safeY - offset).coerceIn(0f, 1f)),
            NormalizedKeypoint.create((safeX - offset).coerceIn(0f, 1f), safeY),
            NormalizedKeypoint.create((safeX + offset).coerceIn(0f, 1f), safeY),
            NormalizedKeypoint.create((safeX - offset).coerceIn(0f, 1f), (safeY + offset).coerceIn(0f, 1f)),
            NormalizedKeypoint.create(safeX, (safeY + offset).coerceIn(0f, 1f)),
            NormalizedKeypoint.create((safeX + offset).coerceIn(0f, 1f), (safeY + offset).coerceIn(0f, 1f))
        )

        for ((ex, ey) in extraPoints) {
            points.add(NormalizedKeypoint.create(ex.coerceIn(0f, 1f), ey.coerceIn(0f, 1f)))
        }

        return Stroke.builder()
            .setBrushMode(Stroke.BrushMode.POSITIVE)
            .setPoints(points)
            .setCompleted(true)
            .build()
    }

    // =====================================================================
    // RAW SEGMENTATION (thresholded + connected-component, at capped resolution)
    // =====================================================================

    /** Result of one segmentation pass, kept at the reduced processing resolution. */
    private class RawSegmentation(
        val component: BooleanArray,
        val workWidth: Int,
        val workHeight: Int,
        val touchesBorder: Boolean,
        val selectedPercent: Float
    )

    private fun findNearestTrue(
        component: BooleanArray,
        workWidth: Int,
        workHeight: Int,
        seedX: Int,
        seedY: Int,
        searchRadius: Int
    ): Pair<Int, Int>? {

        val startIndex = seedY * workWidth + seedX

        if (component[startIndex]) {
            return seedX to seedY
        }

        for (radius in 1..searchRadius) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = seedX + dx
                    val py = seedY + dy
                    if (px < 0 || px >= workWidth || py < 0 || py >= workHeight) continue
                    if (component[py * workWidth + px]) {
                        return px to py
                    }
                }
            }
        }

        return null
    }

    /**
     * Runs one segmenter.segment() call (assumes segmenter.setImage() was
     * already called for the current crop), thresholds the result, and
     * keeps only the connected component touching the tap - at a capped
     * processing resolution (PROCESS_SIZE) to keep memory bounded
     * regardless of the crop's actual pixel size.
     */
    private fun runSegmentationPass(
        cropWidth: Int,
        cropHeight: Int,
        localSeedX: Float,
        localSeedY: Float,
        extraPositivePoints: List<Pair<Float, Float>>
    ): RawSegmentation {

        val strokes = listOf(
            buildPositiveStroke(localSeedX, localSeedY, extraPositivePoints),
            createNegativeCornerStroke()
        )

        val mpMask = segmenter.segment(strokes)

        val sourceWidth = mpMask.width
        val sourceHeight = mpMask.height

        val scale = minOf(1f, PROCESS_SIZE.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat())
        val workWidth = maxOf(1, (sourceWidth * scale).roundToInt())
        val workHeight = maxOf(1, (sourceHeight * scale).roundToInt())

        val buffer = ByteBufferExtractor.extract(mpMask).asFloatBuffer()

        // Read the model's raw confidence at the exact tap pixel, in the
        // mask's native (pre-downsample) resolution, so the threshold
        // below is derived from real signal, not from an already-blurred
        // downsampled sample.
        val seedSourceX = (localSeedX.coerceIn(0f, 1f) * (sourceWidth - 1)).roundToInt()
        val seedSourceY = (localSeedY.coerceIn(0f, 1f) * (sourceHeight - 1)).roundToInt()
        val seedValue = buffer.get(seedSourceY * sourceWidth + seedSourceX)

        val effectiveThreshold =
            (seedValue * THRESHOLD_RELATIVE_FACTOR).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        val binary = BooleanArray(workWidth * workHeight)

        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        var sumValue = 0f

        for (yWork in 0 until workHeight) {

            val sourceY = (yWork.toFloat() / workHeight.toFloat() * sourceHeight)
                .toInt().coerceIn(0, sourceHeight - 1)

            for (xWork in 0 until workWidth) {

                val sourceX = (xWork.toFloat() / workWidth.toFloat() * sourceWidth)
                    .toInt().coerceIn(0, sourceWidth - 1)

                val value = buffer.get(sourceY * sourceWidth + sourceX)

                if (value < minValue) minValue = value
                if (value > maxValue) maxValue = value
                sumValue += value

                binary[yWork * workWidth + xWork] = value >= effectiveThreshold
            }
        }

        val avgValue = sumValue / (workWidth * workHeight).toFloat()

        Log.d(
            "Detection",
            "Raw mask: seedValue=$seedValue effectiveThreshold=$effectiveThreshold " +
                    "min=$minValue max=$maxValue avg=$avgValue"
        )

        val seedX = (localSeedX.coerceIn(0f, 1f) * (workWidth - 1)).roundToInt()
        val seedY = (localSeedY.coerceIn(0f, 1f) * (workHeight - 1)).roundToInt()

        val start = findNearestTrue(binary, workWidth, workHeight, seedX, seedY, 24)

        val component = BooleanArray(workWidth * workHeight)
        var touchesBorder = false
        var selectedPixels = 0

        if (start != null) {

            val startIndex = start.second * workWidth + start.first

            val queue = IntArray(workWidth * workHeight)
            var head = 0
            var tail = 0

            queue[tail++] = startIndex
            component[startIndex] = true

            while (head < tail) {

                val index = queue[head++]
                val px = index % workWidth
                val py = index / workWidth

                if (px == 0 || px == workWidth - 1 || py == 0 || py == workHeight - 1) {
                    touchesBorder = true
                }

                selectedPixels++

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || nx >= workWidth || ny < 0 || ny >= workHeight) continue
                        val neighbor = ny * workWidth + nx
                        if (binary[neighbor] && !component[neighbor]) {
                            component[neighbor] = true
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
        }

        val selectedPercent = selectedPixels.toFloat() / (workWidth * workHeight).toFloat() * 100f

        return RawSegmentation(component, workWidth, workHeight, touchesBorder, selectedPercent)
    }

    /**
     * Samples up to [maxPoints] positive points for the REFINE pass, drawn
     * from [raw]'s own bounding box PADDED outward by [padFactor] per axis
     * (see GROWTH_PAD_FACTOR doc comment above). Unlike a strictly-interior
     * sample, points here are allowed to land just beyond the probe pass's
     * current mask - that's intentional, since only points outside the
     * current selection can teach the second pass to actually grow it.
     * Each grid point snaps to the nearest already-true pixel within a
     * small radius if one exists (keeping it as a strong on-object anchor);
     * otherwise it's used as-is, as a "grow toward here" hint.
     */
    private fun sampleInteriorPoints(
        raw: RawSegmentation,
        maxPoints: Int,
        padFactor: Float
    ): List<Pair<Float, Float>> {

        val component = raw.component
        val workWidth = raw.workWidth
        val workHeight = raw.workHeight

        var minX = workWidth
        var maxX = -1
        var minY = workHeight
        var maxY = -1

        for (y in 0 until workHeight) {
            val row = y * workWidth
            for (x in 0 until workWidth) {
                if (component[row + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return emptyList()
        }

        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1

        val padX = (boxWidth * padFactor).roundToInt()
        val padY = (boxHeight * padFactor).roundToInt()

        val paddedMinX = (minX - padX).coerceAtLeast(0)
        val paddedMaxX = (maxX + padX).coerceAtMost(workWidth - 1)
        val paddedMinY = (minY - padY).coerceAtLeast(0)
        val paddedMaxY = (maxY + padY).coerceAtMost(workHeight - 1)

        val paddedWidth = paddedMaxX - paddedMinX
        val paddedHeight = paddedMaxY - paddedMinY

        val gridDim = ceil(sqrt(maxPoints.toDouble())).toInt().coerceAtLeast(1)

        val points = mutableListOf<Pair<Float, Float>>()

        outer@ for (gy in 0 until gridDim) {
            for (gx in 0 until gridDim) {

                if (points.size >= maxPoints) break@outer

                val cellX = (paddedMinX + (gx + 0.5f) / gridDim * paddedWidth).roundToInt()
                    .coerceIn(paddedMinX, paddedMaxX)

                val cellY = (paddedMinY + (gy + 0.5f) / gridDim * paddedHeight).roundToInt()
                    .coerceIn(paddedMinY, paddedMaxY)

                val snapped = findNearestTrue(
                    component, workWidth, workHeight, cellX, cellY, NEAREST_TRUE_SEARCH_RADIUS
                )

                val point = snapped ?: (cellX to cellY)

                points.add(
                    point.first.toFloat() / workWidth.toFloat() to
                            point.second.toFloat() / workHeight.toFloat()
                )
            }
        }

        return points
    }

    private fun rawToBitmap(raw: RawSegmentation, cropWidth: Int, cropHeight: Int): Bitmap {

        val workWidth = raw.workWidth
        val workHeight = raw.workHeight

        val smallPixels = IntArray(workWidth * workHeight)

        for (i in raw.component.indices) {
            smallPixels[i] = if (raw.component[i]) Color.WHITE else Color.TRANSPARENT
        }

        val smallMask = Bitmap.createBitmap(workWidth, workHeight, Bitmap.Config.ARGB_8888)
        smallMask.setPixels(smallPixels, 0, workWidth, 0, 0, workWidth, workHeight)

        if (workWidth == cropWidth && workHeight == cropHeight) {
            return smallMask
        }

        val scaled = Bitmap.createScaledBitmap(smallMask, cropWidth, cropHeight, false)
        smallMask.recycle()
        return scaled
    }

    // =====================================================================
    // CROP GEOMETRY
    // =====================================================================

    private fun computeCropRect(
        tapX: Int,
        tapY: Int,
        cropSize: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Rect {

        val size = min(cropSize, min(bitmapWidth, bitmapHeight))

        var left = tapX - size / 2
        var top = tapY - size / 2

        left = left.coerceIn(0, bitmapWidth - size)
        top = top.coerceIn(0, bitmapHeight - size)

        return Rect(left, top, left + size, top + size)
    }

    private fun embedIntoFullSize(
        cropMask: Bitmap,
        cropRect: Rect,
        fullWidth: Int,
        fullHeight: Int
    ): Bitmap {

        val fullMask = Bitmap.createBitmap(fullWidth, fullHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullMask)
        canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), null)
        cropMask.recycle()

        return fullMask
    }

    // =====================================================================
    // PUBLIC ENTRY POINT
    // =====================================================================

    /**
     * Creates the detection mask for a tap at normalized image coordinates.
     *
     * For each crop attempt: runs a small PROBE segmentation right at the
     * tap, samples extra positive points from strictly inside that probe's
     * own result (so they respect the object's real shape), then reruns
     * segmentation as a REFINE pass with tap + those verified points. If
     * the refine pass's selection still touches the crop's edge, the crop
     * is grown and the whole probe+refine cycle repeats (up to
     * MAX_CROP_ATTEMPTS). The final mask is embedded into a full-photo-
     * sized, mostly transparent Bitmap so every downstream consumer (the
     * on-canvas overlay, LamaInpainter.inpaint()) keeps working unchanged.
     */
    fun createMaskAt(x: Float, y: Float): Bitmap {

        val bmp = sourceBitmap
            ?: throw IllegalStateException("setInputImage() must be called before createMaskAt()")

        val tapX = (x.coerceIn(0f, 1f) * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val tapY = (y.coerceIn(0f, 1f) * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)

        Log.d("Detection", "Tap (image px): ($tapX, $tapY)")

        var cropSize = max(
            MIN_CROP_SIZE_PX,
            (min(bmp.width, bmp.height) * INITIAL_CROP_FRACTION).roundToInt()
        )

        val maxCropSize = min(bmp.width, bmp.height)

        lateinit var finalRaw: RawSegmentation
        lateinit var finalRect: Rect

        var attempt = 0

        while (true) {

            val cropRect = computeCropRect(tapX, tapY, cropSize, bmp.width, bmp.height)

            val cropped = Bitmap.createBitmap(
                bmp, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()
            )

            segmenter.setImage(BitmapImageBuilder(cropped).build())

            val localX = ((tapX - cropRect.left).toFloat() / cropRect.width().toFloat()).coerceIn(0f, 1f)
            val localY = ((tapY - cropRect.top).toFloat() / cropRect.height().toFloat()).coerceIn(0f, 1f)

            val probe = runSegmentationPass(cropRect.width(), cropRect.height(), localX, localY, emptyList())

            Log.d(
                "Detection",
                "Attempt $attempt probe: crop=${cropRect.width()}x${cropRect.height()} " +
                        "selected=${probe.selectedPercent}% touchesBorder=${probe.touchesBorder}"
            )

            val interiorPoints = sampleInteriorPoints(probe, REFINE_POINT_COUNT, GROWTH_PAD_FACTOR)

            val refined =
                if (interiorPoints.isNotEmpty()) {
                    runSegmentationPass(cropRect.width(), cropRect.height(), localX, localY, interiorPoints)
                } else {
                    probe
                }

            Log.d(
                "Detection",
                "Attempt $attempt refine: extraPoints=${interiorPoints.size} " +
                        "selected=${refined.selectedPercent}% touchesBorder=${refined.touchesBorder}"
            )

            cropped.recycle()

            attempt++

            val canGrow = cropSize < maxCropSize && attempt < MAX_CROP_ATTEMPTS

            if (!refined.touchesBorder || !canGrow) {
                finalRaw = refined
                finalRect = cropRect
                break
            }

            // Object bigger than this crop - grow and retry.
            cropSize = min((cropSize * CROP_GROWTH_FACTOR).roundToInt(), maxCropSize)
        }

        val cropMask = rawToBitmap(finalRaw, finalRect.width(), finalRect.height())

        return embedIntoFullSize(cropMask, finalRect, bmp.width, bmp.height)
    }

    /**
     * Raw, uncropped segmentation for the whole photo. Not used by the
     * current UI (PhotoEditor calls createMaskAt), kept for API
     * compatibility only.
     */
    fun segmentAt(x: Float, y: Float): MPImage {
        val bmp = sourceBitmap
            ?: throw IllegalStateException("setInputImage() must be called before segmentAt()")
        segmenter.setImage(BitmapImageBuilder(bmp).build())
        return segmenter.segment(listOf(buildPositiveStroke(x, y, emptyList())))
    }

    fun close() {
        segmenter.close()
    }
}