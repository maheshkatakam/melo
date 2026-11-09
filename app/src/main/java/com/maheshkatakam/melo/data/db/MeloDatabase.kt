package com.maheshkatakam.melo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayStatEntity::class,
        MoodStatEntity::class,
        LyricsEntity::class,
        CustomMoodEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MeloDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun stats(): StatsDao
    abstract fun lyrics(): LyricsDao
    abstract fun customMoods(): CustomMoodDao

    companion object {
        fun build(context: Context): MeloDatabase =
            Room.databaseBuilder(context, MeloDatabase::class.java, "melo.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
