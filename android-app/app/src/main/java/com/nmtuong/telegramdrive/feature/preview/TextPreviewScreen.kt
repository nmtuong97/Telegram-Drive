package com.nmtuong.telegramdrive.feature.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nmtuong.telegramdrive.R
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_TEXT_PREVIEW_BYTES = 1_048_576

@Composable
fun TextPreviewScreen(path: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by produceState<TextResult>(TextResult.Loading, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { TextResult.Content(readTextPreview(File(path))) }
                .getOrElse { TextResult.Error(it.message ?: "Unable to read this text file") }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.back)) }
        when (val result = state) {
            TextResult.Loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            is TextResult.Error -> Text(result.message, modifier = Modifier.padding(16.dp))
            is TextResult.Content -> Text(
                text = result.text,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

internal sealed interface TextResult {
    data object Loading : TextResult
    data class Content(val text: String) : TextResult
    data class Error(val message: String) : TextResult
}

internal fun readTextPreview(file: File, maxBytes: Int = MAX_TEXT_PREVIEW_BYTES): String {
    require(file.isFile) { "Unable to open this text file" }
    FileInputStream(file).use { input ->
        val bytes = ByteArray(maxBytes + 1)
        var total = 0
        while (total < bytes.size) {
            val read = input.read(bytes, total, bytes.size - total)
            if (read < 0) break
            total += read
        }
        require(total <= maxBytes) { "Text preview is limited to 1 MB" }
        return bytes.copyOf(total).toString(Charsets.UTF_8)
    }
}
