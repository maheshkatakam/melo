package com.maheshkatakam.melo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayStatEntity::class,
        MoodStatEntity::class,
        LyricsEntity::class,
        CustomMoodEntity::class,
        DiscoveryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MeloDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun playlists(): PlaylistDao
    abstract fun stats(): StatsDao
    abstract fun lyrics(): LyricsDao
    abstract fun customMoods(): CustomMoodDao
    abstract fun discoveries(): DiscoveryDao

    companion object {
        /** 1.0.5: adds the "Discovered" songs table. Everything else is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `discoveries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`title` TEXT, " +
                        "`artist` TEXT, " +
                        "`album` TEXT, " +
                        "`releaseDate` TEXT, " +
                        "`artworkPath` TEXT, " +
                        "`songLink` TEXT, " +
                        "`spotifyUrl` TEXT, " +
                        "`appleMusicUrl` TEXT, " +
                        "`clipPath` TEXT, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`matchedSongId` INTEGER)",
                )
            }
        }

        fun build(context: Context): MeloDatabase =
            Room.databaseBuilder(context, MeloDatabase::class.java, "melo.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
