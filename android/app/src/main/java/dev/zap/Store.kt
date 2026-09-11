package dev.zap

import androidx.room.*

@Entity(tableName = "clips")
data class StoredClip(@PrimaryKey val id: String, val createdAt: Long, val pending: Boolean)
@Entity(tableName = "deletions")
data class Deletion(@PrimaryKey val id: String)
@Dao
interface ClipDao {
    @Query("SELECT * FROM clips ORDER BY createdAt DESC") suspend fun all(): List<StoredClip>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(clip: StoredClip)
    @Query("UPDATE clips SET pending=0 WHERE id=:id") suspend fun sent(id: String)
    @Query("DELETE FROM clips WHERE id=:id") suspend fun remove(id: String)
    @Query("DELETE FROM clips WHERE createdAt<=:cutoff") suspend fun expire(cutoff: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun queue(deletion: Deletion)
    @Query("SELECT id FROM deletions") suspend fun deletions(): List<String>
    @Query("DELETE FROM deletions WHERE id=:id") suspend fun deleted(id: String)
}
@Database(entities = [StoredClip::class, Deletion::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() { abstract fun clips(): ClipDao }
