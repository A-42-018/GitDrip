package com.gitdrip.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects", indices = [Index(value = ["name"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,              // Termux project slug (projects/<name>)
    val repoUrl: String = "",
    val branch: String = "main",
    val paused: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val autoDrip: Boolean = false,           // FX4: random daily times when no fixed schedule is enabled
    @ColumnInfo(defaultValue = "10") val autoPerDay: Int = 10,
    @ColumnInfo(defaultValue = "'09:00'") val autoStart: String = "09:00",
    @ColumnInfo(defaultValue = "'22:00'") val autoEnd: String = "22:00",
)

/** FX4: one planned Auto Drip run. State PLANNED|SENT|SKIPPED|DONE|FAILED. Times are stored so a restart never reshuffles the day. */
@Entity(
    tableName = "auto_slots",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class AutoSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val day: String,                                   // yyyy-MM-dd, device zone
    val atMs: Long,
    val state: String = "PLANNED",
    @ColumnInfo(defaultValue = "''") val reason: String = "",
    @ColumnInfo(defaultValue = "''") val requestId: String = "",
)

@Entity(
    tableName = "batches",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val seq: Int,
    val message: String,
    val status: String = "PENDING",   // mirrors Termux batches.json
    val commitHash: String? = null,
    val origin: String = "",          // "" = created in the app, "watch" = appended by `gitdrip watch` (imported on sync)
)

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(BatchEntity::class, ["id"], ["batchId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("projectId"), Index("batchId"), Index(value = ["projectId", "path"], unique = true)],
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val path: String,              // relative path inside projects/<name>/source
    val size: Long,
    val sha256: String,
    val batchId: Long? = null,     // null = unassigned
)

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val time: String,                 // "HH:MM"
    val policy: String = "skip",      // skip | run-now (next-slot stays Termux-cron only)
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "''") val zone: String = "",        // IANA id; "" = device zone (v4)
    @ColumnInfo(defaultValue = "127") val days: Int = ALL_DAYS,    // bit0=Mon..bit6=Sun (v4)
    @ColumnInfo(defaultValue = "''") val lastFireDay: String = "", // yyyy-MM-dd in [zone] (v4)
)

@Entity(
    tableName = "executions",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class ExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val batchId: Long? = null,
    val taskId: String,               // Termux tasks/<id>.json (state of truth); blank until the result file reports it
    @ColumnInfo(defaultValue = "''") val requestId: String = "",   // bridge request = results/<requestId>.json (v3)
    val state: String,                // PENDING|RUNNING|SUCCESS|FAILED|SKIPPED|PENDING_RETRY
    val commitHash: String? = null,
    val reason: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val output: String = "",       // redacted last lines (v5)
    @ColumnInfo(defaultValue = "0") val filesChanged: Int = 0,      // (v5)
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,           // (v5)
    @ColumnInfo(defaultValue = "''") val errorClass: String = "",   // secret|auth|conflict|transient|fatal|unknown (v5)
    @ColumnInfo(defaultValue = "''") val nextRetryAt: String = "",  // ISO, PENDING_RETRY only (v5)
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC") fun all(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") fun byId(id: Long): Flow<ProjectEntity?>
    @Query("SELECT * FROM projects") suspend fun allNow(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun byIdNow(id: Long): ProjectEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(p: ProjectEntity): Long
    @Delete suspend fun delete(p: ProjectEntity)
    @Query("UPDATE projects SET autoDrip = :on, autoPerDay = :perDay, autoStart = :start, autoEnd = :end WHERE id = :id")
    suspend fun setAuto(id: Long, on: Boolean, perDay: Int, start: String, end: String)
}

