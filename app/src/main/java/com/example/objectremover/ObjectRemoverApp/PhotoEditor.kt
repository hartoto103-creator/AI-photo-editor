package com.example.objectremover.objectremoverapp

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.objectremover.BrushStroke
import com.example.objectremover.DESELECT_BRUSH_SIZE
import com.example.objectremover.InteractiveSegmenterHelper
import com.example.objectremover.ToolMode
import com.example.objectremover.baseScaleFor
import com.example.objectremover.clampPan
import com.example.objectremover.composeColorFor
import com.example.objectremover.screenToImage
import com.example.objectremover.totalScaleFor
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun PhotoEditor(
    bitmap: Bitmap,
    brushSize: Float,
    toolMode: ToolMode,
    strokes: List<BrushStroke>,
    liveStroke: List<Offset>,
    scaleState: Float,
    panState: Offset,
    cursor: Offset?,
    cursorVisible: Boolean,
    onStrokeAdded: (BrushStroke) -> Unit,
    onLiveStrokeChanged: (List<Offset>) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onPanChanged: (Offset) -> Unit,
    onCursorChanged: (Offset?) -> Unit,
    interactiveSegmenter: InteractiveSegmenterHelper,
    onDetectionMaskReady: (Bitmap) -> Unit,
    detectionMask: Bitmap?,

    ) {

    val currentScale = rememberUpdatedState(scaleState)
    val currentPan = rememberUpdatedState(panState)
    val currentBrushSize = rememberUpdatedState(brushSize)
    val currentBitmap = rememberUpdatedState(bitmap)
    val currentToolMode = rememberUpdatedState(toolMode)
    var magnifierOnRight by remember { mutableStateOf(false) }
    var magnifierCollisionLatched by remember { mutableStateOf(false) }

    Canvas(
        modifier = Modifier.fillMaxSize()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    var activePointerId: PointerId? = null
                    var multiTouch = false
                    var lastCentroid = Offset.Zero
                    var lastDistance = 0f
                    var localScale = currentScale.value
                    var localPan = currentPan.value
                    var currentLiveStroke = emptyList<Offset>()
                    var lastScreenPoint: Offset? = null
                    var gestureTool = currentToolMode.value

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        // =================================================
                        // 2 FINGER = ZOOM + PAN
                        // =================================================
                        if (pressed.size >= 2) {
                            if (!multiTouch) {
                                multiTouch = true
                                activePointerId = null
                                currentLiveStroke = emptyList()
                                lastScreenPoint = null
                                onLiveStrokeChanged(emptyList())
                                onCursorChanged(null)
                                val p1 = pressed[0].position
                                val p2 = pressed[1].position

                                lastCentroid =
                                    Offset(
                                        (p1.x + p2.x) / 2f,
                                        (p1.y + p2.y) / 2f
                                    )
                                lastDistance = (p1 - p2).getDistance()
                            }

                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val centroid =
                                Offset(
                                    (p1.x + p2.x) / 2f,
                                    (p1.y + p2.y) / 2f
                                )

                            val distance = (p1 - p2).getDistance()
                            if (lastDistance > 0f && distance > 0f) {
                                val oldScale = localScale
                                val oldPan = localPan
                                val rawZoomFactor = distance / lastDistance
                                val zoomFactor =
                                    if (kotlin.math.abs(
                                            rawZoomFactor - 1f
                                        ) < 0.01f
                                    ) {
                                        1f
                                    } else {
                                        rawZoomFactor
                                    }

                                val newScale = (oldScale * zoomFactor).coerceIn(1f, 8f)
                                val actualZoom = newScale / oldScale
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focalPoint = centroid - center - oldPan
                                val zoomCorrection = focalPoint * (1f - actualZoom)
                                val panMovement = centroid - lastCentroid
                                val newPan = oldPan + panMovement + zoomCorrection

                                val clampedPan =
                                    clampPan(
                                        pan = newPan,
                                        scale = newScale,
                                        canvasWidth = size.width.toFloat(),
                                        canvasHeight = size.height.toFloat(),
                                        bitmap = currentBitmap.value,
                                        totalScale =
                                            totalScaleFor(size, currentBitmap.value, newScale)
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
                        // =================================================
                        else if (pressed.size == 1 && !multiTouch) {
                            val change = pressed[0]
                            if (activePointerId == null) {
                                activePointerId = change.id
                                gestureTool = currentToolMode.value
                                currentLiveStroke = emptyList()
                                lastScreenPoint = null
                                onLiveStrokeChanged(emptyList())
                            }
                            if (change.id == activePointerId) {
                                val current = change.position

                                // =============================================================
                                // MAGNIFIER AUTO SIDE SWITCH
                                // =============================================================
                                val magnifierWidth = 500f
                                val magnifierHeight = 350f
                                val magnifierTop = 12f
                                val magnifierMargin = 12f

                                val magnifierLeft =
                                    if (magnifierOnRight) {
                                        size.width - magnifierWidth - magnifierMargin
                                    } else {
                                        magnifierMargin
                                    }

                                val magnifierRect =
                                    Rect(
                                        magnifierLeft,
                                        magnifierTop,
                                        magnifierLeft + magnifierWidth,
                                        magnifierTop + magnifierHeight
                                    )

                                val touchingMagnifier = magnifierRect.contains(current)
                                if (touchingMagnifier && !magnifierCollisionLatched) {
                                    magnifierOnRight = !magnifierOnRight
                                    magnifierCollisionLatched = true
                                } else if (!touchingMagnifier) {
                                    magnifierCollisionLatched = false
                                }

                                val brush =
                                    if (gestureTool == ToolMode.DESELECT) {
                                        DESELECT_BRUSH_SIZE
                                    } else {
                                        currentBrushSize.value
                                    }

                                val bmp = currentBitmap.value
                                val scaleNow = localScale
                                val panNow = localPan
                                val canvasSize = size

                                // =============================================================
                                // PHOTO BOUNDARY
                                // =============================================================
                                val totalScaleNow = totalScaleFor(canvasSize, bmp, scaleNow)
                                val imageWidthScreen = bmp.width * totalScaleNow
                                val imageHeightScreen = bmp.height * totalScaleNow
                                val imageLeft = canvasSize.width / 2f -
                                        imageWidthScreen / 2f +
                                        panNow.x

                                val imageTop = canvasSize.height / 2f -
                                        imageHeightScreen / 2f +
                                        panNow.y

                                val imageRight = imageLeft + imageWidthScreen
                                val imageBottom = imageTop + imageHeightScreen

                                val edgeInset =
                                    if (gestureTool == ToolMode.LASSO) {
                                        11f / 2f
                                    } else {
                                        brush / 2f
                                    }

                                val midX = (imageLeft + imageRight) / 2f
                                val midY = (imageTop + imageBottom) / 2f
                                val minX = (imageLeft + edgeInset).coerceAtMost(midX)
                                val maxX = (imageRight - edgeInset).coerceAtLeast(midX)
                                val minY = (imageTop + edgeInset).coerceAtMost(midY)
                                val maxY = (imageBottom - edgeInset).coerceAtLeast(midY)

                                val strokeScreenPoint = Offset(
                                    current.x.coerceIn(minX, maxX),
                                    current.y.coerceIn(minY, maxY)
                                )
                                val previousScreen = lastScreenPoint
                                if (previousScreen == null) {
                                    currentLiveStroke = listOf(
                                        screenToImage(
                                            strokeScreenPoint,
                                            canvasSize,
                                            bmp,
                                            scaleNow,
                                            panNow
                                        )
                                    )
                                } else {

                                    val distance =
                                        (strokeScreenPoint - previousScreen).getDistance()
                                    if (distance > 0f) {
                                        val spacing = (brush * 0.08f).coerceAtLeast(1f)
                                        val steps =
                                            ceil(distance / spacing).toInt().coerceAtLeast(1)
                                        val newPoints = buildList {
                                            for (i in 1..steps) {
                                                val t = i.toFloat() / steps
                                                val screenPoint =
                                                    previousScreen + (strokeScreenPoint - previousScreen) * t
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

                                        currentLiveStroke = currentLiveStroke + newPoints
                                    }
                                }

                                lastScreenPoint = strokeScreenPoint
                                onLiveStrokeChanged(currentLiveStroke)
                                onCursorChanged(current)
                                change.consume()
                            }
                        } else if (pressed.isEmpty()) {

                            if (!multiTouch && currentLiveStroke.isNotEmpty()) {
                                if (gestureTool == ToolMode.DETECTION) {
                                    android.util.Log.d(
                                        "Detection",
                                        "Detection tap triggered"
                                    )

                                    val tapPoint = currentLiveStroke.first()
                                    val normalizedX =
                                        (tapPoint.x / bitmap.width.toFloat()).coerceIn(0f, 1f)
                                    val normalizedY =
                                        (tapPoint.y / bitmap.height.toFloat()).coerceIn(0f, 1f)

                                    try {
                                        val mask = interactiveSegmenter.createMaskAt(
                                            normalizedX,
                                            normalizedY
                                        )
                                        onDetectionMaskReady(mask)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                var finalPoints = currentLiveStroke
                                if (gestureTool == ToolMode.LASSO && finalPoints.size >= 2) {
                                    finalPoints = finalPoints + finalPoints.first()
                                }
                                val totalScale =
                                    totalScaleFor(size, currentBitmap.value, localScale)
                                val effectiveBrushSize = if (gestureTool == ToolMode.DESELECT) {
                                    DESELECT_BRUSH_SIZE
                                } else {
                                    currentBrushSize.value
                                }

                                if (gestureTool != ToolMode.DETECTION) {
                                    onStrokeAdded(
                                        BrushStroke(
                                            points = finalPoints,
                                            width = effectiveBrushSize / totalScale,
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
        val baseScale = baseScaleFor(size.width, size.height, bitmap)
        val totalScale = baseScale * scaleState

        // =============================================================
        // PHOTO + ALL STROKES (saved and live)
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

            drawImage(bitmap.asImageBitmap())
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

            clipRect(
                left = 0f, top = 0f,
                right = bitmap.width.toFloat(),
                bottom = bitmap.height.toFloat()
            ) {

                val strokesLayerRect =
                    Rect(Offset.Zero, Size(bitmap.width.toFloat(), bitmap.height.toFloat()))
                drawContext.canvas.saveLayer(
                    strokesLayerRect,
                    Paint().apply { alpha = 0.35f }
                )

                for (stroke in strokes) {
                    if (stroke.tool == ToolMode.LASSO) {
                        drawClosedLassoSelection(
                            points = stroke.points,
                            lassoVisualWidth = 11f / totalScale
                        )
                    } else {

                        drawStrokePath(
                            points = stroke.points,
                            width = stroke.width,
                            tool = stroke.tool,
                            closed = false,
                            lassoVisualWidth = brushSize / totalScale
                        )
                    }
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
                        closed = false,
                        lassoVisualWidth = if (toolMode == ToolMode.LASSO) {
                            11f / totalScale
                        } else {
                            brushSize / totalScale
                        }
                    )
                }

                drawContext.canvas.restore()
            }
        }

        // =============================================================
        // CURSOR
        // =============================================================
        val currentCursor = cursor
        if (cursorVisible && currentCursor != null) {
            drawCircle(
                color = composeColorFor(toolMode),
                center = currentCursor,
                radius = (if (toolMode == ToolMode.DESELECT) {
                    DESELECT_BRUSH_SIZE
                } else {
                    brushSize
                }) / 2f,
                style = Stroke(width = 2f)
            )
        }

        // =============================================================
        // MAGNIFIER
        // =============================================================
        if (currentCursor != null && toolMode != ToolMode.DETECTION) {
            val finger = currentCursor
            val canvasSize = IntSize(
                size.width.toInt(),
                size.height.toInt()
            )

            val imagePoint = liveStroke.lastOrNull()
                ?: screenToImage(
                    screenPoint = finger,
                    canvasSize = canvasSize,
                    bitmap = bitmap,
                    scale = scaleState,
                    pan = panState
                )

            val magnifierImagePoint =
                Offset(
                    imagePoint.x.coerceIn(
                        0f,
                        bitmap.width.toFloat()
                    ),
                    imagePoint.y.coerceIn(
                        0f,
                        bitmap.height.toFloat()
                    )
                )

            val lensWidth = 500f
            val lensHeight = 350f
            val lensTop = 12f
            val lensMargin = 12f
            val cornerRadius = 22f
            val lensLeft =
                if (magnifierOnRight) {
                    size.width -
                            lensWidth -
                            lensMargin
                } else {
                    lensMargin
                }

            val lensCenter = Offset(
                lensLeft + lensWidth / 2f,
                lensTop + lensHeight / 2f
            )

            val zoom = 1.5f

            val sourceWidth = (lensWidth / zoom / totalScale).coerceAtLeast(1f)
            val sourceHeight = (lensHeight / zoom / totalScale).coerceAtLeast(1f)
            val sourceWidthPx = sourceWidth.roundToInt().coerceAtMost(bitmap.width)
            val sourceHeightPx = sourceHeight.roundToInt().coerceAtMost(bitmap.height)
            val sourceLeft = (magnifierImagePoint.x - sourceWidthPx / 2f).roundToInt()
            val sourceTop = (magnifierImagePoint.y - sourceHeightPx / 2f).roundToInt()
            val sourceRight = sourceLeft + sourceWidthPx
            val sourceBottom = sourceTop + sourceHeightPx
            val validLeft = sourceLeft.coerceAtLeast(0)
            val validTop = sourceTop.coerceAtLeast(0)
            val validRight = sourceRight.coerceAtMost(bitmap.width)
            val validBottom = sourceBottom.coerceAtMost(bitmap.height)
            val validWidth = (validRight - validLeft).coerceAtLeast(0)
            val validHeight = (validBottom - validTop).coerceAtLeast(0)
            val strokeScaleX = lensWidth / sourceWidthPx.toFloat()
            val strokeScaleY = lensHeight / sourceHeightPx.toFloat()
            val lensPath =
                Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            rect = Rect(
                                lensLeft,
                                lensTop,
                                lensLeft + lensWidth,
                                lensTop + lensHeight
                            ),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    )
                }

            clipPath(lensPath) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(
                        lensLeft,
                        lensTop
                    ),
                    size = Size(lensWidth, lensHeight)
                )

                // =========================================================
                // PHOTO PORTION
                // =========================================================
                if (validWidth > 0 && validHeight > 0) {
                    val dstLeft = lensLeft + (validLeft - sourceLeft) * strokeScaleX
                    val dstTop = lensTop + (validTop - sourceTop) * strokeScaleY
                    val dstWidth = validWidth * strokeScaleX
                    val dstHeight = validHeight * strokeScaleY

                    drawImage(
                        image = bitmap.asImageBitmap(),
                        srcOffset = IntOffset(validLeft, validTop),
                        srcSize = IntSize(validWidth, validHeight),
                        dstOffset = IntOffset(
                            dstLeft.roundToInt(),
                            dstTop.roundToInt()
                        ),
                        dstSize = IntSize(
                            dstWidth.roundToInt(),
                            dstHeight.roundToInt()
                        )
                    )
                }

                // =========================================================
                // ZOOMED STROKES
                // =========================================================
                withTransform({
                    translate(
                        left = lensLeft - sourceLeft * strokeScaleX,
                        top = lensTop - sourceTop * strokeScaleY
                    )
                    scale(
                        scaleX = strokeScaleX,
                        scaleY = strokeScaleY,
                        pivot = Offset.Zero
                    )

                }) {

                    val strokeMargin = brushSize.coerceAtLeast(DESELECT_BRUSH_SIZE) * 2f
                    val lensStrokeRect =
                        Rect(
                            left = -strokeMargin,
                            top = -strokeMargin,
                            right = bitmap.width.toFloat() + strokeMargin,
                            bottom = bitmap.height.toFloat() + strokeMargin
                        )
                    drawContext.canvas.saveLayer(
                        lensStrokeRect,
                        Paint().apply { alpha = 0.35f }
                    )
                    for (stroke in strokes) {
                        if (stroke.tool == ToolMode.LASSO) {
                            drawClosedLassoSelection(
                                points = stroke.points,
                                lassoVisualWidth = 11f / strokeScaleX
                            )
                        } else {
                            drawStrokePath(
                                points = stroke.points,
                                width = stroke.width,
                                tool = stroke.tool,
                                closed = false,
                                lassoVisualWidth = brushSize / totalScale
                            )
                        }
                    }

                    if (liveStroke.isNotEmpty()) {
                        drawStrokePath(
                            points = liveStroke,
                            width = (if (toolMode == ToolMode.DESELECT) DESELECT_BRUSH_SIZE
                            else brushSize
                                    ) / totalScale,

                            tool = toolMode, closed = false,
                            lassoVisualWidth = if (toolMode == ToolMode.LASSO) {
                                11f / strokeScaleX
                            } else {
                                brushSize / totalScale
                            }
                        )
                    }
                    drawContext.canvas.restore()
                }

                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(
                        lensLeft,
                        lensTop
                    ),
                    size = Size(
                        lensWidth, lensHeight
                    ),
                    cornerRadius = CornerRadius(
                        cornerRadius,
                        cornerRadius
                    ),
                    style = Stroke(width = 3f)
                )

            }

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
    closed: Boolean,
    lassoVisualWidth: Float = width
) {

    if (points.isEmpty())
        return
    val erase = tool == ToolMode.DESELECT
    val blendMode = if (erase) BlendMode.Clear else BlendMode.SrcOver
    val paintColor = when (tool) {
        ToolMode.BRUSH -> composeColorFor(tool)
        ToolMode.DETECTION -> composeColorFor(tool)
        ToolMode.LASSO -> composeColorFor(tool)
        ToolMode.DESELECT -> Color.Black
    }

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

    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    var i = 1
    while (i < points.size) {
        path.lineTo(points[i].x, points[i].y)
        i++
    }

    if (closed) { path.close() }
    if (tool == ToolMode.LASSO) {
        drawPath(path = path, color = paintColor, style =
            Stroke(
                width = lassoVisualWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(lassoVisualWidth * 3.2f, lassoVisualWidth * 1.8f)
                )
            )
        )
    } else {
        drawPath(path = path, color = paintColor, style =
            Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            blendMode = blendMode
        )
    }
}

private fun DrawScope.drawClosedLassoSelection(
    points: List<Offset>,
    lassoVisualWidth: Float
) {
    if (points.size < 2) return
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        path.lineTo(points[i].x, points[i].y)
    }

    path.close()
    drawPath(path = path, color = Color.Red)
    drawPath(path = path, color = Color.Red, style =
        Stroke(
            width = lassoVisualWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}