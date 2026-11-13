package com.maheshkatakam.melo.lyrics.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A Whisper model you can download once for better lyrics. */
data class DownloadableModel(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
)

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val fraction: Float, val waitingForNetwork: Boolean) : ModelState
    data object Checking : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads the large "accurate" lyrics model with Android's own download manager, so it
 * survives Melo being closed, resumes after network drops, and shows the system's progress
 * notification. The file is checked against its known checksum before it's used.
 */
class AiModelStore(private val context: Context, private val scope: CoroutineScope) {

    /**
     * Whisper large-v3-turbo, 5-bit (MIT licence). Much better than the built-in model at
     * Indian languages; it writes the lyrics in the song's own script, and Melo translates them.
     */
    val accurate = DownloadableModel(
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        sizeBytes = 574_041_195L,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        urls = listOf(
            "https://github.com/maheshkatakam/melo/releases/download/ai-models/ggml-large-v3-turbo-q5_0.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        ),
    )

    private val prefs = context.getSharedPreferences("melo_ai_models", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var watcher: Job? = null

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** The model file, only once it's fully downloaded and checked. */
    fun readyFile(): File? = file(accurate).takeIf { prefs.getBoolean(KEY_VERIFIED, false) && it.length() == accurate.sizeBytes }

    fun download(urlIndex: Int = 0) {
        val model = accurate
        val url = model.urls.getOrNull(urlIndex) ?: return
        val free = runCatching { StatFs(dir.apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < model.sizeBytes + 200L * 1024 * 1024) {
            _state.value = ModelState.Failed("Not enough free space. The model needs about 600 MB.")
            return
        }
        cancelDownload()
        file(model).delete()
        if (context.getExternalFilesDir(null) == null) {
            _state.value = ModelState.Failed("The phone's storage isn't available right now.")
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Melo lyrics AI")
            .setDescription("Better lyrics for Indian languages (one-time download)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "models/${model.fileName}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            _state.value = ModelState.Failed("Couldn't start the download.")
            return
        }
        prefs.edit().putLong(KEY_DOWNLOAD_ID, id).putInt(KEY_URL_INDEX, urlIndex).putBoolean(KEY_VERIFIED, false).apply()
        _state.value = ModelState.Downloading(0f, false)
        watch()
    }

    fun cancelDownload() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id >= 0) runCatching { manager.remove(id) }
        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
        watcher?.cancel()
        if (_state.value is ModelState.Downloading) _state.value = ModelState.NotDownloaded
    }

    fun delete() {
        cancelDownload()
        file(accurate).delete()
        prefs.edit().putBoolean(KEY_VERIFIED, false).apply()
        _state.value = ModelState.NotDownloaded
    }

    private fun file(model: DownloadableModel) = File(dir, model.fileName)

    private suspend fun refresh() {
        when {
            readyFile() != null -> _state.value = ModelState.Ready
            prefs.getLong(KEY_DOWNLOAD_ID, -1L) >= 0 -> {
                _state.value = ModelState.Downloading(0f, false)
                watch()
            }
            file(accurate).length() == accurate.sizeBytes -> verify()
            else -> _state.value = ModelState.NotDownloaded
        }
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            while (true) {
                val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
                if (id < 0) return@launch
                val row = query(id)
                if (row == null) {
                    // Removed from the system list (for example cleared by the user).
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    refresh()
                    return@launch
                }
                when (row.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                        verify()
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                        val next = prefs.getInt(KEY_URL_INDEX, 0) + 1
                        if (next < accurate.urls.size) {
                            scope.launch { download(next) } // try the other server
                        } else {
                            _state.value = ModelState.Failed("The download failed. Try again on Wi-Fi.")
                        }
                        return@launch
                    }
                    else -> {
                        val total = if (row.total > 0) row.total else accurate.sizeBytes
                        _state.value = ModelState.Downloading(
                            (row.done.toFloat() / total).coerceIn(0f, 1f),
                            waitingForNetwork = row.status == DownloadManager.STATUS_PAUSED,
                        )
                    }
                }
                delay(1000)
            }
        }
    }

    private data class Row(val status: Int, val done: Long, val total: Long)

    private fun query(id: Long): Row? = runCatching {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            Row(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }.getOrNull()

    private suspend fun verify() = withContext(Dispatchers.IO) {
        _state.value = ModelState.Checking
        val f = file(accurate)
        val ok = f.length() == accurate.sizeBytes && sha256(f).equals(accurate.sha256, ignoreCase = true)
        prefs.edit().putBoolean(KEY_VERIFIED, ok).apply()
        if (ok) {
            _state.value = ModelState.Ready
        } else {
            f.delete()
            _state.value = ModelState.Failed("The download was damaged. Please try again.")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_URL_INDEX = "url_index"
        private const val KEY_VERIFIED = "verified"
    }
}
