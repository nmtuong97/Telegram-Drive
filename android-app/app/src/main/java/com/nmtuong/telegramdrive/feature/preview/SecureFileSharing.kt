package com.nmtuong.telegramdrive.feature.preview

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

internal sealed interface ExternalOpenResult {
    data object Opened : ExternalOpenResult
    data object NoCompatibleApp : ExternalOpenResult
    data object UnsafeFile : ExternalOpenResult
    data object Failed : ExternalOpenResult
}

internal fun isAllowedExternalFile(context: Context, file: File): Boolean {
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
    val allowedRoots = listOf(
        context.cacheDir,
        context.filesDir.resolve("tdlib/files"),
    ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
    return allowedRoots.any { root ->
        canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
    } && canonical.isFile
}

internal fun openFileWithExternalApp(context: Context, path: String, mimeType: String?): ExternalOpenResult {
    val file = File(path)
    if (!isAllowedExternalFile(context, file)) return ExternalOpenResult.UnsafeFile
    return runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setDataAndType(uri, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            ExternalOpenResult.NoCompatibleApp
        } else {
            context.startActivity(intent)
            ExternalOpenResult.Opened
        }
    }.getOrElse { ExternalOpenResult.Failed }
}
