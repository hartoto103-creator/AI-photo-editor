package com.example.objectremover

import android.app.Activity
import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
const val DESELECT_BRUSH_SIZE = 40f
fun baseScaleFor(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmap: Bitmap
): Float {
    return kotlin.math.min(
        canvasWidth / bitmap.width.toFloat(),
        canvasHeight / bitmap.height.toFloat()
    )
}
fun clampPan(
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
    val displayedWidth = bitmap.width * totalScale
    val displayedHeight = bitmap.height * totalScale
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
fun screenToImage(
    screenPoint: Offset,
    canvasSize: IntSize,
    bitmap: Bitmap,
    scale: Float,
    pan: Offset
): Offset {
    val center = Offset(
        canvasSize.width / 2f,
        canvasSize.height / 2f
    )
    val totalScale = baseScaleFor(
        canvasSize.width.toFloat(),
        canvasSize.height.toFloat(),
        bitmap
    ) * scale
    return Offset(
        (screenPoint.x - center.x - pan.x) / totalScale + bitmap.width / 2f,
        (screenPoint.y - center.y - pan.y) / totalScale + bitmap.height / 2f
    )
}
fun totalScaleFor(
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
fun suppressReturnTransition(context: Context) {
    val activity = context as? Activity ?: return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        activity.overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            0,
            0
        )
    } else {
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}