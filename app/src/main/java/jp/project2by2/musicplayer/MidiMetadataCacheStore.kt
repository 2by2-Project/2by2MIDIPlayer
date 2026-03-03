package jp.project2by2.musicplayer

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "midi_metadata_cache",
    indices = [
        Index(value = ["updated_at"])
    ]
)
data class MidiMetadataCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri_string") val uriString: String,
    val title: String?,
    val copyright: String?,
    @ColumnInfo(name = "loop_point_ms") val loopPointMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface MidiMetadataCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<MidiMetadataCacheEntity>)

    @Query("SELECT * FROM midi_metadata_cache WHERE uri_string = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): MidiMetadataCacheEntity?

    @Query("SELECT * FROM midi_metadata_cache WHERE uri_string IN (:uriStrings)")
    suspend fun getByUris(uriStrings: List<String>): List<MidiMetadataCacheEntity>
}

class MidiMetadataCacheRepository(private val dao: MidiMetadataCacheDao) {
    suspend fun get(uriString: String): MidiMetadata? {
        return dao.getByUri(uriString)?.toMidiMetadata()
    }

    suspend fun getByUris(uriStrings: List<String>): Map<String, MidiMetadata> {
        if (uriStrings.isEmpty()) return emptyMap()
        return uriStrings
            .distinct()
            .chunked(900)
            .flatMap { dao.getByUris(it) }
            .associate { entity ->
                entity.uriString to entity.toMidiMetadata()
            }
    }

    suspend fun put(uriString: String, metadata: MidiMetadata) {
        putAll(mapOf(uriString to metadata))
    }

    suspend fun putAll(entries: Map<String, MidiMetadata>) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.upsertAll(
            entries.map { (uriString, metadata) ->
                MidiMetadataCacheEntity(
                    uriString = uriString,
                    title = metadata.title,
                    copyright = metadata.copyright,
                    loopPointMs = metadata.loopPointMs,
                    durationMs = metadata.durationMs,
                    updatedAt = now
                )
            }
        )
    }
}

object MidiMetadataCacheStore {
    @Volatile
    private var repository: MidiMetadataCacheRepository? = null

    fun repository(context: Context): MidiMetadataCacheRepository {
        return repository ?: synchronized(this) {
            repository ?: MidiMetadataCacheRepository(
                PlaylistDatabase.get(context).midiMetadataCacheDao()
            ).also { repository = it }
        }
    }
}

fun MidiMetadataCacheEntity.toMidiMetadata(): MidiMetadata {
    return MidiMetadata(
        title = title,
        copyright = copyright,
        loopPointMs = loopPointMs,
        durationMs = durationMs
    )
}
