package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.MacLinkEntity
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity

@Database(
    entities = [TvDeviceEntity::class, TvAppEntity::class, MacLinkEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun macLinkDao(): MacLinkDao
    abstract fun tvDeviceDao(): TvDeviceDao
    abstract fun tvAppDao(): TvAppDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS mac_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    deviceId INTEGER NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL,
                    FOREIGN KEY(deviceId) REFERENCES tv_devices(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mac_links_deviceId ON mac_links(deviceId)")
            }
        }

        // Add the new shortcut once, keeping existing shortcuts and later user deletions intact.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO tv_apps (
                        deviceId, appName, appIdentifier, category, colorHex, iconKey,
                        deepLinkUrl, isFavorite, launchCount, lastLaunched, sortOrder
                    )
                    SELECT id, 'TvDimmer - NightCompanion', 'com.tribalapps.tvdimmer',
                           'Tools', 4284704497, 'tv', '', 0, 0, 0, 10
                    FROM tv_devices
                    WHERE deviceType = 'ANDROID_TV'
                      AND NOT EXISTS (
                          SELECT 1 FROM tv_apps
                          WHERE deviceId = tv_devices.id
                            AND appIdentifier = 'com.tribalapps.tvdimmer'
                      )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "omni_remote_database.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
