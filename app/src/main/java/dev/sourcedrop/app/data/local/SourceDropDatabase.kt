package dev.sourcedrop.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sourcedrop.app.data.local.dao.TrackedAppDao
import dev.sourcedrop.app.data.local.dao.UpdateEventDao
import dev.sourcedrop.app.data.local.entity.TrackedApp
import dev.sourcedrop.app.data.local.entity.UpdateEvent

@Database(
    entities = [TrackedApp::class, UpdateEvent::class],
    version = 4,
    exportSchema = true
)
abstract class SourceDropDatabase : RoomDatabase() {

    abstract fun trackedAppDao(): TrackedAppDao
    abstract fun updateEventDao(): UpdateEventDao

    companion object {
        @Volatile
        private var instance: SourceDropDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `update_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackedAppId` INTEGER NOT NULL,
                        `detectedVersion` TEXT NOT NULL,
                        `releaseNotes` TEXT NOT NULL DEFAULT '',
                        `apkUrl` TEXT NOT NULL DEFAULT '',
                        `detectedAt` INTEGER NOT NULL,
                        `downloadStatus` TEXT NOT NULL DEFAULT 'none',
                        `installStatus` TEXT NOT NULL DEFAULT 'none',
                        FOREIGN KEY(`trackedAppId`) REFERENCES `tracked_apps`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_update_events_trackedAppId` ON `update_events` (`trackedAppId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `update_events` ADD COLUMN `downloadId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `update_events` ADD COLUMN `localApkPath` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracked_apps` ADD COLUMN `includePreReleases` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): SourceDropDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SourceDropDatabase::class.java,
                    "sourcedrop.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
        }
    }
}
