package com.aemake.brontoplayer.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aemake.brontoplayer.data.db.BookEntity
import com.aemake.brontoplayer.data.db.BookmarkEntity
import com.aemake.brontoplayer.data.db.BrontoDatabase
import com.aemake.brontoplayer.data.db.ChapterEntity
import com.aemake.brontoplayer.data.io.UriSeekableSource
import com.aemake.brontoplayer.m4b.M4bMetadata
import com.aemake.brontoplayer.m4b.Mp4ChapterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Outcome of an import batch, surfaced to the UI. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failures: List<Pair<String, String>>,
)

/** Single source of truth for the audiobook library. */
class LibraryRepository(private val context: Context) {

    private val db = BrontoDatabase.get(context)
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val bookmarkDao = db.bookmarkDao()
    private val resolver get() = context.contentResolver
    private val coversDir: File by lazy { File(context.filesDir, "covers").apply { mkdirs() } }

    // ----- Observation -----
    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)
    fun observeChapters(id: String): Flow<List<ChapterEntity>> = chapterDao.observeForBook(id)
    fun observeBookmarks(id: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(id)

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)
    suspend fun getChapters(id: String): List<ChapterEntity> = chapterDao.getForBook(id)

    // ----- Import -----

    /** Imports individually-picked documents, persisting read permission for each. */
    suspend fun importDocuments(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        val failures = mutableListOf<Pair<String, String>>()
        for (uri in uris) {
            try {
                persistPermission(uri)
                when (importSingle(uri)) {
                    ImportOutcome.IMPORTED -> imported++
                    ImportOutcome.SKIPPED -> skipped++
                }
            } catch (e: Exception) {
                failures += displayName(uri) to (e.message ?: e.javaClass.simpleName)
            }
        }
        ImportResult(imported, skipped, failures)
    }

    /** Scans a picked folder (tree) for audiobook files and imports them. */
    suspend fun importFolder(treeUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        persistPermission(treeUri)
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ImportResult(0, 0, listOf(displayName(treeUri) to "Could not open folder"))
        val candidates = collectAudioFiles(tree)
        var imported = 0
        var skipped = 0
        val failures = mutableListOf<Pair<String, String>>()
        for (doc in candidates) {
            try {
                when (importSingle(doc.uri, doc.name)) {
                    ImportOutcome.IMPORTED -> imported++
                    ImportOutcome.SKIPPED -> skipped++
                }
            } catch (e: Exception) {
                failures += (doc.name ?: doc.uri.toString()) to (e.message ?: e.javaClass.simpleName)
            }
        }
        ImportResult(imported, skipped, failures)
    }

    private fun collectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                out += collectAudioFiles(child)
            } else if (child.isFile && isAudiobookFile(child.name, child.type)) {
                out += child
            }
        }
        return out
    }

    private fun isAudiobookFile(name: String?, mime: String?): Boolean {
        val n = name?.lowercase().orEmpty()
        if (n.endsWith(".m4b") || n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")) return true
        return mime != null && (mime.startsWith("audio/") || mime == "video/mp4")
    }

    private enum class ImportOutcome { IMPORTED, SKIPPED }

    private suspend fun importSingle(uri: Uri, knownName: String? = null): ImportOutcome {
        val id = uri.toString()
        if (bookDao.exists(id)) return ImportOutcome.SKIPPED

        val meta: M4bMetadata = UriSeekableSource(resolver, uri).use { Mp4ChapterParser.parse(it) }
        val name = knownName ?: displayName(uri)
        val coverPath = meta.coverArt?.let { saveCover(id, it.bytes) }

        val title = meta.title?.takeIf { it.isNotBlank() } ?: stripExtension(name)
        val now = System.currentTimeMillis()
        val book = BookEntity(
            id = id,
            title = title,
            author = meta.author,
            narrator = meta.narrator,
            album = meta.album,
            durationMs = meta.durationMs,
            coverPath = coverPath,
            chapterSource = meta.chapterSource.name,
            chapterCount = meta.chapters.size,
            addedAt = now,
        )
        bookDao.upsert(book)

        val chapters = if (meta.chapters.isEmpty()) {
            listOf(ChapterEntity(bookId = id, index = 0, title = title, startMs = 0L, endMs = meta.durationMs))
        } else {
            meta.chapters.map { ch ->
                ChapterEntity(bookId = id, index = ch.index, title = ch.title, startMs = ch.startMs, endMs = ch.endMs)
            }
        }
        chapterDao.deleteForBook(id)
        chapterDao.insertAll(chapters)
        return ImportOutcome.IMPORTED
    }

    private fun saveCover(bookId: String, bytes: ByteArray): String? = try {
        val file = File(coversDir, hash(bookId) + ".img")
        file.outputStream().use { it.write(bytes) }
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    // ----- Mutations -----

    suspend fun removeBook(book: BookEntity) = withContext(Dispatchers.IO) {
        book.coverPath?.let { runCatching { File(it).delete() } }
        runCatching { releasePermission(Uri.parse(book.id)) }
        bookDao.deleteById(book.id) // chapters & bookmarks cascade
    }

    suspend fun saveProgress(id: String, positionMs: Long, chapterIndex: Int) =
        bookDao.updateProgress(id, positionMs, chapterIndex, System.currentTimeMillis())

    suspend fun setSpeed(id: String, speed: Float) = bookDao.updateSpeed(id, speed)

    suspend fun setFinished(id: String, finished: Boolean) = bookDao.updateFinished(id, finished)

    suspend fun addBookmark(bookId: String, positionMs: Long, chapterIndex: Int, chapterTitle: String, note: String?): Long =
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId,
                positionMs = positionMs,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )

    suspend fun deleteBookmark(id: Long) = bookmarkDao.deleteById(id)
    suspend fun updateBookmarkNote(id: Long, note: String?) = bookmarkDao.updateNote(id, note)

    // ----- Helpers -----

    private fun persistPermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releasePermission(uri: Uri) {
        runCatching {
            // Only release a grant we actually hold. Folder imports persist the *tree* URI, not the
            // per-document child URIs, so releasing a child would throw; and a shared tree grant must
            // outlive sibling books. Releasing only directly-held grants avoids leaks and exceptions.
            val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (held) {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: uri.toString()

    private fun stripExtension(name: String): String =
        name.substringBeforeLast('.', name).ifBlank { name }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