@Dao
interface AutoSlotDao {
    @Insert suspend fun insert(s: AutoSlotEntity): Long
    @Insert suspend fun insertAll(s: List<AutoSlotEntity>)
    @Update suspend fun update(s: AutoSlotEntity)
    @Query("SELECT * FROM auto_slots WHERE id = :id") suspend fun byId(id: Long): AutoSlotEntity?
    @Query("SELECT * FROM auto_slots WHERE requestId = :r AND requestId != '' LIMIT 1") suspend fun byRequest(r: String): AutoSlotEntity?
    @Query("SELECT * FROM auto_slots WHERE projectId = :p AND day = :d ORDER BY atMs") suspend fun forDayNow(p: Long, d: String): List<AutoSlotEntity>
    @Query("SELECT * FROM auto_slots WHERE projectId = :p AND day = :d ORDER BY atMs") fun forDay(p: Long, d: String): Flow<List<AutoSlotEntity>>
    @Query("SELECT * FROM auto_slots WHERE state = 'PLANNED' ORDER BY atMs") suspend fun plannedNow(): List<AutoSlotEntity>
    @Query("SELECT * FROM auto_slots WHERE state = 'PLANNED' ORDER BY atMs") fun plannedFlow(): Flow<List<AutoSlotEntity>>
    @Query("SELECT * FROM auto_slots WHERE projectId = :p AND state = 'PLANNED' ORDER BY atMs LIMIT 1") suspend fun nextPlanned(p: Long): AutoSlotEntity?
    @Query("SELECT atMs FROM auto_slots WHERE day = :d AND state = 'PLANNED'") suspend fun plannedTimes(d: String): List<Long>
    @Query("SELECT COUNT(*) FROM auto_slots WHERE day = :d AND state = 'PLANNED' AND projectId != :p") suspend fun plannedOthers(d: String, p: Long): Int
    @Query("SELECT COUNT(*) FROM auto_slots WHERE projectId = :p AND day = :d AND state IN ('SENT','DONE','FAILED')") suspend fun consumed(p: Long, d: String): Int
    @Query("SELECT COUNT(*) FROM auto_slots WHERE projectId = :p AND day = :d AND state = 'PLANNED'") suspend fun plannedCount(p: Long, d: String): Int
    @Query("UPDATE auto_slots SET state = 'SKIPPED', reason = :why WHERE projectId = :p AND state = 'PLANNED'") suspend fun skipPlanned(p: Long, why: String)
    @Query("DELETE FROM auto_slots WHERE projectId = :p AND state = 'PLANNED'") suspend fun deletePlanned(p: Long)
    @Query("DELETE FROM auto_slots WHERE projectId = :p AND state = 'PLANNED' AND atMs > :after") suspend fun deletePlannedAfter(p: Long, after: Long)
    @Query("DELETE FROM auto_slots WHERE state = 'PLANNED' AND atMs < :before") suspend fun deleteOverdue(before: Long)
    @Query("DELETE FROM auto_slots WHERE day < :day") suspend fun pruneBefore(day: String)
}

@Dao
interface BatchDao {
    @Query("SELECT * FROM batches WHERE projectId = :p ORDER BY seq") fun batches(p: Long): Flow<List<BatchEntity>>
    @Query("SELECT * FROM batches WHERE projectId = :p ORDER BY seq") suspend fun batchesNow(p: Long): List<BatchEntity>
    @Query("SELECT * FROM files WHERE projectId = :p ORDER BY path") fun files(p: Long): Flow<List<FileEntity>>
    @Query("SELECT * FROM files WHERE projectId = :p ORDER BY path") suspend fun filesNow(p: Long): List<FileEntity>
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batchById(id: Long): BatchEntity?
    @Query("SELECT COUNT(*) FROM batches WHERE projectId = :p AND (status != 'PENDING' OR origin != '')") suspend fun started(p: Long): Int
    @Query("SELECT COALESCE(MAX(seq), 0) FROM batches WHERE projectId = :p") suspend fun maxSeq(p: Long): Int
    @Insert suspend fun insertBatch(b: BatchEntity): Long
    @Insert suspend fun insertFiles(f: List<FileEntity>)
    @Query("UPDATE files SET batchId = :batch WHERE id = :file") suspend fun assign(file: Long, batch: Long?)
    @Query("UPDATE files SET batchId = :batch WHERE projectId = :p AND path IN (:paths)")
    suspend fun assignPaths(p: Long, paths: List<String>, batch: Long)
    @Query("UPDATE batches SET seq = :seq WHERE id = :id") suspend fun setSeq(id: Long, seq: Int)
    @Query("UPDATE batches SET message = :m WHERE id = :id") suspend fun rename(id: Long, m: String)
    @Query("DELETE FROM batches WHERE id = :id") suspend fun deleteBatch(id: Long)
    @Query("DELETE FROM batches WHERE projectId = :p") suspend fun clearBatches(p: Long)
    @Query("DELETE FROM files WHERE projectId = :p") suspend fun clearFiles(p: Long)
    @Query("UPDATE batches SET status = :s, commitHash = :h WHERE id = :id") suspend fun setStatus(id: Long, s: String, h: String?)
}

