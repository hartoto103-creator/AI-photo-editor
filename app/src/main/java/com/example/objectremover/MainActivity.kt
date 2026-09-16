package com.example.objectremover

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter

import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.res.painterResource

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import kotlin.math.ceil
import kotlin.math.roundToInt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath


/**
 * Which tool is currently active. All four share the same underlying
 * freehand-capture pipeline in [PhotoEditor]; only how a gesture is
 * interpreted while it's happening, and how the finished stroke is
 * rendered, differs per tool:
 *
 * - BRUSH: paints a freehand mask with a subtle, semi-transparent red
 *   overlay so the photo stays visible underneath.
 * - LASSO: freehand outline that auto-closes into a loop on release,
 *   drawn with a subtle semi-transparent red stroke/fill to read as a
 *   "selection".
 * - DETECTION: paints a freehand mask exactly like BRUSH, but with a
 *   subtle white overlay instead of red. There's no ML model wired in
 *   here, so it's just a differently-colored paint tool for now - real
 *   detection would replace the freehand capture with a call out to a
 *   segmentation model and turn its result into this tool's mask.
 * - DESELECT: a mask-eraser brush, fixed at [DESELECT_BRUSH_SIZE].
 *   Painting with it removes whatever it touches from the active mask
 *   (via BlendMode.Clear against the mask layer) - it never touches
 *   the underlying photo.
 */
enum class ToolMode {
    BRUSH,
    LASSO,
    DETECTION,
    DESELECT
}


/**
 * Points are always stored in IMAGE space (bitmap pixel coordinates),
 * never in raw screen space. This is what fixes the "stroke jumps after
 * release" bug: previously, the live stroke was drawn in raw screen
 * coordinates while dragging, then converted to image space only once,
 * at release. Any drift between the values used for that one-time
 * conversion and the values later used to render the stroke would make
 * the finished stroke land somewhere different from where it was drawn.
 *
 * Now conversion happens per-point, in real time, and the live stroke
 * is rendered through the exact same transform as saved strokes -
 * there's no separate "commit" step left that can disagree.
 */
data class BrushStroke(
    val points: List<Offset>, // image space
    val width: Float,         // image space
    val tool: ToolMode = ToolMode.BRUSH
)


class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            ObjectRemoverApp()
        }
    }
}


// =====================================================================
// TOOLBAR ICONS
// ---------------------------------------------------------------------
// Back / Undo / Redo / Deselect / Save / Lasso / Brush / Detection now
// render from the eight VectorDrawable resources already in
// res/drawable/ (ic_back, ic_undo, ic_redo, ic_eraser, ic_save,
// ic_lasso, ic_brush, ic_scan) via painterResource(...) + Compose
// Icon. Each usage site below calls painterResource() directly against
// its own drawable id, so every icon <-> resource mapping is explicit
// and easy to audit:
//
//   Back      -> painterResource(R.drawable.ic_back)
//   Save      -> painterResource(R.drawable.ic_save)
//   Brush     -> painterResource(R.drawable.ic_brush)
//   Lasso     -> painterResource(R.drawable.ic_lasso)
//   Detection -> painterResource(R.drawable.ic_scan)
//   Deselect  -> painterResource(R.drawable.ic_eraser)
//   Undo      -> painterResource(R.drawable.ic_undo)
//   Redo      -> painterResource(R.drawable.ic_redo)
//
// ToolIcon is a thin wrapper around Icon(painter, tint, modifier) so
// every call site keeps the same "give me a painter + a tint, get a
// properly sized icon" shape the old hand-drawn IconGlyph/DrawScope
// glyphs had. Existing active/inactive/disabled tint logic (computed
// by callers, e.g. ToolButton's `tint`) is unchanged - only where the
// pixels come from changed.
//
// drawImageGlyph (Home Screen), drawWarningGlyph (discard-confirm
// dialog), and the photo-picker glyphs (drawCloseGlyph,
// drawCameraGlyph) are intentionally left as hand-drawn Canvas
// glyphs below, since no drawable resource exists for them and
// they are out of scope for the icon-resource swap.
// =====================================================================

@Composable
private fun ToolIcon(
    painter: Painter,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {

    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}


@Composable
private fun IconGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    onDraw: DrawScope.(Color) -> Unit
) {

    Canvas(modifier = modifier) {
        onDraw(tint)
    }
}


private fun DrawScope.drawImageGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.07f

    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.1f, h * 0.15f),
        size = Size(w * 0.8f, h * 0.7f),
        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        style = Stroke(width = sw)
    )

    drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.32f, h * 0.38f))

    val path = Path().apply {
        moveTo(w * 0.18f, h * 0.75f)
        lineTo(w * 0.4f, h * 0.5f)
        lineTo(w * 0.55f, h * 0.65f)
        lineTo(w * 0.7f, h * 0.45f)
        lineTo(w * 0.85f, h * 0.75f)
    }

    drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}


/**
 * Warning triangle used by [ConfirmDiscardDialog]. Same hand-drawn
 * Canvas/DrawScope approach as before - no drawable resource exists
 * for this glyph, so it is out of scope for this pass and is left
 * as-is.
 */
private fun DrawScope.drawWarningGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.09f

    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.92f, h * 0.85f)
        lineTo(w * 0.08f, h * 0.85f)
        close()
    }

    drawPath(path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

    drawLine(tint, Offset(w * 0.5f, h * 0.4f), Offset(w * 0.5f, h * 0.62f), sw, cap = StrokeCap.Round)
    drawCircle(tint, radius = sw * 0.6f, center = Offset(w * 0.5f, h * 0.74f))
}


/**
 * The scale that makes the image fully FIT INSIDE the canvas (like
 * ContentScale.Fit) - the whole photo stays visible, letterboxed on
 * whichever axis doesn't match the canvas's aspect ratio, instead of
 * being cropped. Uses min() of the two axis ratios so both dimensions
 * fit inside the canvas.
 *
 * Takes plain Float width/height rather than IntSize/Size directly,
 * because PointerInputScope.size is IntSize while DrawScope.size is
 * Size (Float-based) - two different types with the same name. Using
 * primitives here lets both call sites feed this one function without
 * a type mismatch.
 */
private fun baseScaleFor(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmap: Bitmap
): Float {

    return kotlin.math.min(
        canvasWidth / bitmap.width.toFloat(),
        canvasHeight / bitmap.height.toFloat()
    )
}


/**
 * Clamps pan so the image can never be dragged far enough to expose
 * empty space at an edge. `scale` here is the raw pinch/zoom level
 * (1f = not zoomed in at all), separate from `totalScale` (which
 * already includes the base "fit" scale and is what's used for the
 * actual math). Below 1x zoom the image is pinned dead-center with no
 * panning allowed at all.
 */
private fun clampPan(
    pan: Offset,
    scale: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    bitmap: Bitmap,
    totalScale: Float
): Offset {

    if (scale <= 1.001f) {
        return Offset.Zero
    }

    val displayedWidth =
        bitmap.width * totalScale

    val displayedHeight =
        bitmap.height * totalScale

    val maxPanX =
        ((displayedWidth - canvasWidth) / 2f)
            .coerceAtLeast(0f)

    val maxPanY =
        ((displayedHeight - canvasHeight) / 2f)
            .coerceAtLeast(0f)

    return Offset(
        pan.x.coerceIn(-maxPanX, maxPanX),
        pan.y.coerceIn(-maxPanY, maxPanY)
    )
}


/**
 * Converts a point from screen (Canvas-local) space into image
 * (bitmap pixel) space, using the same center/scale/pan math the
 * draw phase uses to go the other direction. Keeping this in one
 * function guarantees both directions always agree.
 */
private fun screenToImage(
    screenPoint: Offset,
    canvasSize: IntSize,
    bitmap: Bitmap,
    scale: Float,
    pan: Offset
): Offset {

    val center =
        Offset(
            canvasSize.width / 2f,
            canvasSize.height / 2f
        )

    val totalScale =
        baseScaleFor(
            canvasSize.width.toFloat(),
            canvasSize.height.toFloat(),
            bitmap
        ) * scale

    return Offset(
        (screenPoint.x - center.x - pan.x) / totalScale + bitmap.width / 2f,
        (screenPoint.y - center.y - pan.y) / totalScale + bitmap.height / 2f
    )
}


private fun totalScaleFor(
    canvasSize: IntSize,
    bitmap: Bitmap,
    scale: Float
): Float {

    return baseScaleFor(
        canvasSize.width.toFloat(),
        canvasSize.height.toFloat(),
        bitmap
    ) * scale
}


/**
 * Bakes the current marks onto a copy of the source bitmap and saves
 * it to the device gallery. This app has no inpainting backend, so
 * "save" exports the photo with the marked areas overlaid - not an
 * object-removed result. Wiring a real remover would mean sending
 * (bitmap, mask-from-strokes) to an inpainting model and saving its
 * output instead of drawing the strokes directly here.
 */
private fun saveMarkedImage(
    context: Context,
    source: Bitmap,
    strokes: List<BrushStroke>
): Boolean {

    val result =
        source.copy(Bitmap.Config.ARGB_8888, true)

    val canvas =
        AndroidCanvas(result)

    val paint =
        AndroidPaint().apply {
            isAntiAlias = true
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            style = AndroidPaint.Style.STROKE
        }

    for (stroke in strokes) {

        if (stroke.points.isEmpty()) continue

        paint.color = androidColorFor(stroke.tool)
        paint.strokeWidth = stroke.width

        if (stroke.points.size == 1) {

            val p = stroke.points[0]

            paint.style = AndroidPaint.Style.FILL

            canvas.drawCircle(p.x, p.y, stroke.width / 2f, paint)

            paint.style = AndroidPaint.Style.STROKE

        } else {

            val path = AndroidPath()

            path.moveTo(stroke.points[0].x, stroke.points[0].y)

            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }

            canvas.drawPath(path, paint)
        }
    }

    return try {

        @Suppress("DEPRECATION")
        MediaStore.Images.Media.insertImage(
            context.contentResolver,
            result,
            "object_remover_${System.currentTimeMillis()}",
            "Edited with Object Remover"
        )

        true

    } catch (e: Exception) {

        e.printStackTrace()

        false
    }
}


