package com.maheshkatakam.melo.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies songs you pick (from Downloads, a USB drive, WhatsApp, anywhere) into Music/Melo
 * so they appear in the library like any other song.
 */
class SongImporter(private val context: Context, private val music: MusicRepository) {

    data class Result(val imported: Int, val failed: Int)

    suspend fun import(uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        val scanned = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(uri) ?: "song_${System.currentTimeMillis()}.mp3"
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val success = runCatching {
                if (Build.VERSION.SDK_INT >= 29) copyViaMediaStore(uri, name, mime)
                else copyLegacy(uri, name)?.let { scanned += it }
            }.onFailure { Log.w("Melo", "Import failed for $uri", it) }.isSuccess
            if (success) ok++ else failed++
        }
        if (scanned.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, scanned.toTypedArray(), null, null)
        }
        music.refresh()
        Result(ok, failed)
    }

    private fun copyViaMediaStore(source: Uri, name: String, mime: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Melo")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: error("Could not create file")
        try {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(target).use { output ->
                    requireNotNull(input); requireNotNull(output)
                    input.copyTo(output, 64 * 1024)
                }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(target, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun copyLegacy(source: Uri, name: String): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Melo")
        dir.mkdirs()
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.', "mp3"))
            n++
        }
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input)
            file.outputStream().use { input.copyTo(it, 64 * 1024) }
        }
        return file.absolutePath
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
