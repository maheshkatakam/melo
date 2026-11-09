package com.maheshkatakam.melo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "melo_settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val materialYou: Boolean = false,
    val artworkColors: Boolean = true,
    val minDurationSec: Int = 30,
    val hiddenFolders: Set<String> = emptySet(),
    val moodId: String = "normal",
    val songSort: SongSort = SongSort.TITLE,
    val autoAiLyrics: Boolean = false,
    val aiOnlyWhileCharging: Boolean = true,
    val auddToken: String = "",
    val autoFavoriteDiscovered: Boolean = true,
    val useDiscoveredCovers: Boolean = true,
    val preferOriginalLyrics: Boolean = false,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 1f,
    val loaded: Boolean = false,
)

data class SavedQueue(val songIds: List<String>, val index: Int, val positionMs: Long)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val materialYou = booleanPreferencesKey("material_you")
        val artworkColors = booleanPreferencesKey("artwork_colors")
        val minDuration = intPreferencesKey("min_duration")
        val hiddenFolders = stringSetPreferencesKey("hidden_folders")
        val mood = stringPreferencesKey("mood")
        val songSort = stringPreferencesKey("song_sort")
        val autoAi = booleanPreferencesKey("auto_ai_lyrics")
        val aiOnlyCharging = booleanPreferencesKey("ai_only_while_charging")
        val aiQueue = stringPreferencesKey("ai_queue")
        val auddToken = stringPreferencesKey("audd_token")
        val autoFavDiscovered = booleanPreferencesKey("auto_favorite_discovered")
        val discoveredCovers = booleanPreferencesKey("use_discovered_covers")
        val preferOriginal = booleanPreferencesKey("prefer_original_lyrics")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val speed = floatPreferencesKey("speed")
        val pitch = floatPreferencesKey("pitch")
        val queue = stringPreferencesKey("queue")
        val queueIndex = intPreferencesKey("queue_index")
        val queuePosition = longPreferencesKey("queue_position")
    }

    val settings: StateFlow<Settings> = context.dataStore.data
        .map { p ->
            Settings(
                themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
                materialYou = p[Keys.materialYou] ?: false,
                artworkColors = p[Keys.artworkColors] ?: true,
                minDurationSec = p[Keys.minDuration] ?: 30,
                hiddenFolders = p[Keys.hiddenFolders] ?: emptySet(),
                moodId = p[Keys.mood] ?: "normal",
                songSort = p[Keys.songSort]?.let { runCatching { SongSort.valueOf(it) }.getOrNull() } ?: SongSort.TITLE,
                autoAiLyrics = p[Keys.autoAi] ?: false,
                aiOnlyWhileCharging = p[Keys.aiOnlyCharging] ?: true,
                auddToken = p[Keys.auddToken] ?: "",
                autoFavoriteDiscovered = p[Keys.autoFavDiscovered] ?: true,
                useDiscoveredCovers = p[Keys.discoveredCovers] ?: true,
                preferOriginalLyrics = p[Keys.preferOriginal] ?: false,
                skipSilence = p[Keys.skipSilence] ?: false,
                playbackSpeed = p[Keys.speed] ?: 1f,
                playbackPitch = p[Keys.pitch] ?: 1f,
                loaded = true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    private val writeScope = scope

    private fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit { block(it) } }
    }

    fun setThemeMode(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    fun setMaterialYou(on: Boolean) = edit { it[Keys.materialYou] = on }
    fun setArtworkColors(on: Boolean) = edit { it[Keys.artworkColors] = on }
    fun setMinDuration(sec: Int) = edit { it[Keys.minDuration] = sec }
    fun setMood(id: String) = edit { it[Keys.mood] = id }
    fun setSongSort(sort: SongSort) = edit { it[Keys.songSort] = sort.name }
    fun setAutoAiLyrics(on: Boolean) = edit { it[Keys.autoAi] = on }
    fun setAiOnlyWhileCharging(on: Boolean) = edit { it[Keys.aiOnlyCharging] = on }
    fun setAuddToken(token: String) = edit { it[Keys.auddToken] = token.trim() }
    fun setAutoFavoriteDiscovered(on: Boolean) = edit { it[Keys.autoFavDiscovered] = on }
    fun setUseDiscoveredCovers(on: Boolean) = edit { it[Keys.discoveredCovers] = on }

    fun saveAiQueue(ids: List<Long>) = edit { it[Keys.aiQueue] = ids.joinToString(",") }

    suspend fun loadAiQueue(): List<Long> =
        context.dataStore.data.first()[Keys.aiQueue]
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
    fun setPreferOriginalLyrics(on: Boolean) = edit { it[Keys.preferOriginal] = on }
    fun setSkipSilence(on: Boolean) = edit { it[Keys.skipSilence] = on }
    fun setSpeed(speed: Float) = edit { it[Keys.speed] = speed }
    fun setPitch(pitch: Float) = edit { it[Keys.pitch] = pitch }

    fun hideFolder(path: String) = edit { it[Keys.hiddenFolders] = (it[Keys.hiddenFolders] ?: emptySet()) + path }
    fun unhideFolder(path: String) = edit { it[Keys.hiddenFolders] = (it[Keys.hiddenFolders] ?: emptySet()) - path }

    fun saveQueue(queue: SavedQueue) = edit {
        it[Keys.queue] = queue.songIds.joinToString("\n")
        it[Keys.queueIndex] = queue.index
        it[Keys.queuePosition] = queue.positionMs
    }

    suspend fun loadQueue(): SavedQueue? {
        val p = context.dataStore.data.first()
        val ids = p[Keys.queue]?.split("\n")?.filter { it.isNotBlank() } ?: return null
        if (ids.isEmpty()) return null
        return SavedQueue(ids, p[Keys.queueIndex] ?: 0, p[Keys.queuePosition] ?: 0L)
    }

    suspend fun awaitLoaded(): Settings = settings.first { it.loaded }
}
