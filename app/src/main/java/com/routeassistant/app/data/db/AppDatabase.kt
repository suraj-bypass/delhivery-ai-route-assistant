package com.routeassistant.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.routeassistant.app.data.dao.ParcelDao
import com.routeassistant.app.data.entity.ParcelEntity

/**
 * v1 -> v2: urgent flag, COD amount, cash-collected flag.
 * REAL migration with defaults - existing rows keep their data.
 * Never use fallbackToDestructiveMigration: it would wipe the user's parcels.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE parcels ADD COLUMN isUrgent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE parcels ADD COLUMN codAmount REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE parcels ADD COLUMN cashCollected INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [ParcelEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun parcelDao(): ParcelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "route_assistant.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
