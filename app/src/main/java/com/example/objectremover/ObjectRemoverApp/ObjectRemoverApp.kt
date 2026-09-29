package com.example.objectremover.objectremoverapp

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.example.objectremover.BrushStroke
import com.example.objectremover.EditorHistoryState
import com.example.objectremover.InteractiveSegmenterHelper
import com.example.objectremover.LamaInpainter
import com.example.objectremover.ToolMode
import com.example.objectremover.decodeOrientedBitmap
import com.example.objectremover.rasterizeSelectionMask
import com.example.objectremover.saveMarkedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ObjectRemoverApp() {
    val context = LocalContext.current
    val interactiveSegmenter = remember {
        InteractiveSegmenterHelper(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            interactiveSegmenter.close()
        }
    }

    val coroutineScope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectionMask by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bitmap) {
        bitmap?.let { currentBitmap ->
            interactiveSegmenter.setInputImage(currentBitmap)
        }
    }

    var showPhotoPicker by remember {
        mutableStateOf(false)
    }

    var brushSize by remember {
        mutableFloatStateOf(40f)
    }

    var eraseSize by remember {
        mutableFloatStateOf(40f)
    }

    var toolMode by remember {
        mutableStateOf(ToolMode.BRUSH)
    }

    val strokes = remember {
        mutableStateListOf<BrushStroke>()
    }

    val undoHistory = remember {
        mutableStateListOf<EditorHistoryState>()
    }

    val redoHistory = remember {
        mutableStateListOf<EditorHistoryState>()
    }

    var liveStroke by remember {
        mutableStateOf<List<Offset>>(emptyList())
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

    fun captureEditorState(): EditorHistoryState? {
        val currentBitmap = bitmap ?: return null

        return EditorHistoryState(
            bitmap = currentBitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            ),
            strokes = strokes.toList(),
            detectionMask = detectionMask?.copy(
                Bitmap.Config.ARGB_8888,
                false
            )
        )
    }

    fun saveUndoState() {
        captureEditorState()?.let { state ->
            undoHistory.add(state)
            redoHistory.clear()
        }
    }

    fun discardAndExitEditor() {
        bitmap = null
        resetEditorState()
    }

    // =====================================================================
    // PHOTO SELECTION -> EDITOR HANDOFF
    // =====================================================================

    fun onImageUriSelected(uri: Uri) {
        coroutineScope.launch {
            val loaded = withContext(Dispatchers.IO) {
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
    // =====================================================================

    fun performDelete() {
        if (isDeleting) return

        val currentBitmap = bitmap ?: return
        val beforeDeleteState = captureEditorState()

        if (strokes.isEmpty() && detectionMask == null) {
            Toast.makeText(
                context,
                "Select an object first.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

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

                val inpainter = LamaInpainter.getInstance(context)

                val result = inpainter.inpaint(
                    image = currentBitmap,
                    maskBitmap
                )

                maskBitmap.recycle()

                beforeDeleteState?.let { state ->
                    undoHistory.add(state)
                    redoHistory.clear()
                }

                redoHistory.clear()

                bitmap = result

                if (detectionMask != null) {
                    detectionMask?.recycle()
                    detectionMask = null
                }

                strokes.removeAll(consumedStrokes)

            } catch (e: Exception) {
                e.printStackTrace()

                Log.e(
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
                onBrushSizeChange = {
                    brushSize = it
                },

                eraseSize = eraseSize,
                onEraseSizeChange = {
                    eraseSize = it
                },

                toolMode = toolMode,
                onToolModeChange = {
                    toolMode = it
                },

                strokes = strokes,
                liveStroke = liveStroke,

                scale = scale,
                pan = pan,

                cursor = cursor,
                cursorVisible = cursorVisible,

                canUndo = undoHistory.isNotEmpty(),
                canRedo = redoHistory.isNotEmpty(),
                canDeselect = strokes.isNotEmpty(),

                isDeleting = isDeleting,

                onBack = {
                    discardAndExitEditor()
                    showPhotoPicker = true
                },

                onSave = {
                    val bmp = bitmap

                    if (bmp != null) {
                        val ok = saveMarkedImage(
                            context,
                            bmp
                        )

                        Toast.makeText(
                            context,
                            if (ok) {
                                "Saved to gallery"
                            } else {
                                "Save failed"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },

                onDelete = {
                    performDelete()
                },

                onUndo = {
                    val currentState = captureEditorState()

                    if (
                        currentState != null &&
                        undoHistory.isNotEmpty()
                    ) {
                        redoHistory.add(currentState)

                        val previousState =
                            undoHistory.removeAt(
                                undoHistory.lastIndex
                            )

                        bitmap = previousState.bitmap

                        strokes.clear()
                        strokes.addAll(previousState.strokes)

                        detectionMask?.recycle()
                        detectionMask = previousState.detectionMask
                    }
                },

                onRedo = {
                    val currentState = captureEditorState()

                    if (
                        currentState != null &&
                        redoHistory.isNotEmpty()
                    ) {
                        undoHistory.add(currentState)

                        val nextState =
                            redoHistory.removeAt(
                                redoHistory.lastIndex
                            )

                        bitmap = nextState.bitmap

                        strokes.clear()
                        strokes.addAll(nextState.strokes)

                        detectionMask?.recycle()
                        detectionMask = nextState.detectionMask
                    }
                },

                onStrokeAdded = {
                    saveUndoState()
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
                    saveUndoState()
                    detectionMask?.recycle()
                    detectionMask = mask
                },

                detectionMask = detectionMask
            )
        }
    }
}