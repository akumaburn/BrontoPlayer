package com.aemake.brontoplayer.m4b

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Mp4ChapterParserTest {

    private val chapterMs = 30_000L

    private fun chapters(n: Int, titler: (Int) -> String = { "Chapter ${it + 1}" }) =
        (0 until n).map { Mp4Builder.ChapterSpec(titler(it), it * chapterMs) }

    private fun parse(bytes: ByteArray): M4bMetadata =
        ByteArraySource(bytes).use { Mp4ChapterParser.parse(it) }

    // ---------------------------------------------------------------------------------------------
    // The headline requirement: more than 255 chapters.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun parses300ChapterTextTrack_singleChunkStco() {
        val n = 300
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs)
        val meta = parse(bytes)

        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(300, meta.chapters.size)
        assertEquals("Chapter 1", meta.chapters.first().title)
        assertEquals(0L, meta.chapters.first().startMs)
        assertEquals("Chapter 300", meta.chapters.last().title)
        assertEquals(299 * chapterMs, meta.chapters.last().startMs)
        assertEquals(300 * chapterMs, meta.chapters.last().endMs)
        // Every chapter index is sequential and titles line up.
        meta.chapters.forEachIndexed { i, ch ->
            assertEquals(i, ch.index)
            assertEquals("Chapter ${i + 1}", ch.title)
            assertEquals(i * chapterMs, ch.startMs)
        }
    }

    @Test
    fun parses300ChaptersWithCo64Offsets() {
        val n = 300
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs, offsetMode = Mp4Builder.OffsetMode.CO64)
        val meta = parse(bytes)
        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(300, meta.chapters.size)
        assertEquals("Chapter 250", meta.chapters[249].title)
        assertEquals(249 * chapterMs, meta.chapters[249].startMs)
    }

    @Test
    fun parses300ChaptersWithMultipleChunks() {
        val n = 300
        val bytes = Mp4Builder.build(
            chapters(n), durationMs = n * chapterMs, offsetMode = Mp4Builder.OffsetMode.MULTI_CHUNK_STCO,
        )
        val meta = parse(bytes)
        assertEquals(300, meta.chapters.size)
        // Spot-check a middle chapter to ensure stsc/stco walking is correct.
        assertEquals("Chapter 178", meta.chapters[177].title)
        assertEquals(177 * chapterMs, meta.chapters[177].startMs)
    }

    @Test
    fun handlesVeryLargeChapterCount() {
        val n = 2_000
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs)
        val meta = parse(bytes)
        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(2_000, meta.chapters.size)
        assertEquals("Chapter 2000", meta.chapters.last().title)
        assertEquals(1_999 * chapterMs, meta.chapters.last().startMs)
    }

    @Test
    fun textTrackIsPreferredOverChplWhenBothPresent() {
        val n = 300
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs, includeChpl = true)
        val meta = parse(bytes)
        // Even though a (truncated) chpl exists, the unlimited text track must win.
        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(300, meta.chapters.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Nero chpl fallback and its inherent 255 limit (documents WHY the text track matters).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fallsBackToNeroChplWhenNoTextTrack() {
        val n = 200
        val bytes = Mp4Builder.build(
            chapters(n), durationMs = n * chapterMs, includeTextTrack = false, includeChpl = true,
        )
        val meta = parse(bytes)
        assertEquals(ChapterSource.NERO_CHPL, meta.chapterSource)
        assertEquals(200, meta.chapters.size)
        assertEquals("Chapter 1", meta.chapters.first().title)
        assertEquals(199 * chapterMs, meta.chapters.last().startMs)
    }

    @Test
    fun neroChplCannotRepresentMoreThan255Chapters() {
        // A chpl-only file with 300 chapters can encode at most 255 (single-byte count).
        // This is exactly the failure mode the text-track path avoids.
        val n = 300
        val bytes = Mp4Builder.build(
            chapters(n), durationMs = n * chapterMs, includeTextTrack = false, includeChpl = true,
        )
        val meta = parse(bytes)
        assertEquals(ChapterSource.NERO_CHPL, meta.chapterSource)
        assertEquals(255, meta.chapters.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Title decoding
    // ---------------------------------------------------------------------------------------------

    @Test
    fun decodesUtf8UnicodeTitles() {
        val titles = listOf("Prólogo", "Глава 1", "第二章", "Chapter 📖")
        val specs = titles.mapIndexed { i, t -> Mp4Builder.ChapterSpec(t, i * chapterMs) }
        val bytes = Mp4Builder.build(specs, durationMs = titles.size * chapterMs)
        val meta = parse(bytes)
        assertEquals(titles, meta.chapters.map { it.title })
    }

    @Test
    fun decodesUtf16TitlesWithBom() {
        val titles = listOf("Préface", "Кафедра", "終わり")
        val specs = titles.mapIndexed { i, t -> Mp4Builder.ChapterSpec(t, i * chapterMs) }
        val bytes = Mp4Builder.build(specs, durationMs = titles.size * chapterMs, utf16Titles = true)
        val meta = parse(bytes)
        assertEquals(titles, meta.chapters.map { it.title })
    }

    @Test
    fun blankTitlesGetSyntheticNames() {
        val specs = listOf(
            Mp4Builder.ChapterSpec("", 0),
            Mp4Builder.ChapterSpec("Real Title", chapterMs),
        )
        val bytes = Mp4Builder.build(specs, durationMs = 2 * chapterMs)
        val meta = parse(bytes)
        assertEquals("Chapter 1", meta.chapters[0].title)
        assertEquals("Real Title", meta.chapters[1].title)
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata, cover art, container quirks
    // ---------------------------------------------------------------------------------------------

    @Test
    fun parsesMetadataTagsAndCover() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16) { it.toByte() }
        val bytes = Mp4Builder.build(
            chapters(5), durationMs = 5 * chapterMs,
            title = "My Book", author = "Jane Doe", narrator = "John Reader", album = "Series One",
            cover = png to 14,
        )
        val meta = parse(bytes)
        assertEquals("My Book", meta.title)
        assertEquals("Jane Doe", meta.author)
        assertEquals("John Reader", meta.narrator)
        assertEquals("Series One", meta.album)
        val cover = meta.coverArt
        assertNotNull(cover)
        assertEquals("image/png", cover?.mimeType)
        assertArrayEquals(png, cover?.bytes)
    }

    @Test
    fun parsesQuickTimeStyleMetaBox() {
        val bytes = Mp4Builder.build(
            chapters(3), durationMs = 3 * chapterMs, metaStyle = Mp4Builder.MetaStyle.QUICKTIME, title = "QT Book",
        )
        val meta = parse(bytes)
        assertEquals("QT Book", meta.title)
        assertEquals(3, meta.chapters.size)
    }

    @Test
    fun findsChapterTrackEvenWithoutTrefReference() {
        val bytes = Mp4Builder.build(chapters(10), durationMs = 10 * chapterMs, includeTref = false)
        val meta = parse(bytes)
        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(10, meta.chapters.size)
    }

    @Test
    fun chapterEndTimesAreContiguousAndBounded() {
        val n = 50
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs)
        val meta = parse(bytes)
        for (i in 0 until n - 1) {
            assertEquals(meta.chapters[i].endMs, meta.chapters[i + 1].startMs)
        }
        assertEquals(n * chapterMs, meta.chapters.last().endMs)
        meta.chapters.forEach { assertTrue(it.endMs >= it.startMs) }
    }

    @Test
    fun reportsNoChaptersWhenNonePresent() {
        val bytes = Mp4Builder.build(
            chapters(4), durationMs = 4 * chapterMs, includeTextTrack = false, includeChpl = false,
        )
        val meta = parse(bytes)
        assertEquals(ChapterSource.NONE, meta.chapterSource)
        assertTrue(meta.chapters.isEmpty())
        // Metadata/duration should still be available.
        assertEquals(4 * chapterMs, meta.durationMs)
        assertEquals("The Test Audiobook", meta.title)
    }

    @Test
    fun parsesViaRandomAccessFileSource() {
        val n = 300
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs)
        val tmp = File.createTempFile("bronto-test", ".m4b")
        try {
            tmp.writeBytes(bytes)
            val meta = Mp4ChapterParser.parse(tmp)
            assertEquals(300, meta.chapters.size)
            assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun throwsOnNonMp4Input() {
        val notMp4 = "this is definitely not an mp4 file, no moov here".toByteArray()
        try {
            parse(notMp4)
            fail("Expected Mp4ParseException")
        } catch (e: Mp4ParseException) {
            assertTrue(e.message!!.contains("moov"))
        }
    }

    @Test
    fun durationAndCoverAbsentAreNullSafe() {
        val bytes = Mp4Builder.build(chapters(2), durationMs = 2 * chapterMs, cover = null)
        val meta = parse(bytes)
        assertNull(meta.coverArt)
    }

    // ---------------------------------------------------------------------------------------------
    // Robustness against corrupt sample tables (must not crash; should degrade gracefully).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun corruptHugeSampleCountIsBoundedAndDoesNotCrash() {
        val n = 300
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs)
        // Overwrite the chapter track's stsz sample_count with 0xFFFFFFFF (would be a negative Int /
        // multi-GB allocation without bounds). Layout after the "stsz" type: version/flags(4),
        // sample_size(4), sample_count(4).
        val stsz = lastIndexOfFourCc(bytes, "stsz")
        assertTrue("stsz present", stsz >= 0)
        for (k in 0 until 4) bytes[stsz + 12 + k] = 0xFF.toByte()

        // Must not throw (NegativeArraySize / OOM / AIOOBE); count is clamped to the real payload.
        val meta = parse(bytes)
        assertEquals(ChapterSource.TEXT_TRACK, meta.chapterSource)
        assertEquals(300, meta.chapters.size)
    }

    @Test
    fun unusableChapterTrackFallsBackToNeroChpl() {
        val n = 200
        val bytes = Mp4Builder.build(chapters(n), durationMs = n * chapterMs, includeChpl = true)
        // Corrupt the chapter track's stsc box type so the text track is unusable.
        val stsc = lastIndexOfFourCc(bytes, "stsc")
        assertTrue("stsc present", stsc >= 0)
        "xxxx".toByteArray(Charsets.US_ASCII).copyInto(bytes, stsc)

        val meta = parse(bytes)
        assertEquals(ChapterSource.NERO_CHPL, meta.chapterSource)
        assertEquals(200, meta.chapters.size)
    }

    private fun lastIndexOfFourCc(bytes: ByteArray, fourCc: String): Int {
        val pat = fourCc.toByteArray(Charsets.US_ASCII)
        for (i in bytes.size - 4 downTo 0) {
            if (bytes[i] == pat[0] && bytes[i + 1] == pat[1] && bytes[i + 2] == pat[2] && bytes[i + 3] == pat[3]) {
                return i
            }
        }
        return -1
    }
}
