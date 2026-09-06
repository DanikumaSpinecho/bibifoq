package app.bibifoq.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per download, kept so the queue survives the process being killed - which on Android
 * happens routinely, and mid-download more often than not.
 */
@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val filePath: String,
    val formatId: String,
    val formatLabel: String,
    val state: DownloadState,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY createdAtMillis")
    suspend fun pending(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecord)

    @Query(
        "UPDATE downloads SET state = :state, downloadedBytes = :downloaded, " +
            "totalBytes = :total, errorMessage = :error, updatedAtMillis = :now WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        state: DownloadState,
        downloaded: Long,
        total: Long?,
        error: String?,
        now: Long,
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()

    /**
     * Anything left RUNNING when the app starts was interrupted by the process dying, not by
     * the user. Mark it so the UI offers a resume instead of showing a frozen progress bar.
     */
    @Query("UPDATE downloads SET state = 'FAILED', errorMessage = :reason WHERE state = 'RUNNING'")
    suspend fun markInterrupted(reason: String)
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = true)
abstract class BibifoqDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
}
