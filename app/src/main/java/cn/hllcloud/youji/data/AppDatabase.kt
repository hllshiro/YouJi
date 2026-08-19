package cn.hllcloud.youji.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.hllcloud.youji.data.dao.PhotoDao
import cn.hllcloud.youji.data.dao.TravelNoteDao
import cn.hllcloud.youji.data.dao.WorkflowPhaseResultDao
import cn.hllcloud.youji.data.dao.WorkflowTaskDao
import cn.hllcloud.youji.data.dao.WritingStyleDao
import cn.hllcloud.youji.data.entity.PhotoEntity
import cn.hllcloud.youji.data.entity.TravelNoteEntity
import cn.hllcloud.youji.data.entity.WorkflowPhaseResultEntity
import cn.hllcloud.youji.data.entity.WorkflowTaskEntity
import cn.hllcloud.youji.data.entity.WritingStyleEntity

@Database(
    entities = [
        PhotoEntity::class,
        TravelNoteEntity::class,
        WorkflowTaskEntity::class,
        WorkflowPhaseResultEntity::class,
        WritingStyleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun travelNoteDao(): TravelNoteDao
    abstract fun workflowTaskDao(): WorkflowTaskDao
    abstract fun workflowPhaseResultDao(): WorkflowPhaseResultDao
    abstract fun writingStyleDao(): WritingStyleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * V1 → V2 迁移：
         * - 新增 workflow_tasks / workflow_phase_results / writing_styles 三张表
         * - photos 增列 workflowTaskId
         * - travel_notes 增列 writingStyleId / writingStyleName / workflowTaskId
         *
         * 注意：列名沿用 camelCase 以与实体字段保持一致（项目既有约定，
         * 如 PhotoEntity.travelNoteId 列名亦为 travelNoteId），并保证 PhotoDao
         * 中 `WHERE workflowTaskId = :taskId` 等查询可用。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE workflow_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        currentPhase TEXT NOT NULL DEFAULT 'prepare',
                        currentPhaseIndex INTEGER NOT NULL DEFAULT 0,
                        totalPhases INTEGER NOT NULL DEFAULT 5,
                        geocodeDoneCount INTEGER NOT NULL DEFAULT 0,
                        geocodeTotalCount INTEGER NOT NULL DEFAULT 0,
                        selectedStyleId INTEGER,
                        selectedStyleName TEXT,
                        inputPhotoPaths TEXT NOT NULL,
                        createdNoteId INTEGER,
                        errorMessage TEXT,
                        hasPendingEdit INTEGER NOT NULL DEFAULT 0,
                        lastDiffJson TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE workflow_phase_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        phase TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        resultJson TEXT,
                        errorMessage TEXT,
                        phaseVersion INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_workflow_phase_results_taskId_phase " +
                        "ON workflow_phase_results(taskId, phase)"
                )
                db.execSQL(
                    """
                    CREATE TABLE writing_styles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        promptGuideline TEXT NOT NULL,
                        openingTone TEXT,
                        closingTone TEXT,
                        isBuiltin INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 允许 null：老数据的照片都已关联游记，workflowTaskId 始终为 null
                db.execSQL("ALTER TABLE photos ADD COLUMN workflowTaskId INTEGER")
                db.execSQL("ALTER TABLE travel_notes ADD COLUMN writingStyleId INTEGER")
                db.execSQL("ALTER TABLE travel_notes ADD COLUMN writingStyleName TEXT")
                db.execSQL("ALTER TABLE travel_notes ADD COLUMN workflowTaskId INTEGER")
            }
        }

        /**
         * 数据库首次创建时注入内置"纪实""美化"两种风格。
         * 注意：onCreate 仅在数据库首次创建时执行，不会在 migration 时执行，
         * 因此从 V1 升级到 V2 的存量用户不会触发此处插入（如需补充可另行处理）。
         */
        private val ON_CREATE_CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // createdAt 用 SQLite 当前毫秒时间戳，避免依赖宿主层传入时间
                val nowExpr = "CAST(strftime('%s', 'now') AS INTEGER) * 1000"
                db.execSQL(
                    "INSERT INTO writing_styles " +
                        "(name, promptGuideline, openingTone, closingTone, isBuiltin, createdAt) " +
                        "VALUES (" +
                        "'纪实', " +
                        "'如实记录旅行经历，客观平实，注重时间地点事件的准确性', " +
                        "NULL, NULL, 1, " +
                        "$nowExpr" +
                        ")"
                )
                db.execSQL(
                    "INSERT INTO writing_styles " +
                        "(name, promptGuideline, openingTone, closingTone, isBuiltin, createdAt) " +
                        "VALUES (" +
                        "'美化', " +
                        "'在纪实基础上适当文学化，融入情感和意境，语言优美但不失真', " +
                        "NULL, NULL, 1, " +
                        "$nowExpr" +
                        ")"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "youji_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(ON_CREATE_CALLBACK)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
