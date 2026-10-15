package com.routeassistant.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.routeassistant.app.data.dao.ParcelDao
import com.routeassistant.app.data.entity.ParcelEntity

@Database(entities = [ParcelEntity::class], version = 1, exportSchema = false)
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
                    // Prototype only: schema is still evolving. In production,
                    // replace with explicit migrations so user data is never lost.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
