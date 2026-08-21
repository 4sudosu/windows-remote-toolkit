package com.runtimebroker.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object MediaSaver {

    /** Saves a base64 blob to the shared MediaStore gallery. Returns a content Uri. */
    fun save(context: Context, mimeType: String, displayName: String, base64: String, subDir: String): Uri? {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null

        val dir = "${Environment.DIRECTORY_DOWNLOADS}/RuntimeBroker" +
            (if (subDir.isNotBlank()) "/$subDir" else "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            }
        }

        val collection = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || collection == MediaStore.Images.Media.EXTERNAL_CONTENT_URI ||
            collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI || collection == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) {
            val uri = context.contentResolver.insert(collection, values) ?: return null
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            out.use { it.write(bytes) }
            return uri
        }

        // Pre-Q generic file: fall back to app-specific storage behind FileProvider.
        val ext = displayName.substringAfterLast('.', "bin")
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "$subDir-$displayName")
        return try {
            FileOutputStream(file).use { it.write(bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    fun open(context: Context, uri: Uri, mimeType: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open"))
            true
        } catch (e: Exception) {
            false
        }
    }
}