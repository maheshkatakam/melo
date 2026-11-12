package com.maheshkatakam.melo.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.maheshkatakam.melo.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the picture for a song, fully offline, in this order:
 *  1. A picture you chose yourself in Melo
 *  2. The cover embedded inside the song file (ID3 / FLAC / MP4 tags)
 *  3. A cover image in the same folder (cover.jpg, folder.jpg, ...)
 * Small versions are cached on disk so scrolling stays smooth.
 */
class ArtworkRepository(private val context: Context) {

    private val customDir = File(context.filesDir, "art").apply { mkdirs() }
    private val thumbDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    private val versions = ConcurrentHashMap<Long, Int>()
    private val _revision = MutableStateFlow(0)
    /** Bumps whenever a custom picture changes so the UI reloads it. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun version(songId: Long): Int = versions[songId] ?: 0

    private fun customFile(songId: Long) = File(customDir, "$songId.jpg")

    fun hasCustomArt(songId: Long): Boolean = customFile(songId).exists()

    fun customArtUri(songId: Long): Uri? = customFile(songId).takeIf { it.exists() }?.let { Uri.fromFile(it) }

    suspend fun setCustomArt(songId: Long, source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return@runCatching false
            val bitmap = decodeSampled(bytes, 1200) ?: return@runCatching false
            customFile(songId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            bump(songId)
            true
        }.getOrElse {
            Log.w(TAG, "Could not set picture", it)
            false
        }
    }

    suspend fun removeCustomArt(songId: Long) = withContext(Dispatchers.IO) {
        customFile(songId).delete()
        bump(songId)
    }

    /** After file access is granted: look again for folder covers for songs that had no picture. */
    fun forgetMisses() {
        thumbDir.listFiles { f -> f.name.endsWith(".none") }?.forEach { it.delete() }
        _revision.value = _revision.value + 1
    }

    private fun bump(songId: Long) {
        versions[songId] = version(songId) + 1
        thumbDir.listFiles { f -> f.name.startsWith("${songId}_") }?.forEach { it.delete() }
        _revision.value = _revision.value + 1
    }

    /** Encoded image bytes for a song, or null if it has no picture anywhere. */
    fun loadArt(song: Song, large: Boolean): ByteArray? {
        if (!large) {
            val thumb = File(thumbDir, "${song.id}_${song.dateModifiedSec}_${version(song.id)}.jpg")
            val none = File(thumbDir, "${song.id}_${song.dateModifiedSec}_${version(song.id)}.none")
            if (thumb.exists()) return runCatching { thumb.readBytes() }.getOrNull()
            if (none.exists()) return null
            val original = loadOriginal(song)
            if (original == null) {
                runCatching { none.createNewFile() }
                return null
            }
            val bitmap = decodeSampled(original, 360) ?: return original
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bitmap.recycle()
            val bytes = out.toByteArray()
            runCatching { thumb.writeBytes(bytes) }
            return bytes
        }
        return loadOriginal(song)
    }

    fun loadBitmap(song: Song, maxSize: Int): Bitmap? {
        val bytes = loadArt(song, large = maxSize > 360) ?: return null
        return decodeSampled(bytes, maxSize)
    }

    private fun loadOriginal(song: Song): ByteArray? {
        customFile(song.id).takeIf { it.exists() }?.let { file ->
            return runCatching { file.readBytes() }.getOrNull()
        }
        embedded(song)?.let { return it }
        return folderCover(song)
    }

    private fun embedded(song: Song): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, song.uri)
            retriever.embeddedPicture
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun folderCover(song: Song): ByteArray? {
        val path = song.path ?: return null
        val dir = File(path).parentFile ?: return null
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val images = files.filter { f ->
            val n = f.name.lowercase()
            f.isFile && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
        }
        if (images.isEmpty()) return null
        val base = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val preferred = listOf(base, "cover", "folder", "front", "album", "albumart", "albumartsmall")
        val pick = preferred.firstNotNullOfOrNull { name ->
            images.firstOrNull { it.nameWithoutExtension.lowercase() == name }
        } ?: images.singleOrNull()
        return pick?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    private fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= maxSize) return decoded
        val scale = maxSize.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "MeloArt"
    }
}
