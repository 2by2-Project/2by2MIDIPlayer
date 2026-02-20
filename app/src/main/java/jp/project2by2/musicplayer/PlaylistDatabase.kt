package jp.project2by2.musicplayer

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlist_id"),
        Index(value = ["playlist_id", "position"])
    ]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "uri_string") val uriString: String,
    @ColumnInfo(name = "title_cache") val titleCache: String?,
    @ColumnInfo(name = "artist_cache") val artistCache: String?,
    @ColumnInfo(name = "artwork_uri_cache") val artworkUriCache: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long
)

data class PlaylistWithItems(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "playlist_id",
        entity = PlaylistItemEntity::class
    )
    val items: List<PlaylistItemEntity>
)

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC, id DESC")
    suspend fun listPlaylists(): List<PlaylistEntity>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistWithItems(playlistId: Long): PlaylistWithItems?

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET updated_at = :updatedAt WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAt: Long)
}

@Database(
    entities = [PlaylistEntity::class, PlaylistItemEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: PlaylistDatabase? = null

        fun get(context: Context): PlaylistDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlaylistDatabase::class.java,
                    "playlist.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlist_items ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val updatedAt: Long
)

data class PlaylistTrack(
    val uriString: String,
    val title: String?,
    val artist: String?,
    val artworkUri: String?,
    val durationMs: Long,
    val position: Int
)

class PlaylistRepository(private val dao: PlaylistDao) {
    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun listPlaylists(): List<PlaylistSummary> {
        return dao.listPlaylists().map { playlist ->
            val size = dao.getPlaylistWithItems(playlist.id)?.items?.size ?: 0
            PlaylistSummary(
                id = playlist.id,
                name = playlist.name,
                itemCount = size,
                updatedAt = playlist.updatedAt
            )
        }
    }

    suspend fun addItems(
        playlistId: Long,
        tracks: List<PlaylistTrack>
    ) {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        var position = dao.getMaxPosition(playlistId).coerceAtLeast(-1) + 1
        val rows = tracks.map { track ->
            PlaylistItemEntity(
                playlistId = playlistId,
                uriString = track.uriString,
                titleCache = track.title,
                artistCache = track.artist,
                artworkUriCache = track.artworkUri,
                durationMs = track.durationMs.coerceAtLeast(0L),
                position = position++,
                addedAt = now
            )
        }
        dao.insertPlaylistItems(rows)
        dao.touchPlaylist(playlistId, now)
    }

    suspend fun getPlaylistItems(playlistId: Long): List<PlaylistTrack> {
        val rel = dao.getPlaylistWithItems(playlistId) ?: return emptyList()
        return rel.items
            .sortedBy { it.position }
            .map {
                PlaylistTrack(
                    uriString = it.uriString,
                    title = it.titleCache,
                    artist = it.artistCache,
                    artworkUri = it.artworkUriCache,
                    durationMs = it.durationMs,
                    position = it.position
                )
            }
    }

    suspend fun getPlaylistName(playlistId: Long): String? {
        return dao.getPlaylistWithItems(playlistId)?.playlist?.name
    }
}

object PlaylistStore {
    @Volatile
    private var repository: PlaylistRepository? = null

    fun repository(context: Context): PlaylistRepository {
        return repository ?: synchronized(this) {
            repository ?: PlaylistRepository(PlaylistDatabase.get(context).playlistDao())
                .also { repository = it }
        }
    }
}
