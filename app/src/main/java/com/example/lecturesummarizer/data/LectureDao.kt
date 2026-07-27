package com.example.lecturesummarizer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {
    // --- Лекции ---
    @Query("SELECT * FROM lectures ORDER BY timestamp DESC")
    fun getAllLectures(): Flow<List<Lecture>>

    @Query("SELECT * FROM lectures WHERE folderId = :folderId ORDER BY timestamp DESC")
    fun getLecturesByFolder(folderId: Int): Flow<List<Lecture>>

    @Query("SELECT * FROM lectures WHERE folderId IS NULL ORDER BY timestamp DESC")
    fun getUncategorizedLectures(): Flow<List<Lecture>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLecture(lecture: Lecture)

    @Update
    suspend fun updateLecture(lecture: Lecture)

    @Delete
    suspend fun deleteLecture(lecture: Lecture)

    @Query("UPDATE lectures SET folderId = :folderId WHERE id = :lectureId")
    suspend fun updateLectureFolder(lectureId: Int, folderId: Int?)

    // --- Папки ---
    @Query("SELECT * FROM lectures WHERE title LIKE :query OR summary LIKE :query OR transcript LIKE :query ORDER BY timestamp DESC")
    fun searchLectures(query: String): Flow<List<Lecture>>

    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)
    
    @Query("DELETE FROM lectures WHERE folderId = :folderId")
    suspend fun deleteLecturesInFolder(folderId: Int)

    @Query("SELECT * FROM lectures ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLecture(): Lecture?
}
