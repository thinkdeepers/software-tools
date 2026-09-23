package com.cursor.mobile.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.cursor.mobile.data.model.PromptImage
import java.io.ByteArrayOutputStream
import android.util.Base64

fun bitmapToPromptImage(source: Bitmap): PromptImage {
    val maxEdge = 1280
    val largest = maxOf(source.width, source.height).coerceAtLeast(1)
    val scale = minOf(1f, maxEdge.toFloat() / largest)
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, width, height, true) else source
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
    if (scaled !== source) scaled.recycle()
    return PromptImage(
        data = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP),
        mimeType = "image/jpeg"
    )
}

fun drawMarkup(base: Bitmap, strokes: List<List<Pair<Float, Float>>>): Bitmap {
    val output = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4D4F")
        style = Paint.Style.STROKE
        strokeWidth = (maxOf(output.width, output.height) / 80f).coerceAtLeast(6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        val path = Path()
        path.moveTo(stroke.first().first * output.width, stroke.first().second * output.height)
        stroke.drop(1).forEach { point ->
            path.lineTo(point.first * output.width, point.second * output.height)
        }
        canvas.drawPath(path, paint)
    }
    return output
}
