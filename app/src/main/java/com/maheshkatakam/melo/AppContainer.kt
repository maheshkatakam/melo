package com.maheshkatakam.melo

import android.app.Application
import coil.ImageLoader
import coil.memory.MemoryCache
import com.maheshkatakam.melo.art.ArtworkRepository
import com.maheshkatakam.melo.art.SongArtFetcher
import com.maheshkatakam.melo.art.SongArtKeyer
import com.maheshkatakam.melo.audio.AudioEffectsController
import com.maheshkatakam.melo.data.LibraryRepository
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.SongImporter
import com.maheshkatakam.melo.data.db.MeloDatabase
import com.maheshkatakam.melo.lyrics.LyricsRepository
import com.maheshkatakam.melo.lyrics.LyricsTranslator
import com.maheshkatakam.melo.lyrics.ai.AiLyricsEngine
import com.maheshkatakam.melo.playback.PlayerConnection
import com.maheshkatakam.melo.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container; one per app process. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: MeloDatabase = MeloDatabase.build(app)
    val settings = SettingsRepository(app, scope)
    val music = MusicRepository(app, scope, settings)
    val library = LibraryRepository(db, music, scope)
    val importer = SongImporter(app, music)
    val artwork = ArtworkRepository(app)
    val effects = AudioEffectsController(app, scope, settings, db.customMoods())
    val sleepTimer = SleepTimer()

    val translator = LyricsTranslator(app)
    val aiEngine = AiLyricsEngine(app, scope)
    val lyrics = LyricsRepository(app, db.lyrics(), music, translator, aiEngine, settings, scope)

    val player = PlayerConnection(app, scope, music, artwork)

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        .components {
            add(SongArtKeyer())
            add(SongArtFetcher.Factory(artwork))
        }
        .memoryCache {
            MemoryCache.Builder(app).maxSizePercent(0.2).build()
        }
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()
}