/**
 * =====================================================================
 * ACTIVE SELECTION -> LaMa MASK BRIDGE
 * ---------------------------------------------------------------------
 * The app has exactly ONE active selection: the running composite of
 * every entry in `strokes` (BRUSH/LASSO/DETECTION paint the selection
 * on, DESELECT erases from it), each already stored in image-space
 * coordinates (see BrushStroke's doc comment). This is the SAME
 * selection PhotoEditor renders on-screen via drawStrokePath() inside
 * a single saveLayer - this function rasterizes that identical
 * stroke-by-stroke composite (same order, same per-tool shape logic:
 * stroke for BRUSH/DETECTION, filled closed path for LASSO,
 * PorterDuff.CLEAR for DESELECT) into a standalone android.graphics
 * Bitmap sized to the source photo, instead of a semi-transparent
 * on-screen overlay.
 *
 * The only difference from the on-screen render is opacity: the
 * on-screen version uses partial alpha (~0.35) purely so the photo
 * stays visible underneath; this version paints fully opaque
 * (alpha 255) wherever a stroke/lasso covers, because full alpha IS
 * the "remove" signal LamaInpainter.preprocessMask() reads (alpha 255
 * = remove, alpha 0 = keep, linear in between - which anti-aliased
 * edges here naturally produce). There is no second,
 * independently-maintained mask representation - this is a
 * rasterization of the exact same `strokes` list, produced fresh
 * right before each Delete.
 * =====================================================================
 */
private fun rasterizeSelectionMask(
    width: Int,
    height: Int,
    strokes: List<BrushStroke>
): Bitmap {

    val mask =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val canvas =
        AndroidCanvas(mask)

    // Fully transparent starting point - alpha 0 everywhere means
    // "keep" until a stroke paints over it.
    canvas.drawColor(
        android.graphics.Color.TRANSPARENT,
        android.graphics.PorterDuff.Mode.CLEAR
    )

    val paint =
        AndroidPaint().apply {
            isAntiAlias = true
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
            // Fully opaque white - only the ALPHA channel is read by
            // preprocessMask(), so the RGB value here is irrelevant.
            color = android.graphics.Color.WHITE
        }

    val clearXfermode =
        android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)

    for (stroke in strokes) {

        if (stroke.points.isEmpty()) continue

        val isErase = stroke.tool == ToolMode.DESELECT
        val isLasso = stroke.tool == ToolMode.LASSO

        // Same per-tool shape rule as drawStrokePath(): DESELECT
        // punches a hole (PorterDuff.CLEAR) instead of painting;
        // LASSO fills its closed interior; BRUSH/DETECTION/DESELECT
        // stroke a round-capped path.
        paint.xfermode = if (isErase) clearXfermode else null
        paint.style =
            if (isLasso) AndroidPaint.Style.FILL else AndroidPaint.Style.STROKE
        paint.strokeWidth = stroke.width

        if (stroke.points.size == 1) {

            // Single-tap stroke: always a filled dot, exactly like
            // drawStrokePath()'s single-point branch.
            val p = stroke.points[0]
            val savedStyle = paint.style
            paint.style = AndroidPaint.Style.FILL
            canvas.drawCircle(p.x, p.y, stroke.width / 2f, paint)
            paint.style = savedStyle

        } else {

            val path = AndroidPath()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)

            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }

            if (isLasso) {
                path.close()

                canvas.drawPath(path, paint)

                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 8f
                paint.strokeCap = AndroidPaint.Cap.ROUND
                paint.strokeJoin = AndroidPaint.Join.ROUND

                canvas.drawPath(path, paint)

                paint.style = AndroidPaint.Style.FILL
            } else {
                canvas.drawPath(path, paint)
            }
        }
    }

    return mask
}


private fun androidColorFor(tool: ToolMode): Int {

    return when (tool) {
        ToolMode.BRUSH -> android.graphics.Color.RED
        ToolMode.LASSO -> android.graphics.Color.RED
        ToolMode.DETECTION -> android.graphics.Color.WHITE
        // DESELECT strokes are erasers, not marks - transparent means
        // they draw as a no-op onto the exported/baked image.
        ToolMode.DESELECT -> android.graphics.Color.TRANSPARENT
    }
}


private fun composeColorFor(tool: ToolMode): Color {

    return when (tool) {
        ToolMode.BRUSH -> Color.Red
        ToolMode.LASSO -> Color.Red
        ToolMode.DETECTION -> Color.White
        // Only used for the on-screen cursor ring outline (see
        // PhotoEditor); the actual erase paint color is irrelevant to
        // BlendMode.Clear and is set separately in drawStrokePath().
        ToolMode.DESELECT -> Color.Gray
    }
}


/**
 * Fixed brush size (same units as [BrushStroke.width]/[brushSize]
 * before scale-adjustment) used by the DESELECT/mask-eraser tool. The
 * eraser is always this size, regardless of whatever the Brush Size
 * slider currently shows.
 */
private const val DESELECT_BRUSH_SIZE = 40f


// A single shared brand color used by the Home Screen's primary
// action button, kept as one named constant so Home Screen styling
// stays consistent as more entry points get added there later.
private val HomeAccentPurple = Color(0xFF6A3DE8)


/**
 * Undoes the default "zoom in" window-return animation Android plays
 * when control comes back to this Activity from a separate Activity
 * (e.g. the system Photo Picker, or a camera app launched via
 * ACTION_IMAGE_CAPTURE). Shared by every call site that launches an
 * external Activity and expects a jump-cut return instead.
 */
private fun suppressReturnTransition(context: Context) {

    (context as? Activity)?.let { activity ->

        if (Build.VERSION.SDK_INT >= 34) {

            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                0,
                0
            )

        } else {

            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
        // Project's minSdk is 26, so overridePendingTransition
        // (added in API 5) is always available here - no extra
        // version check needed below the API-34 branch.
    }
}


// =====================================================================
// IMAGE ORIENTATION CORRECTION
// ---------------------------------------------------------------------
// The single place, for the whole app, where a photo Uri turns into a
// Bitmap. Both entry points into the editor - picking a photo from
// PhotoPickerScreen's album grid, and finishing a capture from the
// Camera row - route through onImageUriSelected() in ObjectRemoverApp,
// which calls decodeOrientedBitmap() below instead of a raw
// BitmapFactory.decodeStream(). That guarantees gallery photos and
// camera photos get exactly the same orientation handling, and that
// correction happens exactly once, before the Bitmap ever reaches
// EditorScreen/PhotoEditor.
//
// This does NOT touch screenToImage(), baseScaleFor(), clampPan(),
// totalScaleFor(), the pointerInput gesture code, stroke rendering,
// undo/redo, save, or any picker/camera/navigation UI - by the time
// any of that code sees `bitmap`, its width/height/pixels already
// reflect the photo's true visual orientation, so none of the
// existing coordinate math needs to know orientation correction ever
// happened.
// =====================================================================

/**
 * Reads the EXIF orientation tag directly from [uri] and returns the
 * Matrix needed to bring the bitmap to its correct visual
 * orientation, or null if no transform is needed (already normal/
 * undefined, or EXIF couldn't be read). Returning null for the
 * "already correct" case lets the caller skip an unnecessary bitmap
 * copy for the very common case of a photo that's already stored
 * upright.
 *
 * Every one of the eight EXIF orientation values is handled
 * explicitly - including the two transpose cases, which need a flip
 * *and* a rotate, not just a rotate.
 */
private fun exifTransformFor(context: Context, uri: Uri): Matrix? {

    val orientation =
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    val matrix = Matrix()

    when (orientation) {

        ExifInterface.ORIENTATION_NORMAL,
        ExifInterface.ORIENTATION_UNDEFINED -> return null

        ExifInterface.ORIENTATION_ROTATE_90 ->
            matrix.postRotate(90f)

        ExifInterface.ORIENTATION_ROTATE_180 ->
            matrix.postRotate(180f)

        ExifInterface.ORIENTATION_ROTATE_270 ->
            matrix.postRotate(270f)

        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            matrix.postScale(-1f, 1f)

        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            matrix.postScale(1f, -1f)

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            // Mirror horizontally, then rotate 90 - transpose across
            // the main diagonal.
            matrix.postScale(-1f, 1f)
            matrix.postRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            // Mirror horizontally, then rotate 270 - transpose across
            // the anti-diagonal.
            matrix.postScale(-1f, 1f)
            matrix.postRotate(270f)
        }

        else -> return null
    }

    return matrix
}


/**
 * Decodes [uri] into a Bitmap with its EXIF orientation applied
 * exactly once, so the Bitmap this returns already has its true
 * visual width/height and pixel layout - no orientation flag is left
 * for the editor to interpret later, and no rotation happens anywhere
 * downstream (canvas, gestures, or otherwise).
 *
 * Never forces width > height or height > width on its own: the
 * width/height swap for a 90/270-rotated photo only happens as the
 * natural side effect of Bitmap.createBitmap() actually rotating the
 * pixel data via the matrix, so a portrait photo stays portrait, a
 * landscape photo stays landscape, and aspect ratio is preserved
 * exactly - no cropping, stretching, or resizing.
 *
 * This is the ONLY bitmap-decoding function used for photos entering
 * the editor; onImageUriSelected() (below) is its only caller, and
 * that one function already receives Uris from both the gallery grid
 * and the camera capture flow.
 */
private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {

    val decoded =
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        } ?: return null

    val matrix =
        exifTransformFor(context, uri) ?: return decoded

    return try {
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
    } catch (e: Exception) {
        decoded
    }
}


