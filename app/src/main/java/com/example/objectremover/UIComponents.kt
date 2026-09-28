package com.example.objectremover

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TopBar(
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(
        horizontal = 4.dp,
        vertical = 4.dp
    ),
        verticalAlignment = Alignment.CenterVertically
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

@Composable
fun EditingLoadingSpinner(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(
        label = "editing_spinner"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 900,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinner_rotation"
    )
    Canvas(
        modifier = modifier.size(56.dp)
    ) {
        val center = Offset(
            size.width / 2f,
            size.height / 2f
        )
        val radius = size.minDimension * 0.32f
        val strokeWidth = size.minDimension * 0.13f
        for (i in 0 until 8) {
            val angle =
                Math.toRadians(
                    (i * 45f + rotation).toDouble()
                )
            val x = center.x + kotlin.math.cos(angle).toFloat() * radius
            val y = center.y + kotlin.math.sin(angle).toFloat() * radius
            val alpha = 0.20f + (i / 7f) * 0.80f
            drawLine(
                color = Color.DarkGray.copy(alpha = alpha),
                start = Offset(
                    x - kotlin.math.cos(angle).toFloat() * strokeWidth * 0.45f,
                    y - kotlin.math.sin(angle).toFloat() * strokeWidth * 0.45f
                ),
                end = Offset(
                    x + kotlin.math.cos(angle).toFloat() * strokeWidth * 0.45f,
                    y + kotlin.math.sin(angle).toFloat() * strokeWidth * 0.45f
                ),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun IconGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    onDraw: DrawScope.(Color) -> Unit
) {
    Canvas(
        modifier = modifier
    ) {
        onDraw(tint)
    }
}

fun DrawScope.drawImageGlyph(tint: Color) {
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
    drawCircle(
        tint,
        radius = w * 0.07f,
        center = Offset(w * 0.32f, h * 0.38f)
    )
    val path = Path().apply {
        moveTo(w * 0.18f, h * 0.75f)
        lineTo(w * 0.4f, h * 0.5f)
        lineTo(w * 0.55f, h * 0.65f)
        lineTo(w * 0.7f, h * 0.45f)
        lineTo(w * 0.85f, h * 0.75f)
    }
    drawPath(
        path,
        color = tint,
        style = Stroke(
            width = sw,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

fun DrawScope.drawWarningGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.92f, h * 0.85f)
        lineTo(w * 0.08f, h * 0.85f)
        close()
    }
    drawPath(
        path,
        color = tint,
        style = Stroke(
            width = sw,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    drawLine(
        tint,
        Offset(w * 0.5f, h * 0.4f),
        Offset(w * 0.5f, h * 0.62f),
        sw,
        cap = StrokeCap.Round
    )
    drawCircle(
        tint,
        radius = sw * 0.6f,
        center = Offset(w * 0.5f, h * 0.74f)
    )
}

@Composable
fun PrimaryDeleteButton(
    enabled: Boolean,
    onClick: () -> Unit
) {

    Row(modifier = Modifier.fillMaxWidth().padding(
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
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(48.dp)
        ) {
            Text(text = "DELETE", fontSize = 16.sp
            )
        }
    }
}

@Composable
fun BottomControls(
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

    val brushSizeEnabled =
        toolMode == ToolMode.BRUSH || toolMode == ToolMode.DESELECT
    val brushSizeTint =
        if (brushSizeEnabled) Color.DarkGray else Color.Gray.copy(alpha = 0.5f)

    Column(modifier = Modifier.fillMaxWidth().padding(
        horizontal = 12.dp,
        vertical = 8.dp
    )
    ) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ToolIcon(
                painter = painterResource(R.drawable.ic_brush),
                tint = brushSizeTint,
                modifier = Modifier.size(18.dp),
                contentDescription = null
            )
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
        modifier = Modifier.padding(horizontal = 2.dp)
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

val HomeAccentPurple = Color(0xFF6A3DE8)

@Composable
fun ConfirmDiscardDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier
                .wrapContentSize()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
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
                Text(text = "Are you sure you want to go back?",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {Text("CANCEL") }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onConfirm) {
                        Text(text = "YES", color = Color(0xFFD32F2F))
                    }
                }
            }
        }
    }
}

@Composable
fun ToolIcon(
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