package com.aemake.brontoplayer.m4b

/**
 * Where the chapter list was sourced from.
 *
 * [TEXT_TRACK] is the QuickTime/MP4 chapter *text track* referenced by the audio
 * track's `tref`/`chap`. Its sample counts are 32-bit, so it can represent an
 * effectively unlimited number of chapters (audiobooks with thousands of chapters
 * work correctly).
 *
 * [NERO_CHPL] is the Nero `chpl` box. Its on-disk format encodes the chapter count
 * in a **single byte**, so it can never describe more than 255 chapters. It is used
 * only as a fallback when no chapter text track is present.
 */
enum class ChapterSource { TEXT_TRACK, NERO_CHPL, NONE }

/** A single chapter/marker within an audiobook. Times are in milliseconds. */
data class M4bChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    /** Exclusive end; equals the next chapter's start, or the book duration for the last chapter. */
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Cover artwork bytes plus its MIME type, extracted from the `covr` atom. */
data class M4bCoverArt(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is M4bCoverArt) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

/** Parsed metadata for an M4B/MP4/M4A audiobook file. */
data class M4bMetadata(
    val title: String?,
    val author: String?,
    val narrator: String?,
    val album: String?,
    val durationMs: Long,
    val chapters: List<M4bChapter>,
    val coverArt: M4bCoverArt?,
    val chapterSource: ChapterSource,
)

/** Thrown when a file is not a parseable MP4/M4B container. */
class Mp4ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
