package com.maheshkatakam.melo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT songId FROM favorites ORDER BY addedAt DESC")
    fun observeIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun remove(songId: Long)

    @Query("SELECT COUNT(*) FROM favorites WHERE songId = :songId")
    suspend fun count(songId: Long): Int
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    fun observeEntries(): Flow<List<PlaylistSongEntity>>

    @Insert
    suspend fun insertPlaylist(entity: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :id AND songId = :songId")
    suspend fun contains(id: Long, songId: Long): Int

    @Insert
    suspend fun insertEntries(entries: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND songId = :songId")
    suspend fun removeSong(id: Long, songId: Long)

    @Transaction
    suspend fun deleteWithEntries(id: Long) {
        clearEntries(id)
        deletePlaylist(id)
    }
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM play_stats")
    fun observePlayStats(): Flow<List<PlayStatEntity>>

    @Query("SELECT * FROM play_stats WHERE songId = :songId")
    suspend fun getPlayStat(songId: Long): PlayStatEntity?

    @Upsert
    suspend fun upsertPlayStat(entity: PlayStatEntity)

    @Query("SELECT * FROM mood_stats WHERE moodId = :moodId ORDER BY plays DESC, lastPlayedAt DESC")
    suspend fun moodStats(moodId: String): List<MoodStatEntity>

    @Query("SELECT * FROM mood_stats WHERE songId = :songId AND moodId = :moodId")
    suspend fun getMoodStat(songId: Long, moodId: String): MoodStatEntity?

    @Upsert
    suspend fun upsertMoodStat(entity: MoodStatEntity)

    @Query("SELECT moodId, COUNT(*) AS songs FROM mood_stats GROUP BY moodId")
    fun observeMoodCounts(): Flow<List<MoodCount>>
}

data class MoodCount(val moodId: String, val songs: Int)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    fun observe(songId: Long): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: Long): LyricsEntity?

    @Upsert
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun delete(songId: Long)

    @Query("DELETE FROM lyrics WHERE source = 'none'")
    suspend fun deleteMisses()

    @Query("SELECT songId FROM lyrics WHERE source = 'ai' OR english IS NOT NULL")
    suspend fun songsWithEnglish(): List<Long>

    @Query("SELECT COUNT(*) FROM lyrics WHERE source = 'ai'")
    fun observeAiCount(): Flow<Int>

    @Query("SELECT songId, source, (english IS NOT NULL) AS hasEnglish FROM lyrics")
    fun observeStatus(): Flow<List<LyricsStatus>>

    @Query("SELECT songId, source, (english IS NOT NULL) AS hasEnglish FROM lyrics")
    suspend fun statusList(): List<LyricsStatus>
}

data class LyricsStatus(val songId: Long, val source: String, val hasEnglish: Boolean)

@Dao
interface CustomMoodDao {
    @Query("SELECT * FROM custom_moods ORDER BY createdAt")
    fun observeAll(): Flow<List<CustomMoodEntity>>

    @Upsert
    suspend fun upsert(entity: CustomMoodEntity)

    @Query("DELETE FROM custom_moods WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DiscoveryDao {
    @Query("SELECT * FROM discoveries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DiscoveryEntity>>

    @Query("SELECT * FROM discoveries WHERE id = :id")
    suspend fun get(id: Long): DiscoveryEntity?

    @Query("SELECT * FROM discoveries WHERE status = 'pending' ORDER BY createdAt")
    suspend fun pending(): List<DiscoveryEntity>

    @Insert
    suspend fun insert(entity: DiscoveryEntity): Long

    @Update
    suspend fun update(entity: DiscoveryEntity)

    @Query("UPDATE discoveries SET matchedSongId = :songId WHERE id = :id")
    suspend fun setMatch(id: Long, songId: Long)

    @Query("DELETE FROM discoveries WHERE id = :id")
    suspend fun delete(id: Long)
}
