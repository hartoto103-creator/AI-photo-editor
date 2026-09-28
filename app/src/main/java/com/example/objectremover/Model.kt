package com.example.objectremover

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

enum class ToolMode {
    BRUSH,
    LASSO,
    DETECTION,
    DESELECT
}

data class BrushStroke(
    val points: List<Offset>,
    val width: Float,
    val tool: ToolMode = ToolMode.BRUSH
)

data class EditorHistoryState(
    val bitmap: Bitmap,
    val strokes: List<BrushStroke>,
    val detectionMask: Bitmap?
)
