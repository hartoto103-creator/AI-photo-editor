package com.example.objectremover.objectremoverapp

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import com.example.objectremover.BottomControls
import com.example.objectremover.BrushStroke
import com.example.objectremover.ConfirmDiscardDialog
import com.example.objectremover.EditingLoadingSpinner
import com.example.objectremover.InteractiveSegmenterHelper
import com.example.objectremover.PrimaryDeleteButton
import com.example.objectremover.ToolMode
import com.example.objectremover.TopBar

@Composable
fun EditorScreen(
    bitmap: Bitmap,

    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,

    eraseSize: Float,
    onEraseSizeChange: (Float) -> Unit,

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

    isDeleting: Boolean,

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
    detectionMask: Bitmap?
) {

    var showDiscardDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        showDiscardDialog = true
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        TopBar(
            onBack = {
                showDiscardDialog = true
            },
            onSave = onSave
        )

        // =========================================================
        // CANVAS AREA BOUNDARY
        // =========================================================
        Box(
            modifier = Modifier
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

            if (isDeleting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EditingLoadingSpinner()
                }
            }
        }

        // =========================================================
        // PRIMARY DELETE ACTION
        // =========================================================
        PrimaryDeleteButton(
            enabled = strokes.isNotEmpty() || detectionMask != null,
            onClick = onDelete
        )

        // =========================================================
        // BOTTOM CONTROLS
        // =========================================================
        BottomControls(
            brushSize = brushSize,
            onBrushSizeChange = onBrushSizeChange,

            eraseSize = eraseSize,
            onEraseSizeChange = onEraseSizeChange,

            toolMode = toolMode,
            onToolModeChange = onToolModeChange,

            canUndo = canUndo,
            canRedo = canRedo,
            canDeselect = canDeselect,

            onUndo = onUndo,
            onRedo = onRedo
        )
    }

    // =========================================================
    // DISCARD DIALOG
    // =========================================================
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