@Composable
fun ObjectRemoverApp() {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val interactiveSegmenter =
        remember {
            InteractiveSegmenterHelper(context)
        }

    DisposableEffect(Unit) {
        onDispose {
            interactiveSegmenter.close()
        }
    }

    val coroutineScope =
        rememberCoroutineScope()

    var bitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var detectionMask by remember {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(bitmap) {
        bitmap?.let { currentBitmap ->
            interactiveSegmenter.setInputImage(currentBitmap)
        }
    }

    // Controls whether the custom gallery-style photo picker
    // (PhotoPickerScreen) is currently shown in place of HomeScreen.
    // This is purely a photo-*selection* flow flag - it never touches
    // any editor state below.
    var showPhotoPicker by remember {
        mutableStateOf(false)
    }

    var brushSize by remember {
        mutableFloatStateOf(40f)
    }

    var toolMode by remember {
        mutableStateOf(ToolMode.BRUSH)
    }

    val strokes =
        remember {
            mutableStateListOf<BrushStroke>()
        }

// Image edit history: one successful Delete = one history step.
    val undoHistory = remember {
        mutableStateListOf<Bitmap>()
    }

    val redoHistory = remember {
        mutableStateListOf<Bitmap>()
    }
    // Image-space points for the stroke currently being drawn.
    var liveStroke by remember {
        mutableStateOf<List<Offset>>(
            emptyList()
        )
    }

    var scale by remember {
        mutableFloatStateOf(1f)
    }

    var pan by remember {
        mutableStateOf(Offset.Zero)
    }

    var cursor by remember {
        mutableStateOf<Offset?>(null)
    }

    var cursorVisible by remember {
        mutableStateOf(false)
    }

    // True while a Delete (LaMa inference) is running. Only guards
    // against firing a second overlapping inference from a double tap
    // - it intentionally does not touch any other UI/canvas state.
    var isDeleting by remember {
        mutableStateOf(false)
    }


    fun resetEditorState() {
        strokes.clear()
        undoHistory.clear()
        redoHistory.clear()
        liveStroke = emptyList()
        scale = 1f
        pan = Offset.Zero
        cursor = null
        cursorVisible = false
        toolMode = ToolMode.BRUSH
    }


    // =====================================================================
    // DISCARD-ON-EXIT
    // ---------------------------------------------------------------------
    // Fully resets the editor AND drops the loaded bitmap, so nothing
    // from the previous editing session survives. This is what "YES" in
    // ConfirmDiscardDialog ultimately triggers, and it's the single
    // source of truth for "leave the editor" - both the in-app Back
    // arrow and the system back gesture route into it via EditorScreen.
    fun discardAndExitEditor() {
        bitmap = null
        resetEditorState()
    }


    // =====================================================================
    // PHOTO SELECTION -> EDITOR HANDOFF
    // ---------------------------------------------------------------------
    // The single place that turns a selected image Uri (from the photo
    // picker grid OR from the camera) into a loaded editor bitmap. This
    // is the "existing image-selection callback" the picker screen below
    // reuses - it decodes the Uri through decodeOrientedBitmap() (which
    // applies the photo's EXIF orientation exactly once, so the Bitmap
    // is already orientation-correct before anything else touches it),
    // resets the editor state exactly like the old GetContent flow did,
    // and closes the picker.
    // =====================================================================
    fun onImageUriSelected(uri: Uri) {

        coroutineScope.launch {

            val loaded =
                withContext(Dispatchers.IO) {
                    decodeOrientedBitmap(context, uri)
                }

            if (loaded != null) {
                bitmap = loaded
                resetEditorState()
            }

            showPhotoPicker = false
        }
    }


    // =====================================================================
    // DELETE -> LaMa BRIDGE
    // ---------------------------------------------------------------------
    // Converts the current active selection (the `strokes` list - the
    // single, shared selection every tool writes into) into the Bitmap
    // mask LamaInpainter expects, runs real inference off the main
    // thread, and replaces the edited bitmap with the result. Only the
    // strokes that were actually part of the selection at the moment
    // Delete was tapped are removed afterward - anything drawn while
    // inference was still running is left alone. redoStack is left
    // untouched: it only ever holds strokes the user previously undid,
    // which were never part of this consumed selection.
    // =====================================================================
    fun performDelete() {

        if (isDeleting) return

        val currentBitmap = bitmap ?: return

        if (strokes.isEmpty() && detectionMask == null) {
            Toast.makeText(
                context,
                "Select an object first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Snapshot exactly what's being consumed, before any suspension
        // point, so a stroke drawn mid-inference is never silently
        // dropped by the cleanup step below.
        val consumedStrokes = strokes.toList()

        isDeleting = true

        coroutineScope.launch {

            try {

                val maskBitmap =
                    detectionMask?.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )
                        ?: withContext(Dispatchers.Default) {
                            rasterizeSelectionMask(
                                width = currentBitmap.width,
                                height = currentBitmap.height,
                                strokes = consumedStrokes
                            )
                        }


                val inpainter =
                    LamaInpainter.getInstance(context)


                val result =
                    inpainter.inpaint(
                        image = currentBitmap,
                        maskBitmap
                    )

                maskBitmap.recycle()

                bitmap = result

                if (detectionMask != null) {
                    detectionMask?.recycle()
                    detectionMask = null
                }

                // Clear only the consumed selection - not the whole
                // list, and not redoStack.
                strokes.removeAll(consumedStrokes)

            } catch (e: Exception) {

                e.printStackTrace()

                android.util.Log.e(
                    "LamaDelete",
                    "OBJECT REMOVAL FAILED",
                    e
                )

                Toast.makeText(
                    context,
                    "Object removal failed",
                    Toast.LENGTH_SHORT
                ).show()

            } finally {

                isDeleting = false
            }
        }
    }


    MaterialTheme {

        // =====================================================================
        // TOP-LEVEL SCREEN SWITCH
        // ---------------------------------------------------------------------
        // Three independent, self-contained screens now. bitmap == null and
        // !showPhotoPicker routes to HomeScreen; bitmap == null and
        // showPhotoPicker routes to the new PhotoPickerScreen; bitmap != null
        // routes to EditorScreen (completely unchanged). Each screen owns its
        // own top bar / chrome, so changes to one can never leak into another.
        // =====================================================================

        if (bitmap == null) {

            if (showPhotoPicker) {

                PhotoPickerScreen(
                    onClose = {
                        showPhotoPicker = false
                    },
                    onImageSelected = { uri ->
                        onImageUriSelected(uri)
                    }
                )

            } else {

                HomeScreen(
                    onPickPhoto = {
                        showPhotoPicker = true
                    }
                )
            }

        } else {

            EditorScreen(

                bitmap = bitmap!!,

                brushSize = brushSize,
                onBrushSizeChange = { brushSize = it },

                toolMode = toolMode,
                onToolModeChange = { toolMode = it },

                strokes = strokes,
                liveStroke = liveStroke,

                scale = scale,
                pan = pan,

                cursor = cursor,
                cursorVisible = cursorVisible,

                canUndo = undoHistory.isNotEmpty(),
                canRedo = redoHistory.isNotEmpty(),
                canDeselect = strokes.isNotEmpty(),

                // EditorScreen no longer calls this directly on tap -
                // it now always routes through its own discard-confirm
                // dialog first. This lambda is only what actually runs
                // once the user confirms "YES".
                onBack = {
                    discardAndExitEditor()
                    showPhotoPicker = true
                },

                onSave = {

                    val bmp = bitmap

                    if (bmp != null) {

                        val ok =
                            saveMarkedImage(
                                context,
                                bmp,
                                strokes
                            )

                        Toast.makeText(
                            context,
                            if (ok) "Saved to gallery" else "Save failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },

                onDelete = {
                    performDelete()
                },

                onUndo = {
                    val current = bitmap

                    if (current != null && undoHistory.isNotEmpty()) {
                        redoHistory.add(
                            current.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )
                        )

                        bitmap = undoHistory.removeAt(
                            undoHistory.lastIndex
                        )

                        strokes.clear()
                    }
                },

                onRedo = {
                    val current = bitmap

                    if (current != null && redoHistory.isNotEmpty()) {
                        undoHistory.add(
                            current.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )
                        )

                        bitmap = redoHistory.removeAt(
                            redoHistory.lastIndex
                        )

                        strokes.clear()
                    }
                },

                onStrokeAdded = {
                    strokes.add(it)
                },

                onLiveStrokeChanged = {
                    liveStroke = it
                },

                onScaleChanged = {
                    scale = it
                },

                onPanChanged = {
                    pan = it
                },

                onCursorChanged = {
                    cursor = it
                },

                interactiveSegmenter = interactiveSegmenter,

                onDetectionMaskReady = { mask ->
                    detectionMask?.recycle()
                    detectionMask = mask
                },

                detectionMask = detectionMask

            )
        }
    }
}


// =========================================================================
// HOME SCREEN (empty image-selection screen)
// -------------------------------------------------------------------------
// Deliberately isolated in its own composable with its own layout root.
// It has NO back arrow and NO dependency on any editor state - it only
// needs a callback to kick off the photo picker. This is where future
// menu items (recent photos, templates, settings, etc.) get added; doing
// so here can never touch EditorScreen below.
// =========================================================================

@Composable
private fun HomeScreen(
    onPickPhoto: () -> Unit
) {

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        // Intentionally no top bar / back arrow on this screen.

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
        ) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    IconGlyph(
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    ) { drawImageGlyph(it) }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onPickPhoto,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = HomeAccentPurple,
                                contentColor = Color.White
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth(0.8f)
                                .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Select an image",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Future Home Screen menu items go in this Column, below or
        // above the picker Box, without touching EditorScreen at all.
    }
}


// =========================================================================
// EDITOR SCREEN (image editor screen)
// -------------------------------------------------------------------------
// Unchanged in behavior from before - keeps its own top bar with the
// Back arrow + Save action, the photo canvas, the primary Delete button,
// and the bottom brush/tool controls. Fully independent of HomeScreen
// and of the new PhotoPickerScreen.
//
// Leaving this screen - whether via the top-bar Back arrow or the
// system/gesture back button - routes through ConfirmDiscardDialog;
// onBack() only fires once the user taps "YES". Tapping "CANCEL" (or
// dismissing the dialog) leaves every bit of editing state exactly as
// it was.
// =========================================================================

@Composable
private fun EditorScreen(

    bitmap: Bitmap,

    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,

    toolMode: ToolMode,
    onToolModeChange: (ToolMode) -> Unit,

    strokes: List<BrushStroke>,
    liveStroke: List<Offset>,

    scale: Float,
    pan: Offset,

    cursor: Offset?,
    cursorVisible: Boolean,

    canUndo: Boolean,
    canRedo: Boolean,
    canDeselect: Boolean,

    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,

    onStrokeAdded: (BrushStroke) -> Unit,
    onLiveStrokeChanged: (List<Offset>) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onPanChanged: (Offset) -> Unit,
    onCursorChanged: (Offset?) -> Unit,

    interactiveSegmenter: InteractiveSegmenterHelper,

    onDetectionMaskReady: (Bitmap) -> Unit,

    detectionMask: Bitmap?,

) {

    // Controls visibility of ConfirmDiscardDialog. Local to this screen
    // since it's purely a "does the user want to leave" prompt - it has
    // no bearing on the actual editing state underneath it.
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Routes the system/gesture back button through the same
    // confirmation the in-app Back arrow uses below, instead of
    // exiting immediately.
    BackHandler(enabled = true) {
        showDiscardDialog = true
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        // Editor Screen keeps its Back arrow + Save top bar, always.
        // Tapping Back now only opens the confirmation dialog.
        TopBar(
            onBack = { showDiscardDialog = true },
            onSave = onSave
        )

        // =========================================================
        // CANVAS AREA BOUNDARY
        // ---------------------------------------------------------
        // clipToBounds() confines everything PhotoEditor draws to
        // exactly this Box's measured region - the space between
        // TopBar above and PrimaryDeleteButton/BottomControls below.
        // Without it, a zoomed-in image can be scaled/translated by
        // PhotoEditor's own transform math past this Box's edges and
        // still get painted (Compose does not clip a composable's
        // drawn content to its layout bounds by default), visually
        // bleeding under the fixed top/bottom UI even though those
        // controls' own layout bounds - and therefore their touch
        // targets - were never touched or resized.
        //
        // This is a pure drawing-boundary fix: it only affects what
        // is allowed to be *painted* here. It does not change this
        // Box's size, PhotoEditor's pointerInput touch coordinates,
        // or any of the scale/pan/screenToImage math inside
        // PhotoEditor, so brush alignment after zoom/pan is
        // unaffected.
        // =========================================================

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
        ) {

            PhotoEditor(

                bitmap = bitmap,
                brushSize = brushSize,
                toolMode = toolMode,
                strokes = strokes,
                liveStroke = liveStroke,
                scaleState = scale,
                panState = pan,
                cursor = cursor,
                cursorVisible = cursorVisible,

                onStrokeAdded = onStrokeAdded,
                onLiveStrokeChanged = onLiveStrokeChanged,
                onScaleChanged = onScaleChanged,
                onPanChanged = onPanChanged,
                onCursorChanged = onCursorChanged,

                        interactiveSegmenter = interactiveSegmenter,

                onDetectionMaskReady = onDetectionMaskReady,

                detectionMask = detectionMask
            )
        }

        // =========================================================
        // PRIMARY DELETE ACTION
        // ---------------------------------------------------------
        // Sits directly below the image/editing surface and above
        // the brush-size controls, horizontally centered and
        // clearly separated from the photo above it. Disabled
        // whenever there's nothing marked/selected yet, enabled
        // as soon as there's a valid selection (any strokes on
        // the canvas) to act on.
        // =========================================================

        PrimaryDeleteButton(
            enabled = strokes.isNotEmpty() || detectionMask != null,
            onClick = onDelete
        )

        BottomControls(

            brushSize = brushSize,
            onBrushSizeChange = onBrushSizeChange,

            toolMode = toolMode,
            onToolModeChange = onToolModeChange,

            canUndo = canUndo,
            canRedo = canRedo,
            canDeselect = canDeselect,

            onUndo = onUndo,
            onRedo = onRedo
        )
    }

    // Rendered last (outside the Column) so it draws on top of the
    // whole editor as an overlay, dimming everything behind it while
    // the current editing state stays untouched underneath.
    if (showDiscardDialog) {

        ConfirmDiscardDialog(
            onCancel = {
                showDiscardDialog = false
            },
            onConfirm = {
                showDiscardDialog = false
                onBack()
            }
        )
    }
}


