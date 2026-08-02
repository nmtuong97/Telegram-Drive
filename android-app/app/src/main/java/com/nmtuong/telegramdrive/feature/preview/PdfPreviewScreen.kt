package com.nmtuong.telegramdrive.feature.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nmtuong.telegramdrive.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PdfPageState(val pageCount: Int, val bitmap: Bitmap? = null, val error: String? = null)

@Composable
fun PdfPreviewScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val file = remember(path) { File(path) }
    var pageIndex by remember(path) { mutableIntStateOf(0) }
    val pageState by produceState(PdfPageState(0), path, pageIndex) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file, pageIndex) }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
        when {
            pageState.error != null -> Text(pageState.error!!, modifier = Modifier.padding(16.dp))
            pageState.bitmap == null -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            else -> {
                Image(
                    bitmap = pageState.bitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.pdf_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text(stringResource(R.string.previous_page)) }
                    Text(stringResource(R.string.page_of, pageIndex + 1, pageState.pageCount))
                    Button(onClick = { pageIndex++ }, enabled = pageIndex + 1 < pageState.pageCount) { Text(stringResource(R.string.next_page)) }
                }
            }
        }
    }
}

private fun renderPdfPage(file: File, requestedPage: Int): PdfPageState {
    if (!file.isFile) return PdfPageState(0, error = "Unable to open this PDF file")
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return PdfPageState(0, error = "This PDF has no pages")
                val pageNumber = requestedPage.coerceIn(0, renderer.pageCount - 1)
                val page = renderer.openPage(pageNumber)
                try {
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfPageState(renderer.pageCount, bitmap)
                } finally {
                    page.close()
                }
            }
        }
    }.getOrElse { PdfPageState(0, error = "Unable to render this PDF file") }
}
