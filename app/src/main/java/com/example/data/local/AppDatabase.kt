package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity

@Database(
    entities = [TvDeviceEntity::class, TvAppEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tvDeviceDao(): TvDeviceDao
    abstract fun tvAppDao(): TvAppDao

    companion object {
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
                ).addMigrations(MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