/**
 * "Discard Changes?" confirmation shown whenever the user tries to
 * leave the editor - via the in-app Back arrow or the system/gesture
 * back button. Matches the app's existing style: white rounded card,
 * a warning glyph, bold title, message, and two right-aligned text
 * actions. [Dialog] already dims the background behind it.
 *
 * CANCEL just closes the dialog - the caller never invokes onBack,
 * so the current image and every stroke/undo/redo entry is left
 * completely alone.
 *
 * YES invokes onConfirm, which the caller wires to the real onBack -
 * that's what actually clears the bitmap and resets all editing
 * state (see discardAndExitEditor() in ObjectRemoverApp).
 */
@Composable
private fun ConfirmDiscardDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {

    Dialog(
        onDismissRequest = onCancel,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
    ) {

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier =
                Modifier
                    .wrapContentSize()
                    .padding(8.dp)
        ) {

            Column(
                modifier = Modifier.padding(20.dp)
            ) {

                IconGlyph(
                    modifier = Modifier.size(32.dp),
                    tint = Color(0xFFD32F2F)
                ) { drawWarningGlyph(it) }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Discard Changes?",
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Are you sure you want to go back?",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {

                    TextButton(onClick = onCancel) {
                        Text("CANCEL")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    TextButton(onClick = onConfirm) {
                        Text(
                            text = "YES",
                            color = Color(0xFFD32F2F)
                        )
                    }
                }
            }
        }
    }
}


/**
 * Editor Screen's top bar. Back opens the discard-confirmation dialog
 * (see [ConfirmDiscardDialog]) rather than leaving immediately; Save
 * exports the marked-up photo to the gallery. The primary Delete
 * action lives below the image (see [PrimaryDeleteButton]), not up
 * here. This composable is only ever used by [EditorScreen] - the
 * Home Screen never renders it.
 *
 * Back = painterResource(R.drawable.ic_back), Save =
 * painterResource(R.drawable.ic_save) - both render as icon-only
 * IconButtons now (no visible "Save" text); onBack/onSave callbacks
 * are unchanged from before.
 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    onSave: () -> Unit
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 4.dp,
                    vertical = 4.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        IconButton(onClick = onBack) {

            ToolIcon(
                painter = painterResource(R.drawable.ic_back),
                tint = Color.Black,
                modifier = Modifier.size(24.dp),
                contentDescription = "Back"
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onSave) {

            ToolIcon(
                painter = painterResource(R.drawable.ic_save),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
                contentDescription = "Save"
            )
        }
    }
}


/**
 * The primary DELETE action for the editor. Deliberately styled and
 * placed as its own row (not folded into the small tool-icon row
 * below) so it reads as *the* main action on the screen, not just
 * another utility button:
 *
 * - Horizontally centered, directly under the image surface.
 * - Visually separated from the photo above it (its own padded row)
 *   and from the brush-size controls below it.
 * - Disabled (dim, non-interactive) when there is no selection to
 *   act on; enabled (solid, full color) once a valid selection
 *   exists.
 *
 * Runs real LaMa inpainting (via performDelete()/LamaInpainter) against
 * the current active selection when tapped - it is no longer just a
 * local clear of marks.
 */
@Composable
private fun PrimaryDeleteButton(
    enabled: Boolean,
    onClick: () -> Unit
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 10.dp
                ),
        horizontalArrangement = Arrangement.Center
    ) {

        Button(
            onClick = onClick,
            enabled = enabled,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFD32F2F).copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
            modifier =
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(48.dp)
        ) {

            Text(
                text = "DELETE",
                fontSize = 16.sp
            )
        }
    }
}


/**
 * Bottom control area: the brush-size slider (with its live value)
 * directly above a row of six equally-weighted tool buttons - Brush,
 * Lasso, Detection, Deselect, Undo, Redo - matching the reference
 * layout. Each tool button's icon comes from painterResource() against
 * its corresponding drawable (ic_brush, ic_lasso, ic_scan, ic_eraser,
 * ic_undo, ic_redo); onClick/enabled/selected wiring is unchanged.
 */
@Composable
private fun BottomControls(

    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,

    toolMode: ToolMode,
    onToolModeChange: (ToolMode) -> Unit,

    canUndo: Boolean,
    canRedo: Boolean,
    canDeselect: Boolean,

    onUndo: () -> Unit,
    onRedo: () -> Unit
) {

    // Brush Size is only ever meaningful for BRUSH (its own size) and
    // DESELECT (fixed at DESELECT_BRUSH_SIZE, but the control itself
    // stays visually enabled per spec) - LASSO/DETECTION gray it out.
    val brushSizeEnabled =
        toolMode == ToolMode.BRUSH || toolMode == ToolMode.DESELECT

    val brushSizeTint =
        if (brushSizeEnabled) Color.DarkGray else Color.Gray.copy(alpha = 0.5f)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp
                )
    ) {

        // ---- Brush size slider, moved down here from the top ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {

            ToolIcon(
                painter = painterResource(R.drawable.ic_brush),
                tint = brushSizeTint,
                modifier = Modifier.size(18.dp),
                contentDescription = null
            )


            // steps intentionally omitted (was 98) - a nonzero `steps`
            // value is what makes Material3's Slider render a tick
            // mark at every discrete stop, which is the "dots/bubbles
            // along the track" look. Leaving `steps` unset renders the
            // normal smooth continuous track/thumb instead. Range,
            // value, onValueChange, and everything downstream that
            // reads `brushSize` (including the rounded `.toInt()`
            // label above) are unaffected.
            Slider(
                value = brushSize,
                onValueChange = onBrushSizeChange,
                valueRange = 1f..100f,
                enabled = brushSizeEnabled,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
            )


            Text(
                text = brushSize.toInt().toString(),
                color = brushSizeTint,
                modifier = Modifier.width(32.dp)
            )
        }


        Spacer(modifier = Modifier.height(4.dp))


        // ---- Tool row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {

            ToolButton(
                icon = painterResource(R.drawable.ic_brush),
                label = "Brush",
                selected = toolMode == ToolMode.BRUSH,
                enabled = true,
                onClick = { onToolModeChange(ToolMode.BRUSH) }
            )

            ToolButton(
                icon = painterResource(R.drawable.ic_lasso),
                label = "Lasso",
                selected = toolMode == ToolMode.LASSO,
                enabled = true,
                onClick = { onToolModeChange(ToolMode.LASSO) }
            )

            ToolButton(
                icon = painterResource(R.drawable.ic_scan),
                label = "Selection",
                selected = toolMode == ToolMode.DETECTION,
                enabled = true,
                onClick = { onToolModeChange(ToolMode.DETECTION) }
            )

            ToolButton(
                icon = painterResource(R.drawable.ic_eraser),
                label = "Erase",
                selected = toolMode == ToolMode.DESELECT,
                enabled = canDeselect,
                onClick = { onToolModeChange(ToolMode.DESELECT) }
            )

            ToolButton(
                icon = painterResource(R.drawable.ic_undo),
                label = "Undo",
                selected = false,
                enabled = canUndo,
                onClick = onUndo
            )

            ToolButton(
                icon = painterResource(R.drawable.ic_redo),
                label = "Redo",
                selected = false,
                enabled = canRedo,
                onClick = onRedo
            )
        }
    }
}