@Dao
interface ExecDao {
    @Insert suspend fun insert(e: ExecutionEntity): Long
    @Update suspend fun update(e: ExecutionEntity)
    @Query("SELECT * FROM executions WHERE projectId = :p AND state IN ('PENDING','RUNNING') AND requestId != ''")
    suspend fun open(p: Long): List<ExecutionEntity>
    @Query("SELECT * FROM executions WHERE requestId = :r LIMIT 1") suspend fun byRequest(r: String): ExecutionEntity?
    /** Cap usage (D2): real commits finished today + runs genuinely in flight (fresh, with a request id). Everything else never counts. */
    @Query("SELECT (SELECT COUNT(*) FROM executions WHERE state = 'SUCCESS' AND commitHash IS NOT NULL AND commitHash != '' AND COALESCE(finishedAt, startedAt) >= :since) + " +
        "(SELECT COUNT(*) FROM executions WHERE state IN ('PENDING','RUNNING') AND requestId != '' AND startedAt >= :fresh)")
    suspend fun usedToday(since: Long, fresh: Long): Int
    @Query("DELETE FROM executions WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("SELECT * FROM executions WHERE state IN ('PENDING','RUNNING') AND requestId != ''") suspend fun openAll(): List<ExecutionEntity>
    @Query("SELECT * FROM executions WHERE projectId = :p ORDER BY startedAt DESC LIMIT 100")
    fun recent(p: Long): Flow<List<ExecutionEntity>>
    @Query("SELECT * FROM executions WHERE id = :id") fun byId(id: Long): Flow<ExecutionEntity?>
    @Query("SELECT * FROM executions WHERE taskId = :t AND taskId != '' LIMIT 1") suspend fun byTask(t: String): ExecutionEntity?
    /** App-started runs whose result file has not reported a task id yet (history sync adopts them instead of duplicating). */
    @Query("SELECT * FROM executions WHERE projectId = :p AND taskId = '' AND requestId != '' AND state IN ('PENDING','RUNNING')")
    suspend fun unbound(p: Long): List<ExecutionEntity>
    @Query("SELECT * FROM executions ORDER BY startedAt DESC LIMIT :n") fun latest(n: Int): Flow<List<ExecutionEntity>>
    @Query("SELECT COALESCE(finishedAt, startedAt) FROM executions WHERE state = 'SUCCESS' AND commitHash IS NOT NULL AND commitHash != '' AND startedAt >= :since")
    fun commitTimes(since: Long): Flow<List<Long>>
    @Query("SELECT COUNT(*) FROM executions WHERE state = 'PENDING_RETRY'") fun retrying(): Flow<Int>
}

@Dao
interface SchedDao {
    @Query("SELECT * FROM schedules WHERE projectId = :p ORDER BY time") fun forProject(p: Long): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules WHERE projectId = :p") suspend fun forProjectNow(p: Long): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun byId(id: Long): ScheduleEntity?
    @Query("SELECT * FROM schedules WHERE enabled = 1") suspend fun enabled(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE enabled = 1") fun enabledFlow(): Flow<List<ScheduleEntity>>
    @Insert suspend fun insert(s: ScheduleEntity): Long
    @Update suspend fun update(s: ScheduleEntity)
    @Delete suspend fun delete(s: ScheduleEntity)
    @Query("UPDATE schedules SET lastFireDay = :d WHERE id = :id") suspend fun markFired(id: Long, d: String)
}

@Dao
interface StatsDao {
    @Query("SELECT COUNT(*) FROM schedules WHERE enabled = 1") fun activeSchedules(): Flow<Int>
    @Query("SELECT COUNT(*) FROM executions WHERE state = :state AND startedAt >= :since")
    fun runs(state: String, since: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM batches WHERE projectId = :id AND status = 'PENDING'")
    fun pendingBatches(id: Long): Flow<Int>
}

@Database(
    entities = [ProjectEntity::class, BatchEntity::class, FileEntity::class, ScheduleEntity::class, ExecutionEntity::class, AutoSlotEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun stats(): StatsDao
    abstract fun batches(): BatchDao
    abstract fun executions(): ExecDao
    abstract fun schedules(): SchedDao
    abstract fun autoSlots(): AutoSlotDao

    companion object {
        fun build(c: Context): AppDb =
            Room.databaseBuilder(c, AppDb::class.java, "gitdrip.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
    }
}

/** v1 -> v2: adds the imported-files table (P9). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` INTEGER NOT NULL, `path` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                "`sha256` TEXT NOT NULL, `batchId` INTEGER, " +
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`batchId`) REFERENCES `batches`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_projectId` ON `files` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_batchId` ON `files` (`batchId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_files_projectId_path` ON `files` (`projectId`, `path`)")
    }
}

/** v2 -> v3: bridge request id on executions (P10). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `requestId` TEXT NOT NULL DEFAULT ''")
    }
}

/** v3 -> v4: schedule zone / weekday mask / last-fire day (P11). next-slot is not app-side, so it falls back to skip. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `schedules` ADD COLUMN `zone` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `schedules` ADD COLUMN `days` INTEGER NOT NULL DEFAULT 127")
        db.execSQL("ALTER TABLE `schedules` ADD COLUMN `lastFireDay` TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `schedules` SET `policy` = 'skip' WHERE `policy` = 'next-slot'")
    }
}

/** v4 -> v5: run details for the history screen (P12). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `output` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `filesChanged` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `attempt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `errorClass` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `executions` ADD COLUMN `nextRetryAt` TEXT NOT NULL DEFAULT ''")
    }
}

private val NAME_RE = Regex("^[a-z0-9][a-z0-9._-]{0,39}$")
private val REPO_RE = Regex("^https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(\\.git)?$")
private val BRANCH_RE = Regex("^[A-Za-z0-9._/-]{1,60}$")

/** Returns an error message, or null when valid. Mirrors engine rules (slug names, no creds in URL). */
fun validateProject(name: String, repo: String, branch: String): String? = when {
    !NAME_RE.matches(name) -> "Name: 1-40 chars, a-z 0-9 . _ - (start with letter/digit)"
    repo.isNotEmpty() && !REPO_RE.matches(repo) -> "Repo must look like https://github.com/owner/repo (no credentials)"
    !BRANCH_RE.matches(branch) -> "Invalid branch name"
    else -> null
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `batches` ADD COLUMN `origin` TEXT NOT NULL DEFAULT ''")
    }
}

/** v6 -> v7: Auto Drip settings on projects + the planned-slots table (FX4). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `autoDrip` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `autoPerDay` INTEGER NOT NULL DEFAULT 10")
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `autoStart` TEXT NOT NULL DEFAULT '09:00'")
        db.execSQL("ALTER TABLE `projects` ADD COLUMN `autoEnd` TEXT NOT NULL DEFAULT '22:00'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `auto_slots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` INTEGER NOT NULL, `day` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL DEFAULT '', `requestId` TEXT NOT NULL DEFAULT '', " +
                "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_auto_slots_projectId` ON `auto_slots` (`projectId`)")
    }
}
