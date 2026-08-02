package com.nmtuong.telegramdrive.feature.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nmtuong.telegramdrive.R

@Composable
fun ExternalPreviewScreen(path: String, mimeType: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var result by remember(path) { mutableStateOf<ExternalOpenResult?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.external_open_description), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { result = openFileWithExternalApp(context, path, mimeType) }) {
            Text(stringResource(R.string.open_file))
        }
        when (result) {
            ExternalOpenResult.NoCompatibleApp -> Text(stringResource(R.string.no_compatible_app), color = MaterialTheme.colorScheme.error)
            ExternalOpenResult.UnsafeFile, ExternalOpenResult.Failed -> Text(stringResource(R.string.media_error), color = MaterialTheme.colorScheme.error)
            ExternalOpenResult.Opened, null -> Unit
        }
    }
}