@Composable
private fun ToolButton(
    icon: Painter,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {

    val tint =
        when {
            !enabled -> Color.Gray.copy(alpha = 0.4f)
            selected -> Color.Red
            else -> Color.DarkGray
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.padding(horizontal = 2.dp)
    ) {

        IconButton(
            onClick = onClick,
            enabled = enabled
        ) {

            ToolIcon(
                painter = icon,
                tint = tint,
                modifier = Modifier.size(24.dp),
                contentDescription = label
            )
        }

        Text(
            text = label,
            fontSize = 11.sp,
            color = tint
        )
    }
}


@Composable
fun PhotoEditor(

    bitmap: Bitmap,

    brushSize: Float,

    toolMode: ToolMode,

    strokes: List<BrushStroke>,

    liveStroke: List<Offset>, // image space

    scaleState: Float,

    panState: Offset,

    cursor: Offset?, // screen space (transient, fine as-is)

    cursorVisible: Boolean,

    onStrokeAdded:
        (BrushStroke) -> Unit,

    onLiveStrokeChanged:
        (List<Offset>) -> Unit,

    onScaleChanged:
        (Float) -> Unit,

    onPanChanged:
        (Offset) -> Unit,

    onCursorChanged:
        (Offset?) -> Unit,

    interactiveSegmenter: InteractiveSegmenterHelper,

    onDetectionMaskReady:
        (Bitmap) -> Unit,

    detectionMask: Bitmap?,

) {

    val currentScale =
        rememberUpdatedState(
            scaleState
        )

    val currentPan =
        rememberUpdatedState(
            panState
        )

    val currentBrushSize =
        rememberUpdatedState(
            brushSize
        )

    val currentBitmap =
        rememberUpdatedState(
            bitmap
        )

    val currentToolMode =
        rememberUpdatedState(
            toolMode
        )


    Canvas(

        modifier =
            Modifier
                .fillMaxSize()

                .pointerInput(bitmap) {

                    awaitEachGesture {

                        var activePointerId:
                                PointerId? = null

                        var multiTouch =
                            false

                        var lastCentroid =
                            Offset.Zero

                        var lastDistance =
                            0f


                        // PAN/ZOOM LIVE STATE - tracked locally for the
                        // duration of the gesture. currentScale.value /
                        // currentPan.value only refresh once PhotoEditor
                        // recomposes with new parameters from the
                        // parent, which happens asynchronously relative
                        // to this coroutine; using a local running value
                        // makes this gesture the single source of truth
                        // for its own duration - the same fix already
                        // applied to brush strokes below.
                        var localScale =
                            currentScale.value

                        var localPan =
                            currentPan.value


                        // BRUSH/LASSO/DETECTION LIVE - stored in IMAGE
                        // space from the first point onward.
                        // lastScreenPoint is kept separately just to
                        // measure on-screen distance for interpolation
                        // spacing.
                        var currentLiveStroke =
                            emptyList<Offset>()

                        var lastScreenPoint:
                                Offset? = null

                        // The tool this gesture started with - locked
                        // in on touch-down so a tool switch mid-stroke
                        // (shouldn't normally happen, but just in case)
                        // can't change behavior partway through.
                        var gestureTool =
                            currentToolMode.value


                        while (true) {

                            val event =
                                awaitPointerEvent()


                            val pressed =
                                event.changes
                                    .filter {
                                        it.pressed
                                    }


                            // =================================================
                            // 2 FINGER = ZOOM + PAN
                            // =================================================

                            if (
                                pressed.size >= 2
                            ) {

                                if (!multiTouch) {

                                    multiTouch =
                                        true

                                    activePointerId =
                                        null

                                    currentLiveStroke =
                                        emptyList()

                                    lastScreenPoint =
                                        null

                                    onLiveStrokeChanged(
                                        emptyList()
                                    )

                                    onCursorChanged(
                                        null
                                    )


                                    val p1 =
                                        pressed[0].position

                                    val p2 =
                                        pressed[1].position


                                    lastCentroid =
                                        Offset(
                                            (p1.x + p2.x) / 2f,
                                            (p1.y + p2.y) / 2f
                                        )


                                    lastDistance =
                                        (p1 - p2).getDistance()
                                }


                                val p1 =
                                    pressed[0].position

                                val p2 =
                                    pressed[1].position


                                val centroid =
                                    Offset(
                                        (p1.x + p2.x) / 2f,
                                        (p1.y + p2.y) / 2f
                                    )


                                val distance =
                                    (p1 - p2).getDistance()


                                if (
                                    lastDistance > 0f &&
                                    distance > 0f
                                ) {

                                    val oldScale =
                                        localScale

                                    val oldPan =
                                        localPan


                                    val rawZoomFactor =
                                        distance / lastDistance

                                    // Ignore sub-1% changes so only a
                                    // deliberate pinch counts as zoom -
                                    // otherwise hand tremor during a
                                    // pure drag would silently unlock
                                    // panning.
                                    val zoomFactor =
                                        if (
                                            kotlin.math.abs(
                                                rawZoomFactor - 1f
                                            ) < 0.01f
                                        ) {
                                            1f
                                        } else {
                                            rawZoomFactor
                                        }


                                    val newScale =
                                        (oldScale * zoomFactor)
                                            .coerceIn(1f, 8f)


                                    val actualZoom =
                                        newScale / oldScale


                                    val center =
                                        Offset(
                                            size.width / 2f,
                                            size.height / 2f
                                        )


                                    val focalPoint =
                                        centroid - center - oldPan


                                    val zoomCorrection =
                                        focalPoint * (1f - actualZoom)


                                    val panMovement =
                                        centroid - lastCentroid


                                    val newPan =
                                        oldPan + panMovement + zoomCorrection


                                    val clampedPan =
                                        clampPan(
                                            pan = newPan,
                                            scale = newScale,
                                            canvasWidth = size.width.toFloat(),
                                            canvasHeight = size.height.toFloat(),
                                            bitmap = currentBitmap.value,
                                            totalScale =
                                                totalScaleFor(
                                                    size,
                                                    currentBitmap.value,
                                                    newScale
                                                )
                                        )


                                    localScale = newScale
                                    localPan = clampedPan

                                    onScaleChanged(newScale)
                                    onPanChanged(clampedPan)
                                }


                                lastCentroid = centroid
                                lastDistance = distance


                                for (change in pressed) {
                                    change.consume()
                                }
                            }


                            // =================================================
                            // 1 FINGER = BRUSH / LASSO / DETECTION
                            //
                            // Every point is converted to image space the
                            // instant it's captured, via the same
                            // screenToImage() helper used everywhere else -
                            // one conversion path, used continuously.
                            // =================================================

                            else if (
                                pressed.size == 1 &&
                                !multiTouch
                            ) {

                                val change =
                                    pressed[0]


                                if (
                                    activePointerId == null
                                ) {

                                    activePointerId = change.id

                                    gestureTool = currentToolMode.value

                                    currentLiveStroke = emptyList()

                                    lastScreenPoint = null

                                    onLiveStrokeChanged(emptyList())
                                }


                                if (
                                    change.id == activePointerId
                                ) {

                                    val current =
                                        change.position

                                    val brush =
                                        if (gestureTool == ToolMode.DESELECT) {
                                            DESELECT_BRUSH_SIZE
                                        } else {
                                            currentBrushSize.value
                                        }

                                    val bmp =
                                        currentBitmap.value

                                    val scaleNow =
                                        localScale

                                    val panNow =
                                        localPan

                                    // Captured once here, by name, so it
                                    // can't get shadowed by List.size
                                    // inside the buildList block below.
                                    val canvasSize =
                                        size


                                    val previousScreen =
                                        lastScreenPoint


                                    if (
                                        previousScreen == null
                                    ) {

                                        // First point of the gesture.
                                        currentLiveStroke =
                                            listOf(
                                                screenToImage(
                                                    current,
                                                    canvasSize,
                                                    bmp,
                                                    scaleNow,
                                                    panNow
                                                )
                                            )

                                    } else {

                                        val distance =
                                            (current - previousScreen)
                                                .getDistance()


                                        if (distance > 0f) {

                                            // Spacing/steps computed in
                                            // screen space so brush
                                            // density on screen stays
                                            // consistent regardless of
                                            // current zoom level.
                                            val spacing =
                                                (brush * 0.08f)
                                                    .coerceAtLeast(1f)


                                            val steps =
                                                ceil(distance / spacing)
                                                    .toInt()
                                                    .coerceAtLeast(1)


                                            val newPoints =
                                                buildList {

                                                    for (i in 1..steps) {

                                                        val t =
                                                            i.toFloat() / steps


                                                        val screenPoint =
                                                            previousScreen +
                                                                    (current - previousScreen) * t


                                                        add(
                                                            screenToImage(
                                                                screenPoint,
                                                                canvasSize,
                                                                bmp,
                                                                scaleNow,
                                                                panNow
                                                            )
                                                        )
                                                    }
                                                }


                                            currentLiveStroke =
                                                currentLiveStroke + newPoints
                                        }
                                    }


                                    lastScreenPoint = current

                                    onLiveStrokeChanged(currentLiveStroke)

                                    onCursorChanged(current)

                                    change.consume()
                                }
                            }


                            // =================================================
                            // NO POINTER - RELEASE
                            //
                            // currentLiveStroke is already in image
                            // space, so committing it is just handing
                            // the list over as-is - no conversion here,
                            // which is what removes the jump-on-release
                            // bug. LASSO additionally closes its loop
                            // by appending the first point again.
                            // =================================================

                            else if (
                                pressed.isEmpty()
                            ) {

                                if (
                                    !multiTouch &&
                                    currentLiveStroke.isNotEmpty()
                                ) {

                                    if (gestureTool == ToolMode.DETECTION) {

                                        android.util.Log.d(
                                            "Detection",
                                            "Detection tap triggered"
                                        )

                                        val tapPoint =
                                            currentLiveStroke.first()

                                        val normalizedX =
                                            (tapPoint.x / bitmap.width.toFloat())
                                                .coerceIn(0f, 1f)

                                        val normalizedY =
                                            (tapPoint.y / bitmap.height.toFloat())
                                                .coerceIn(0f, 1f)

                                        try {

                                            val mask =
                                                interactiveSegmenter.createMaskAt(
                                                    normalizedX,
                                                    normalizedY
                                                )

                                            onDetectionMaskReady(mask)

                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }

                                    }

                                    var finalPoints =
                                        currentLiveStroke

                                    if (
                                        gestureTool == ToolMode.LASSO &&
                                        finalPoints.size >= 2
                                    ) {

                                        finalPoints =
                                            finalPoints + finalPoints.first()
                                    }


                                    val totalScale =
                                        totalScaleFor(
                                            size, // PointerInputScope.size
                                            currentBitmap.value,
                                            localScale
                                        )


                                    val effectiveBrushSize =
                                        if (gestureTool == ToolMode.DESELECT) {
                                            DESELECT_BRUSH_SIZE
                                        } else {
                                            currentBrushSize.value
                                        }

                                    if (gestureTool != ToolMode.DETECTION) {
                                        onStrokeAdded(
                                            BrushStroke(
                                                points = finalPoints,
                                                width =
                                                    effectiveBrushSize / totalScale,
                                                tool = gestureTool
                                            )
                                        )
                                    }
                                }


                                onLiveStrokeChanged(emptyList())

                                onCursorChanged(null)

                                break
                            }
                        }
                    }
                }

    ) {

        // =============================================================
        // IMAGE TRANSFORM
        // =============================================================

        val center =
            Offset(
                size.width / 2f,
                size.height / 2f
            )


        val baseScale =
            baseScaleFor(size.width, size.height, bitmap)


        val totalScale =
            baseScale * scaleState


        // =============================================================
        // PHOTO + ALL STROKES (saved and live) - same transform, same
        // pass, so they can never disagree with each other.
        // =============================================================

        withTransform({

            translate(
                left = center.x + panState.x,
                top = center.y + panState.y
            )


            scale(
                scaleX = totalScale,
                scaleY = totalScale,
                pivot = Offset.Zero
            )


            translate(
                left = -bitmap.width / 2f,
                top = -bitmap.height / 2f
            )

        }) {

            drawImage(
                bitmap.asImageBitmap()
            )

            detectionMask?.let { mask ->
                drawImage(
                    image = mask.asImageBitmap(),
                    dstSize = IntSize(
                        bitmap.width,
                        bitmap.height
                    ),
                    alpha = 0.35f
                )
            }


            // =========================================================
            // Clip drawing to exactly the bitmap's rect (image space)
            // so nothing paints into the letterboxed margin around a
            // non-matching-aspect-ratio photo.
            // =========================================================

            clipRect(
                left = 0f,
                top = 0f,
                right = bitmap.width.toFloat(),
                bottom = bitmap.height.toFloat()
            ) {

                // -----------------------------------------------------
                // Isolate every mask stroke (BRUSH/LASSO/DETECTION/
                // DESELECT) into its own offscreen layer before it
                // gets composited onto the photo. This is what lets
                // DESELECT strokes - drawn with BlendMode.Clear -
                // punch real transparent holes out of the *mask*
                // painted so far, without ever touching the photo:
                // the photo was already drawn above, outside this
                // layer entirely, so Clear has nothing of the photo
                // left to erase.
                // -----------------------------------------------------

                drawContext.canvas.saveLayer(
                    Rect(
                        Offset.Zero,
                        Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                    ),
                    Paint()
                )

                for (stroke in strokes) {

                    drawStrokePath(
                        points = stroke.points,
                        width = stroke.width,
                        tool = stroke.tool,
                        closed = stroke.tool == ToolMode.LASSO
                    )
                }


                if (liveStroke.isNotEmpty()) {

                    drawStrokePath(
                        points = liveStroke,
                        width =
                            (if (toolMode == ToolMode.DESELECT) {
                                DESELECT_BRUSH_SIZE
                            } else {
                                brushSize
                            }) / totalScale,
                        tool = toolMode,
                        closed = false
                    )
                }

                drawContext.canvas.restore()
            }
        }


        // =============================================================
        // CURSOR - stays in raw screen space; it's purely a transient
        // UI indicator that always tracks the finger 1:1.
        // =============================================================

        val currentCursor =
            cursor

        if (
            cursorVisible &&
            currentCursor != null
        ) {

            drawCircle(
                color = composeColorFor(toolMode),
                center = currentCursor,
                radius =
                    (if (toolMode == ToolMode.DESELECT) {
                        DESELECT_BRUSH_SIZE
                    } else {
                        brushSize
                    }) / 2f,
                style = Stroke(width = 2f)
            )
        }

    // =============================================================
    // MAGNIFIER
    // Shows a circular zoomed preview above the finger while the
    // user is drawing with Brush, Lasso, or Erase.
    // =============================================================

        if (
            currentCursor != null &&
            toolMode != ToolMode.DETECTION
        ) {

            val finger =
                currentCursor

            val canvasSize =
                IntSize(
                    size.width.toInt(),
                    size.height.toInt()
                )

            val imagePoint =
                screenToImage(
                    screenPoint = finger,
                    canvasSize = canvasSize,
                    bitmap = bitmap,
                    scale = scaleState,
                    pan = panState
                )

            val lensRadius = 55f
            val lensCenter =
                Offset(
                    finger.x,
                    (finger.y - lensRadius * 2.2f)
                        .coerceAtLeast(lensRadius + 4f)
                )

            val zoom = 2.5f

            val sourceWidth =
                (lensRadius * 2f / zoom / totalScale)
                    .coerceAtLeast(1f)

            val sourceHeight =
                sourceWidth

            val srcLeft =
                (
                        imagePoint.x -
                                sourceWidth / 2f
                        )
                    .roundToInt()
                    .coerceIn(
                        0,
                        bitmap.width - 1
                    )

            val srcTop =
                (
                        imagePoint.y -
                                sourceHeight / 2f
                        )
                    .roundToInt()
                    .coerceIn(
                        0,
                        bitmap.height - 1
                    )

            val srcRight =
                (
                        srcLeft +
                                sourceWidth.roundToInt()
                        )
                    .coerceAtMost(bitmap.width)

            val srcBottom =
                (
                        srcTop +
                                sourceHeight.roundToInt()
                        )
                    .coerceAtMost(bitmap.height)

            val srcWidth =
                (srcRight - srcLeft)
                    .coerceAtLeast(1)

            val srcHeight =
                (srcBottom - srcTop)
                    .coerceAtLeast(1)

            val lensPath =
                Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            lensCenter.x - lensRadius,
                            lensCenter.y - lensRadius,
                            lensCenter.x + lensRadius,
                            lensCenter.y + lensRadius
                        )
                    )
                }

            clipPath(lensPath) {

                drawImage(
                    image = bitmap.asImageBitmap(),
                    srcOffset = IntOffset(
                        srcLeft,
                        srcTop
                    ),
                    srcSize = IntSize(
                        srcWidth,
                        srcHeight
                    ),
                    dstOffset = IntOffset(
                        (lensCenter.x - lensRadius)
                            .roundToInt(),
                        (lensCenter.y - lensRadius)
                            .roundToInt()
                    ),
                    dstSize = IntSize(
                        (lensRadius * 2f)
                            .roundToInt(),
                        (lensRadius * 2f)
                            .roundToInt()
                    )
                )
            }

            drawCircle(
                color = Color.White,
                center = lensCenter,
                radius = lensRadius,
                style = Stroke(
                    width = 3f
                )
            )

            // Center crosshair.
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(
                    lensCenter.x - 8f,
                    lensCenter.y
                ),
                end = Offset(
                    lensCenter.x + 8f,
                    lensCenter.y
                ),
                strokeWidth = 1.5f
            )

            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(
                    lensCenter.x,
                    lensCenter.y - 8f
                ),
                end = Offset(
                    lensCenter.x,
                    lensCenter.y + 8f
                ),
                strokeWidth = 1.5f
            )
        }

    }

}


