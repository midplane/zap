package dev.midplane.zap

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "clips")
data class StoredClip(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val pending: Boolean,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** The pin changed here and the server has not been told yet. */
    @ColumnInfo(defaultValue = "0") val pinDirty: Boolean = false
)

@Entity(tableName = "deletions")
data class Deletion(@PrimaryKey val id: String)

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips ORDER BY createdAt DESC")
    suspend fun all(): List<StoredClip>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(clip: StoredClip)

    @Query("UPDATE clips SET pending=0 WHERE id=:id")
    suspend fun sent(id: String)

    @Query("UPDATE clips SET pending=1")
    suspend fun markAllPending()

    @Query("UPDATE clips SET pinned=:pinned, pinDirty=:dirty WHERE id=:id")
    suspend fun setPinned(id: String, pinned: Boolean, dirty: Boolean)

    /** Applies the server's pin unless a local change is still on its way. */
    @Query("UPDATE clips SET pinned=:pinned WHERE id=:id AND pinDirty=0")
    suspend fun pinnedOnServer(id: String, pinned: Boolean)

    @Query("UPDATE clips SET pinDirty=0 WHERE id=:id")
    suspend fun pinSent(id: String)

    @Query("UPDATE clips SET pinDirty=0")
    suspend fun clearPinChanges()

    @Query("SELECT * FROM clips WHERE pinDirty=1")
    suspend fun pinChanges(): List<StoredClip>

    @Query("DELETE FROM clips WHERE id=:id")
    suspend fun remove(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun queue(deletion: Deletion)

    @Query("SELECT id FROM deletions")
    suspend fun deletions(): List<String>

    /** Marks a queued deletion as sent. */
    @Query("DELETE FROM deletions WHERE id=:id")
    suspend fun deleted(id: String)
}

@Database(entities = [StoredClip::class, Deletion::class], version = 2, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun clips(): ClipDao

    companion object {
        val ADD_PINS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clips ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clips ADD COLUMN pinDirty INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
