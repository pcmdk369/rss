package com.pcmdk369.superioa.memory

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalKey: String,
    val appContext: String,
    val version: Int,
    val stepsJson: String,
    val successCount: Int,
    val failureCount: Int,
    val lastUsed: Long,
)

@Entity(tableName = "task_logs")
data class TaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val status: String,
    val engine: Int,
    val summary: String,
    val startedAt: Long,
    val endedAt: Long,
)

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows WHERE goalKey = :key LIMIT 1")
    suspend fun find(key: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(w: WorkflowEntity): Long

    @Query("SELECT COUNT(*) FROM workflows")
    fun count(): Flow<Int>

    @Query("DELETE FROM workflows")
    suspend fun clear()
}

@Dao
interface TaskLogDao {
    @Insert
    suspend fun insert(l: TaskLogEntity): Long

    @Query("SELECT * FROM task_logs ORDER BY startedAt DESC LIMIT 100")
    fun recent(): Flow<List<TaskLogEntity>>

    @Query("DELETE FROM task_logs")
    suspend fun clear()
}

@Database(entities = [WorkflowEntity::class, TaskLogEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun workflows(): WorkflowDao
    abstract fun logs(): TaskLogDao
}