// =============================================================
// STROKE RENDERER
// =============================================================

private fun DrawScope.drawStrokePath(
    points: List<Offset>,
    width: Float,
    tool: ToolMode,
    closed: Boolean
) {

    if (points.isEmpty()) {
        return
    }

    val erase =
        tool == ToolMode.DESELECT

    // BlendMode.Clear ignores the source color/alpha entirely - it
    // just zeroes out whatever it covers in the current layer (see
    // the saveLayer() wrapping this call in PhotoEditor) - so the
    // color passed for DESELECT is arbitrary.
    val blendMode =
        if (erase) BlendMode.Clear else BlendMode.SrcOver

    // The actual paint color: BRUSH/DETECTION use a subtle,
    // semi-transparent tint so the photo stays clearly visible
    // underneath the selection. LASSO's alpha is applied per-element
    // below (fill vs. border need different strengths). DESELECT's
    // color is irrelevant (see blendMode above).
    val paintColor =
        when (tool) {
            ToolMode.BRUSH -> composeColorFor(tool).copy(alpha = 0.35f)
            ToolMode.DETECTION -> composeColorFor(tool).copy(alpha = 0.35f)
            ToolMode.LASSO -> composeColorFor(tool)
            ToolMode.DESELECT -> Color.Black
        }


    // Single point: a filled dot for every tool. DETECTION no longer
    // renders a crosshair marker - it now reads as a subtle painted
    // selection, exactly like BRUSH, just in white.
    if (points.size == 1) {

        val p = points[0]

        drawCircle(
            color = paintColor,
            center = p,
            radius = width / 2f,
            blendMode = blendMode
        )

        return
    }


    val path =
        Path()

    path.moveTo(points[0].x, points[0].y)

    var i = 1

    while (i < points.size) {
        path.lineTo(points[i].x, points[i].y)
        i++
    }

    if (closed) {
        path.close()
    }


    if (tool == ToolMode.LASSO) {

        // Subtle translucent red fill so the enclosed area reads as a
        // "selection" while the photo stays visible, plus a dashed
        // red border that's clearly visible but not too thick/opaque.
        drawPath(
            path = path,
            color = paintColor.copy(alpha = 0.18f)
        )

        drawPath(
            path = path,
            color = paintColor.copy(alpha = 0.6f),
            style =
                Stroke(
                    width = width.coerceAtMost(10f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect =
                        PathEffect.dashPathEffect(
                            floatArrayOf(32f, 20f)
                        )
                )
        )

    } else {

        drawPath(
            path = path,
            color = paintColor,
            style =
                Stroke(
                    width = width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
            blendMode = blendMode
        )
    }
}

// =========================================================================
// PHOTO PICKER SCREEN
// -------------------------------------------------------------------------
// A self-contained, gallery-style photo picker with two levels:
//
//   1. ALBUM LIST (the initial screen): a "Camera" action at the top,
//      followed by every real album/folder from the device's media
//      library - thumbnail, real name, real photo count, in the same
//      order the device gallery would show them (most-recently-used
//      album first).
//   2. ALBUM PHOTOS: tapping an album shows only that album's photos
//      in a grid, with the top-bar title switched to that album's
//      name.
//
// There is no "Gallery" tile and no "▼" system-picker shortcut - this
// screen IS the picker. Its only interactions with the rest of the app
// are onImageSelected(uri) (tapping a photo or finishing a camera
// capture) and onClose() (leaving the picker entirely, back to
// HomeScreen). Back navigation is nested: from ALBUM PHOTOS, back (the
// X button, or the system/gesture back action) returns to the ALBUM
// LIST; from the ALBUM LIST, back calls onClose() and returns to
// HomeScreen - it never exits the app.
// =========================================================================

/**
 * One MediaStore image row: just enough to build a content Uri
 * (ContentUris.withAppendedId) and show a thumbnail.
 */
private data class MediaImage(
    val id: Long,
    val uri: Uri
)


/**
 * One real album/folder from the device media library: its MediaStore
 * bucket id (used to filter photos down to just this album), its real
 * display name, a thumbnail Uri (the album's most recently added
 * photo), and its real photo count.
 */
private data class MediaAlbum(
    val bucketId: Long,
    val name: String,
    val thumbnailUri: Uri,
    val count: Int
)


/**
 * Thin wrapper around the MediaStore.Images collection. Every query
 * here reads real device data through the standard
 * ContentResolver/MediaStore API - no fake or placeholder entries.
 */
private object MediaStoreHelper {

    /**
     * All images, or only the images in [bucketId] when non-null
     * ("All Photos" is represented by bucketId == null upstream).
     */
    fun queryImages(context: Context, bucketId: Long?): List<MediaImage> {

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID
        )

        val selection =
            if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID} = ?" else null

        val selectionArgs =
            if (bucketId != null) arrayOf(bucketId.toString()) else null

        val sortOrder =
            "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val images = mutableListOf<MediaImage>()

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                while (cursor.moveToNext()) {

                    val id = cursor.getLong(idColumn)

                    val uri =
                        ContentUris.withAppendedId(collection, id)

                    images.add(MediaImage(id, uri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return images
    }

    /**
     * Every real album/folder the device media library exposes,
     * derived from a single DATE_ADDED-descending pass over all
     * images: each bucket's thumbnail is its first (i.e. most recent)
     * photo, its count is every row seen for that bucket, and the
     * album order is the order buckets first appear in that
     * descending-by-date scan - the same "most recently used album
     * first" order a device gallery app shows, and never reordered
     * afterward.
     */
    fun queryAlbums(context: Context): List<MediaAlbum> {

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder =
            "${MediaStore.Images.Media.DATE_ADDED} DESC"

        // LinkedHashMap preserves first-seen (= most recent) insertion
        // order per bucket, which is exactly the album ordering we
        // want to keep.
        data class Accum(
            val bucketId: Long,
            var name: String,
            var thumbnailUri: Uri,
            var count: Int
        )

        val buckets = LinkedHashMap<Long, Accum>()

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                val bucketIdColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)

                val bucketNameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {

                    val bucketId = cursor.getLong(bucketIdColumn)

                    val existing = buckets[bucketId]

                    if (existing == null) {

                        val id = cursor.getLong(idColumn)

                        val thumbnailUri =
                            ContentUris.withAppendedId(collection, id)

                        val name =
                            cursor.getString(bucketNameColumn) ?: "Unknown"

                        buckets[bucketId] =
                            Accum(
                                bucketId = bucketId,
                                name = name,
                                thumbnailUri = thumbnailUri,
                                count = 1
                            )

                    } else {

                        existing.count += 1
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return buckets.values.map { acc ->
            MediaAlbum(
                bucketId = acc.bucketId,
                name = acc.name,
                thumbnailUri = acc.thumbnailUri,
                count = acc.count
            )
        }
    }
}


/**
 * Loads a small thumbnail Bitmap for [uri] off the main thread and
 * exposes it as Compose state. Uses ContentResolver.loadThumbnail on
 * API 29+ (fast, uses MediaStore's cached thumbnail) and falls back to
 * a downsampled BitmapFactory decode on older API levels.
 */
@Composable
private fun rememberImageThumbnail(context: Context, uri: Uri): State<ImageBitmap?> {

    return produceState<ImageBitmap?>(initialValue = null, key1 = uri) {

        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    context.contentResolver
                        .loadThumbnail(uri, android.util.Size(300, 300), null)
                        .asImageBitmap()

                } else {

                    context.contentResolver.openInputStream(uri)?.use { input ->

                        val options =
                            BitmapFactory.Options().apply {
                                inSampleSize = 4
                            }

                        BitmapFactory
                            .decodeStream(input, null, options)
                            ?.asImageBitmap()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}


/**
 * Top-level entry point for the picker.
 *
 * Requests the native runtime read-media permission the first time
 * this screen appears (i.e. the first time the user taps "Select an
 * image" after a fresh install, or whenever Android would otherwise
 * re-prompt for media access) - never before that, and never a custom
 * permission UI: [ActivityResultContracts.RequestMultiplePermissions]
 * triggers the real system dialog(s), with system-controlled,
 * system-localized wording ("Allow limited access" / "Allow all" /
 * "Don't allow", or the equivalent for the device's Android version).
 * If the user denies it, this screen is simply left with no
 * albums/thumbnails to show - there is no repeated re-prompt, no fake
 * permission screen, and the rest of the app's navigation is
 * untouched.
 *
 * Shows two nested levels - the album list, then a single album's
 * photos - entirely within this one composable; see the top-of-file
 * comment for the back-navigation contract between them.
 */
@Composable
fun PhotoPickerScreen(
    onClose: () -> Unit,
    onImageSelected: (Uri) -> Unit
) {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val interactiveSegmenter =
        remember {
            InteractiveSegmenterHelper(context)
        }

    DisposableEffect(Unit) {
        onDispose {
            interactiveSegmenter.close()
        }
    }

    // Which permission(s) actually grant photo access depends on API
    // level:
    // - 34+ (Android 14 "Selected Photos Access"): either
    //   READ_MEDIA_IMAGES (full access) OR
    //   READ_MEDIA_VISUAL_USER_SELECTED (user picked "Select photos")
    //   is enough for MediaStore to return whatever the OS is
    //   currently willing to expose - the queries below already only
    //   ever see what's actually granted.
    // - 33 (Android 13): READ_MEDIA_IMAGES only.
    // - <=32: READ_EXTERNAL_STORAGE.
    val photoPermissions =
        remember {
            when {

                Build.VERSION.SDK_INT >= 34 ->

                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->

                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES
                    )

                else ->

                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    )
            }
        }

    fun hasAnyPhotoPermission(): Boolean =
        photoPermissions.any {
            ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    var hasPermission by remember {
        mutableStateOf(hasAnyPhotoPermission())
    }

    // True once the launcher has actually returned a result for this
    // screen-visit. Distinguishes "haven't asked yet" (still waiting
    // on the system dialog) from "asked and the OS said no" (either
    // the user tapped Don't Allow, or - just as commonly during
    // testing - the OS silently auto-denies because this permission
    // was already permanently denied on a previous run and it has
    // stopped showing its own dialog at all). Only the second case
    // shows the Settings fallback below.
    var permissionRequestFinished by remember {
        mutableStateOf(hasPermission)
    }

    // Fires the native system permission dialog(s) exactly once per
    // screen entry (i.e. once per "Select an image" tap while access
    // isn't already granted). No custom "Grant access" screen - if
    // the OS itself declines to show its dialog again (permanent
    // prior denial), permissionRequestFinished still flips to true
    // and the real Android Settings screen fallback below is the only
    // extra UI shown.
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            hasPermission = hasAnyPhotoPermission()
            permissionRequestFinished = true
        }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(photoPermissions)
        }
    }

    // null = showing the top-level album list; non-null = showing that
    // album's photos, with the top bar title switched to its name.
    var selectedAlbum by remember {
        mutableStateOf<MediaAlbum?>(null)
    }

    var albums by remember {
        mutableStateOf<List<MediaAlbum>>(emptyList())
    }

    var isLoadingAlbums by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoadingAlbums = true
            albums = withContext(Dispatchers.IO) {
                MediaStoreHelper.queryAlbums(context)
            }
            isLoadingAlbums = false
        }
    }

    var albumPhotos by remember {
        mutableStateOf<List<MediaImage>>(emptyList())
    }

    var isLoadingAlbumPhotos by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(selectedAlbum) {

        val album = selectedAlbum

        if (album != null) {
            isLoadingAlbumPhotos = true
            albumPhotos = withContext(Dispatchers.IO) {
                MediaStoreHelper.queryImages(context, bucketId = album.bucketId)
            }
            isLoadingAlbumPhotos = false
        }
    }

    // Leaving the album-photos view (without picking a photo) returns
    // to the album list; leaving the album list returns to HomeScreen
    // via onClose() - it never exits the app. Both the top-bar X
    // button and the system/gesture back action share this exact
    // logic (see goBack() below and PhotoPickerTopBar's onClose).
    fun goBack() {
        if (selectedAlbum != null) {
            selectedAlbum = null
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) {
        goBack()
    }

    // ---- Camera capture ----
    // Inserts a new pending row into MediaStore first (so we have a
    // content Uri to hand the camera app), then launches the standard
    // TakePicture contract. On success the freshly captured photo's
    // Uri is handed straight to onImageSelected, exactly like tapping
    // a grid thumbnail - so it goes through the exact same
    // decodeOrientedBitmap() EXIF-correction path in
    // ObjectRemoverApp.onImageUriSelected() that gallery photos do. On
    // failure/cancel, the empty row is cleaned up.
    var pendingCameraUri by remember {
        mutableStateOf<Uri?>(null)
    }

    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->

            val uri = pendingCameraUri

            if (success && uri != null) {
                onImageSelected(uri)
            } else if (uri != null) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            pendingCameraUri = null
        }

    // Actually inserts the pending MediaStore row and launches the
    // camera app. Only ever called once CAMERA is confirmed granted -
    // see launchCamera() / cameraPermissionLauncher below.
    fun insertAndLaunchCamera() {

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }

        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

        if (uri != null) {
            pendingCameraUri = uri
            suppressReturnTransition(context)
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Couldn't open camera", Toast.LENGTH_SHORT).show()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // Fires the real system CAMERA permission dialog the first time
    // the user taps "Camera" without it already granted (and again on
    // any later tap if they'd previously denied it, exactly like the
    // photo-permission flow above). insertAndLaunchCamera() only ever
    // runs once the OS reports the permission as actually granted.
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasCameraPermission = granted
            if (granted) {
                insertAndLaunchCamera()
            }
        }

    fun launchCamera() {
        if (hasCameraPermission) {
            insertAndLaunchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.White)
    ) {

        PhotoPickerTopBar(
            title = selectedAlbum?.name ?: "All Photos",
            onClose = { goBack() }
        )

        Box(modifier = Modifier.fillMaxSize()) {

            val album = selectedAlbum

            when {

                album == null -> {

                    // ---- ALBUM LIST (top level) ----
                    when {

                        !hasPermission && permissionRequestFinished -> {

                            // The permission request came back denied -
                            // either the user tapped Don't Allow, or
                            // (very common while testing) the OS is
                            // silently auto-denying because this
                            // permission was already permanently denied
                            // on an earlier run and it has stopped
                            // showing its own dialog. Either way, the
                            // system dialog will not reappear on its
                            // own, so this points at the real Android
                            // "App info" settings screen - not a custom
                            // permission dialog.
                            PermissionDeniedState(
                                onOpenSettings = {
                                    val intent =
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        )
                                    suppressReturnTransition(context)
                                    context.startActivity(intent)
                                }
                            )
                        }

                        isLoadingAlbums && albums.isEmpty() -> {

                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = HomeAccentPurple)
                            }
                        }

                        else -> {

                            AlbumListScreen(
                                albums = albums,
                                onCameraClick = { launchCamera() },
                                onAlbumClick = { selectedAlbum = it }
                            )
                        }
                    }
                }

                else -> {

                    // ---- ALBUM PHOTOS ----
                    if (isLoadingAlbumPhotos && albumPhotos.isEmpty()) {

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = HomeAccentPurple)
                        }

                    } else {

                        AlbumPhotosGrid(
                            images = albumPhotos,
                            onImageClick = { onImageSelected(it.uri) }
                        )
                    }
                }
            }
        }
    }
}


