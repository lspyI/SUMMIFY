package com.example.lecturesummarizer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lectures")
data class Lecture(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val summary: String,
    val transcript: String,
    val keywords: String,
    val timestamp: Long,
    val folderId: Int? = null,
    val hasBookmarks: Boolean = false,
    val audioPath: String? = null // Путь к файлу аудиозаписи
)
