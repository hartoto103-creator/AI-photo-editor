package com.example.objectremover

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color

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
            matrix.postScale(-1f, 1f)
            matrix.postRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postScale(-1f, 1f)
            matrix.postRotate(270f)
        }

        else -> return null
    }

    return matrix
}

fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val decoded =
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        } ?: return null

    val matrix = exifTransformFor(context, uri) ?: return decoded

    return try {
        Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            matrix,
            true
        )
    } catch (e: Exception) {
        decoded
    }
}

fun saveMarkedImage(
    context: Context,
    source: Bitmap
): Boolean {

    val result = source.copy(
        Bitmap.Config.ARGB_8888,
        true
    )

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

fun rasterizeSelectionMask(
    width: Int,
    height: Int,
    strokes: List<BrushStroke>
): Bitmap {

    val mask = Bitmap.createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888
    )

    val canvas = AndroidCanvas(mask)

    canvas.drawColor(
        android.graphics.Color.TRANSPARENT,
        android.graphics.PorterDuff.Mode.CLEAR
    )

    val paint = AndroidPaint().apply {
        isAntiAlias = true
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        color = android.graphics.Color.WHITE
    }

    val clearXfermode =
        android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.CLEAR
        )

    for (stroke in strokes) {

        if (stroke.points.isEmpty()) continue

        val isErase = stroke.tool == ToolMode.DESELECT
        val isLasso = stroke.tool == ToolMode.LASSO

        paint.xfermode =
            if (isErase) clearXfermode else null

        paint.style =
            if (isLasso) {
                AndroidPaint.Style.FILL
            } else {
                AndroidPaint.Style.STROKE
            }

        paint.strokeWidth = stroke.width

        if (stroke.points.size == 1) {

            val p = stroke.points[0]

            val savedStyle = paint.style

            paint.style = AndroidPaint.Style.FILL

            canvas.drawCircle(
                p.x,
                p.y,
                stroke.width / 2f,
                paint
            )

            paint.style = savedStyle

        } else {

            val path = AndroidPath()

            path.moveTo(
                stroke.points[0].x,
                stroke.points[0].y
            )

            for (i in 1 until stroke.points.size) {
                path.lineTo(
                    stroke.points[i].x,
                    stroke.points[i].y
                )
            }

            if (isLasso) {

                path.close()

                canvas.drawPath(
                    path,
                    paint
                )

                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 8f
                paint.strokeCap = AndroidPaint.Cap.ROUND
                paint.strokeJoin = AndroidPaint.Join.ROUND

                canvas.drawPath(
                    path,
                    paint
                )

                paint.style = AndroidPaint.Style.FILL

            } else {

                canvas.drawPath(
                    path,
                    paint
                )
            }
        }
    }

    return mask
}

fun androidColorFor(tool: ToolMode): Int {
    return when (tool) {
        ToolMode.BRUSH -> android.graphics.Color.RED
        ToolMode.LASSO -> android.graphics.Color.RED
        ToolMode.DETECTION -> android.graphics.Color.WHITE
        ToolMode.DESELECT -> android.graphics.Color.TRANSPARENT
    }
}

fun composeColorFor(tool: ToolMode): Color {
    return when (tool) {
        ToolMode.BRUSH -> Color.Red
        ToolMode.LASSO -> Color.Red
        ToolMode.DETECTION -> Color.White
        ToolMode.DESELECT -> Color.Gray
    }
}