/**
 * [X]        <title>
 *
 * The title sits at the true horizontal center of the bar - not
 * merely next to the X - by stacking Close (pinned to CenterStart)
 * and the title (pinned to Center) inside one Box, so the title's
 * position never shifts with the icon's width. Title is "All Photos"
 * while on the top-level album list, and switches to the real album
 * name once an album is opened - it is never changed just by the user
 * browsing without picking an album. There is no "▼" button and no
 * Gallery/Photos action here anymore; the album list itself is the
 * browsing UI now.
 */
@Composable
private fun PhotoPickerTopBar(
    title: String,
    onClose: () -> Unit
) {

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            IconGlyph(
                modifier = Modifier.size(20.dp),
                tint = Color.Black
            ) { drawCloseGlyph(it) }
        }

        Text(
            text = title,
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}


/**
 * Shown only once the permission request has actually come back
 * denied - never before that, and never in place of the one native
 * system dialog. This is plain text plus a button that opens the
 * device's real "App info" settings screen (Settings.ACTION_
 * APPLICATION_DETAILS_SETTINGS) where photo/media access can be
 * granted directly; it is not a re-implementation of the permission
 * prompt itself.
 */
@Composable
private fun PermissionDeniedState(onOpenSettings: () -> Unit) {

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            IconGlyph(
                modifier = Modifier.size(40.dp),
                tint = Color.Gray
            ) { drawImageGlyph(it) }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Photo access is turned off",
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = Color.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Allow photo access in Settings to see your albums here.",
                fontSize = 13.sp,
                color = Color.DarkGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onOpenSettings,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = HomeAccentPurple,
                        contentColor = Color.White
                    )
            ) {
                Text(text = "Open Settings")
            }
        }
    }
}


