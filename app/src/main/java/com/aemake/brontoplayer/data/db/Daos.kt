package com.aemake.brontoplayer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET positionMs = :positionMs, currentChapterIndex = :chapterIndex, lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, chapterIndex: Int, playedAt: Long)

    @Query("UPDATE books SET speed = :speed WHERE id = :id")
    suspend fun updateSpeed(id: String, speed: Float)

    @Query("UPDATE books SET finished = :finished, positionMs = CASE WHEN :finished THEN 0 ELSE positionMs END WHERE id = :id")
    suspend fun updateFinished(id: String, finished: Boolean)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun observeForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun getForBook(bookId: String): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY positionMs ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
