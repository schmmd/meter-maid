package com.schmitztech.metermaid.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File access through the Storage Access Framework. The user picks a destination in the
 * system picker — Google Drive, Dropbox, local storage — so there is no account, no
 * OAuth, no shared secret and no server component to deploy.
 */
class SafFile(private val context: Context) {

    private val resolver get() = context.contentResolver

    /**
     * Persist write access to [uri] so the recurring export still works after a restart.
     * Returns false if the provider refused: the file works for this session but the
     * grant dies with the process, and the caller has to say so rather than promise
     * exports that quietly stop.
     */
    suspend fun persist(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
    }

    /** Overwrites the document at [uri]. "wt" truncates, so a shorter file leaves no tail. */
    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open the selected file for writing.")
    }

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open the selected file for reading.")
    }

    /** Best-effort display name, off the main thread: for a cloud file this hits the network. */
    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