/**
 * The top-level browsing screen: a single "Camera" action row, then
 * every real album/folder from the device media library, each showing
 * its real thumbnail, real name, and real photo count - in the exact
 * order [MediaStoreHelper.queryAlbums] returns them (never reordered
 * here).
 */
@Composable
private fun AlbumListScreen(
    albums: List<MediaAlbum>,
    onCameraClick: () -> Unit,
    onAlbumClick: (MediaAlbum) -> Unit
) {

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.White)
    ) {

        item {
            CameraRow(onClick = onCameraClick)
            AlbumListDivider()
        }

        // itemsIndexed (rather than items) only so a divider can be
        // placed between rows without one trailing the last album -
        // the album list itself (data, order, keys) is unchanged.
        itemsIndexed(albums, key = { _, album -> album.bucketId }) { index, album ->
            AlbumRow(album = album, onClick = { onAlbumClick(album) })
            if (index != albums.lastIndex) {
                AlbumListDivider()
            }
        }
    }
}


/**
 * Thin, subtle row separator shared by every item in the Camera +
 * Album list. Matches each row's own 16.dp horizontal inset so it
 * reads as spanning the row content rather than the full screen
 * width edge-to-edge.
 */
@Composable
private fun AlbumListDivider() {

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.75.dp,
        color = Color(0xFFE6E6E6)
    )
}


/**
 * Single full-width row: the camera glyph on the left (same visual
 * treatment as an album thumbnail) with the label "Camera" next to
 * it. Always appears above every album, exactly once.
 */
@Composable
private fun CameraRow(onClick: () -> Unit) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier =
                Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF2F2F2)),
            contentAlignment = Alignment.Center
        ) {

            IconGlyph(
                modifier = Modifier.size(56.dp),
                tint = Color.DarkGray
            ) { drawCameraGlyph(it) }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = "Camera",
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = Color.Black
        )
    }
}


/**
 * One row in the album list: real thumbnail | real album name (with
 * its real photo count underneath), matching the spec's
 * [thumbnail] Name / count layout.
 */
@Composable
private fun AlbumRow(album: MediaAlbum, onClick: () -> Unit) {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val thumbnail by rememberImageThumbnail(context, album.thumbnailUri)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier =
                Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFEDEDED))
        ) {

            val bmp = thumbnail

            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column {

            Text(
                text = album.name,
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = Color.Black
            )

            Text(
                text = album.count.toString(),
                fontSize = 16.sp,
                color = Color.DarkGray
            )
        }
    }
}


/**
 * A single album's photos, in a 3-column grid - no Camera tile here
 * (that action only lives at the top of the album list); this screen
 * shows ONLY the photos that belong to the opened album.
 */
@Composable
private fun AlbumPhotosGrid(
    images: List<MediaImage>,
    onImageClick: (MediaImage) -> Unit
) {

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.White)
    ) {

        items(images, key = { it.id }) { image ->
            PhotoThumbnail(image = image, onClick = { onImageClick(image) })
        }
    }
}


@Composable
private fun PhotoThumbnail(image: MediaImage, onClick: () -> Unit) {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val thumbnail by rememberImageThumbnail(context, image.uri)

    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .padding(1.dp)
                .background(Color(0xFFEDEDED))
                .clickable(onClick = onClick)
    ) {

        val bmp = thumbnail

        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}


// ---- Photo-picker glyphs (hand-drawn Canvas, same style as
// drawImageGlyph / drawWarningGlyph above - no drawable resources
// exist for these yet) ----

private fun DrawScope.drawCloseGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.12f

    drawLine(tint, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), sw, cap = StrokeCap.Round)
}


private fun DrawScope.drawCameraGlyph(tint: Color) {

    val w = size.width
    val h = size.height
    val sw = w * 0.07f

    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.1f, h * 0.28f),
        size = Size(w * 0.8f, h * 0.55f),
        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        style = Stroke(width = sw)
    )

    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.38f, h * 0.15f),
        size = Size(w * 0.24f, h * 0.14f),
        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        style = Stroke(width = sw * 0.8f)
    )

    drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.56f), style = Stroke(width = sw))
}