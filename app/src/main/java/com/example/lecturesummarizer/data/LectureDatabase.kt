package com.example.lecturesummarizer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Lecture::class, Folder::class], version = 3, exportSchema = false)
abstract class LectureDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao

    companion object {
        @Volatile
        private var INSTANCE: LectureDatabase? = null

        fun getDatabase(context: Context): LectureDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LectureDatabase::class.java,
                    "lecture_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
