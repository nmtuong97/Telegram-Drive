package com.nmtuong.telegramdrive.feature.preview

import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nmtuong.telegramdrive.R
import java.io.File
import kotlin.math.min

@Suppress("DEPRECATION")
private class AnimatedImageView(context: android.content.Context) : View(context) {
    private var movie: Movie? = null
    private var startedAt = 0L

    fun setMovie(value: Movie?) {
        movie = value
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMovie = movie ?: return
        val duration = currentMovie.duration().takeIf { it > 0 } ?: 1_000
        currentMovie.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        val width = currentMovie.width().takeIf { it > 0 } ?: return
        val height = currentMovie.height().takeIf { it > 0 } ?: return
        val scale = min(width.toFloat().let { this.width / it }, height.toFloat().let { this.height / it })
        canvas.save()
        canvas.translate((this.width - width * scale) / 2f, (this.height - height * scale) / 2f)
        canvas.scale(scale, scale)
        currentMovie.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}

@Suppress("DEPRECATION")
@Composable
fun AnimationPreviewScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val file = remember(path) { File(path) }
    val movie = remember(path) { file.takeIf(File::isFile)?.let { Movie.decodeFile(it.absolutePath) } }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
        if (movie == null) {
            Text(stringResource(R.string.media_error), modifier = Modifier.padding(16.dp))
        } else {
            AndroidView(
                factory = { AnimatedImageView(it).apply { setMovie(movie) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
