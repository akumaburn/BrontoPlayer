package com.aemake.brontoplayer.m4b

import java.io.File

/**
 * Extracts chapters and metadata from an MP4/M4B/M4A audiobook.
 *
 * ## Chapters beyond 255
 * Two chapter representations exist in the MP4 world:
 *
 *  1. **Chapter text track** (QuickTime / Apple): a dedicated `text` track whose
 *     samples are the chapter titles, referenced from the audio track via
 *     `tref`/`chap`. Sample counts in `stsz`/`stts`/`stco`/`co64` are all 32-bit,
 *     so this representation has no practical chapter-count limit.
 *  2. **Nero `chpl`**: a single box under `udta` whose chapter count field is a
 *     **single byte** — it physically cannot encode more than 255 chapters.
 *
 * This parser *prefers the chapter text track* and only falls back to `chpl` when no
 * text track is present. That is what makes audiobooks with hundreds or thousands of
 * chapters work correctly.
 *
 * The whole `moov` box is loaded into memory (it is small — kilobytes even for huge
 * chapter lists), while chapter title sample bytes (which live in `mdat`) are read
 * lazily from the [SeekableSource] at their absolute file offsets.
 */
object Mp4ChapterParser {

    /** Maximum `moov` size we will buffer into memory (sanity guard against corrupt files). */
    private const val MAX_MOOV_BYTES = 96L * 1024 * 1024

    /** Hard cap on sample-table entry counts; far above any real audiobook, guards against OOM. */
    private const val MAX_SAMPLES = 5_000_000L

    fun parse(file: File): M4bMetadata = RandomAccessFileSource(file).use { parse(it) }

    fun parse(source: SeekableSource): M4bMetadata {
        val moovLoc = findTopLevelBox(source, "moov")
            ?: throw Mp4ParseException("Not an MP4/M4B file: no 'moov' box found")
        if (moovLoc.size > MAX_MOOV_BYTES) {
            throw Mp4ParseException("'moov' box is implausibly large (${moovLoc.size} bytes)")
        }
        if (moovLoc.size < moovLoc.headerSize) {
            throw Mp4ParseException("Corrupt 'moov' box (size ${moovLoc.size})")
        }
        val moov = source.readBytes(moovLoc.offset, moovLoc.size.toInt())
        val moovContentStart = moovLoc.headerSize
        val moovEnd = moov.size

        val movie = MovieHeader()
        val tracks = ArrayList<Track>()
        var udtaTitle: String? = null
        var udtaAuthor: String? = null
        var udtaAlbumArtist: String? = null
        var udtaNarrator: String? = null
        var udtaAlbum: String? = null
        var cover: M4bCoverArt? = null
        var neroChapters: List<RawChapter>? = null

        forEachChild(moov, moovContentStart, moovEnd) { type, ps, pe ->
            when (type) {
                "mvhd" -> parseMvhd(moov, ps, movie)
                "trak" -> runCatching { parseTrak(moov, ps, pe) }.getOrNull()?.let { tracks.add(it) }
                "udta" -> {
                    forEachChild(moov, ps, pe) { utype, ups, upe ->
                        when (utype) {
                            "meta" -> {
                                val tags = parseMeta(moov, ups, upe)
                                udtaTitle = udtaTitle ?: tags.title
                                udtaAuthor = udtaAuthor ?: tags.artist
                                udtaAlbumArtist = udtaAlbumArtist ?: tags.albumArtist
                                udtaNarrator = udtaNarrator ?: tags.composer
                                udtaAlbum = udtaAlbum ?: tags.album
                                cover = cover ?: tags.cover
                            }
                            "chpl" -> neroChapters = parseNeroChpl(moov, ups, upe)
                        }
                    }
                }
            }
        }

        val durationMs = movie.durationMs()

        // ----- Resolve chapters: prefer the text chapter track -----
        val (rawChapters, chapterSource) = resolveChapters(source, tracks, neroChapters, durationMs)

        val chapters = finalizeChapters(rawChapters, durationMs)

        val author = udtaAuthor ?: udtaAlbumArtist
        return M4bMetadata(
            title = udtaTitle ?: udtaAlbum,
            author = author,
            narrator = udtaNarrator,
            album = udtaAlbum,
            durationMs = durationMs,
            chapters = chapters,
            coverArt = cover,
            chapterSource = chapterSource,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Chapter resolution
    // ---------------------------------------------------------------------------------------------

    private fun resolveChapters(
        source: SeekableSource,
        tracks: List<Track>,
        neroChapters: List<RawChapter>?,
        durationMs: Long,
    ): Pair<List<RawChapter>, ChapterSource> {
        val textTrack = selectChapterTextTrack(tracks)
        if (textTrack != null) {
            try {
                val fromTrack = readTextTrackChapters(source, textTrack)
                if (fromTrack.isNotEmpty()) return fromTrack to ChapterSource.TEXT_TRACK
            } catch (_: Exception) {
                // Fall through to chpl / none on any malformed sample table.
            }
        }
        if (!neroChapters.isNullOrEmpty()) return neroChapters to ChapterSource.NERO_CHPL
        return emptyList<RawChapter>() to ChapterSource.NONE
    }

    /**
     * Picks the chapter text track. Preference order:
     *  1. The `text` track explicitly referenced by an audio track's `tref`/`chap`.
     *  2. Any `text` track that carries samples (last resort).
     */
    private fun selectChapterTextTrack(tracks: List<Track>): Track? {
        val byId = tracks.associateBy { it.trackId }
        for (audio in tracks.filter { it.handler == "soun" }) {
            for (refId in audio.chapterTrackIds) {
                val ref = byId[refId]
                if (ref != null && ref.handler == "text" && ref.sampleTable != null) return ref
            }
        }
        return tracks.firstOrNull { it.handler == "text" && it.sampleTable != null }
    }

    private fun readTextTrackChapters(source: SeekableSource, track: Track): List<RawChapter> {
        val st = track.sampleTable ?: return emptyList()
        // A usable text track needs all three tables; a degenerate/empty stsc would otherwise
        // leave every sample offset at 0 and read garbage from the file header.
        if (st.sampleSizes.isEmpty() || st.chunkOffsets.isEmpty() || st.stscFirstChunk.isEmpty()) {
            return emptyList()
        }
        val timescale = if (track.mediaTimescale > 0) track.mediaTimescale else 1000L
        val offsets = st.sampleFileOffsets()
        val sizes = st.sampleSizes
        val startTimes = st.sampleStartTimes() // in media timescale units

        val n = offsets.size
        val out = ArrayList<RawChapter>(n)
        for (i in 0 until n) {
            val size = sizes[i]
            val title = if (size <= 0) "" else decodeTextSample(source, offsets[i], size)
            val startMs = scaleToMs(startTimes[i], timescale)
            out.add(RawChapter(title = title, startMs = startMs))
        }
        return out
    }

    /**
     * Decodes one QuickTime text sample into a title string. Sample layout:
     * `[u16 textLength][text bytes][optional encoding/style atoms]`.
     * Encoding is detected from a leading BOM; otherwise UTF-8 is assumed.
     */
    private fun decodeTextSample(source: SeekableSource, offset: Long, size: Int): String {
        val raw = try {
            source.readBytes(offset, size)
        } catch (_: Exception) {
            return ""
        }
        if (raw.size < 2) return String(raw, Charsets.UTF_8).trim().trimNul()
        var textLen = raw.u16(0)
        // Clamp to what is actually present (guards against sizes that include trailing atoms).
        if (textLen > raw.size - 2) textLen = raw.size - 2
        if (textLen <= 0) return ""
        val start = 2
        val end = start + textLen
        return when {
            textLen >= 2 && raw.u8(start) == 0xFE && raw.u8(start + 1) == 0xFF ->
                String(raw, start + 2, textLen - 2, Charsets.UTF_16BE)
            textLen >= 2 && raw.u8(start) == 0xFF && raw.u8(start + 1) == 0xFE ->
                String(raw, start + 2, textLen - 2, Charsets.UTF_16LE)
            else -> String(raw, start, end - start, Charsets.UTF_8)
        }.trim().trimNul()
    }

    private fun finalizeChapters(raw: List<RawChapter>, durationMs: Long): List<M4bChapter> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.startMs }
        val result = ArrayList<M4bChapter>(sorted.size)
        for (i in sorted.indices) {
            val start = sorted[i].startMs.coerceAtLeast(0L)
            val nextStart = if (i + 1 < sorted.size) sorted[i + 1].startMs else durationMs
            val end = if (durationMs > 0) nextStart.coerceIn(start, durationMs) else nextStart.coerceAtLeast(start)
            val title = sorted[i].title.ifBlank { "Chapter ${i + 1}" }
            result.add(M4bChapter(index = i, title = title, startMs = start, endMs = end))
        }
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Box / track parsing
    // ---------------------------------------------------------------------------------------------

    private fun parseMvhd(buf: ByteArray, p: Int, movie: MovieHeader) {
        val version = buf.u8(p)
        if (version == 1) {
            movie.timescale = buf.u32(p + 20)
            movie.duration = buf.u64(p + 24)
        } else {
            movie.timescale = buf.u32(p + 12)
            movie.duration = buf.u32(p + 16)
        }
    }

    private fun parseTrak(buf: ByteArray, start: Int, end: Int): Track {
        val track = Track()
        forEachChild(buf, start, end) { type, ps, pe ->
            when (type) {
                "tkhd" -> parseTkhd(buf, ps, track)
                "tref" -> parseTref(buf, ps, pe, track)
                "mdia" -> parseMdia(buf, ps, pe, track)
            }
        }
        return track
    }

    private fun parseTkhd(buf: ByteArray, p: Int, track: Track) {
        val version = buf.u8(p)
        // layout: version(1) flags(3) creation modification track_id ...
        track.trackId = if (version == 1) {
            buf.i32(p + 4 + 8 + 8) // after two 8-byte times
        } else {
            buf.i32(p + 4 + 4 + 4) // after two 4-byte times
        }
    }

    private fun parseTref(buf: ByteArray, start: Int, end: Int, track: Track) {
        forEachChild(buf, start, end) { type, ps, pe ->
            if (type == "chap") {
                val ids = ArrayList<Int>()
                var q = ps
                while (q + 4 <= pe) {
                    ids.add(buf.i32(q))
                    q += 4
                }
                track.chapterTrackIds = ids
            }
        }
    }

    private fun parseMdia(buf: ByteArray, start: Int, end: Int, track: Track) {
        forEachChild(buf, start, end) { type, ps, pe ->
            when (type) {
                "mdhd" -> {
                    val version = buf.u8(ps)
                    if (version == 1) {
                        track.mediaTimescale = buf.u32(ps + 20)
                        track.mediaDuration = buf.u64(ps + 24)
                    } else {
                        track.mediaTimescale = buf.u32(ps + 12)
                        track.mediaDuration = buf.u32(ps + 16)
                    }
                }
                "hdlr" -> {
                    // version(1) flags(3) pre_defined(4) handler_type(4) ...
                    track.handler = buf.fourCc(ps + 8)
                }
                "minf" -> parseMinf(buf, ps, pe, track)
            }
        }
    }

    private fun parseMinf(buf: ByteArray, start: Int, end: Int, track: Track) {
        // Only the chapter (text) track's sample table is needed. Skip parsing the audio/video
        // track's sample tables entirely — for a long audiobook the audio stbl holds millions of
        // entries and would waste tens of MB and time. (handler is read from hdlr, which precedes
        // minf in well-formed files; if unknown we still parse, preserving correctness.)
        if (track.handler != null && track.handler != "text") return
        forEachChild(buf, start, end) { type, ps, pe ->
            if (type == "stbl") track.sampleTable = parseStbl(buf, ps, pe)
        }
    }

    private fun parseStbl(buf: ByteArray, start: Int, end: Int): SampleTable {
        var sttsCounts = IntArray(0)
        var sttsDeltas = LongArray(0)
        var stscFirst = IntArray(0)
        var stscPer = IntArray(0)
        var chunkOffsets = LongArray(0)
        var sampleSizes = IntArray(0)

        forEachChild(buf, start, end) { type, ps, pe ->
            // Entry counts come straight from the file; bound them to the box payload (and a hard
            // cap) so a corrupt/truncated table can't trigger a huge allocation or read past the box.
            fun boundedCount(countAt: Int, firstEntry: Int, bytesPerEntry: Int): Int {
                val raw = buf.u32(countAt)
                val available = (pe - firstEntry).toLong().coerceAtLeast(0L)
                val maxByPayload = if (bytesPerEntry > 0) available / bytesPerEntry else MAX_SAMPLES
                return minOf(raw, maxByPayload, MAX_SAMPLES).toInt()
            }
            when (type) {
                "stts" -> {
                    val n = boundedCount(ps + 4, ps + 8, 8)
                    val c = IntArray(n)
                    val d = LongArray(n)
                    var q = ps + 8
                    for (i in 0 until n) {
                        c[i] = buf.u32(q).toInt()
                        d[i] = buf.u32(q + 4)
                        q += 8
                    }
                    sttsCounts = c
                    sttsDeltas = d
                }
                "stsc" -> {
                    val n = boundedCount(ps + 4, ps + 8, 12)
                    val first = IntArray(n)
                    val per = IntArray(n)
                    var q = ps + 8
                    for (i in 0 until n) {
                        first[i] = buf.u32(q).toInt()
                        per[i] = buf.u32(q + 4).toInt()
                        // q+8 = sample_description_index (ignored)
                        q += 12
                    }
                    stscFirst = first
                    stscPer = per
                }
                "stsz" -> {
                    val uniform = buf.u32(ps + 4)
                    sampleSizes = if (uniform != 0L) {
                        val n = minOf(buf.u32(ps + 8), MAX_SAMPLES).toInt()
                        IntArray(n) { uniform.toInt() }
                    } else {
                        val n = boundedCount(ps + 8, ps + 12, 4)
                        var q = ps + 12
                        IntArray(n) { buf.u32(q).toInt().also { q += 4 } }
                    }
                }
                "stz2" -> {
                    val fieldSize = buf.u8(ps + 7) // last byte of the 4-byte field_size word
                    val available = (pe - (ps + 12)).toLong().coerceAtLeast(0L)
                    val maxByPayload = if (fieldSize > 0) (available * 8) / fieldSize else MAX_SAMPLES
                    val n = minOf(buf.u32(ps + 8), maxByPayload, MAX_SAMPLES).toInt()
                    sampleSizes = readStz2(buf, ps + 12, fieldSize, n)
                }
                "stco" -> {
                    val n = boundedCount(ps + 4, ps + 8, 4)
                    var q = ps + 8
                    chunkOffsets = LongArray(n) { buf.u32(q).also { q += 4 } }
                }
                "co64" -> {
                    val n = boundedCount(ps + 4, ps + 8, 8)
                    var q = ps + 8
                    chunkOffsets = LongArray(n) { buf.u64(q).also { q += 8 } }
                }
            }
        }

        return SampleTable(
            sttsCounts = sttsCounts,
            sttsDeltas = sttsDeltas,
            stscFirstChunk = stscFirst,
            stscSamplesPerChunk = stscPer,
            chunkOffsets = chunkOffsets,
            sampleSizes = sampleSizes,
        )
    }

    private fun readStz2(buf: ByteArray, start: Int, fieldSize: Int, count: Int): IntArray {
        val sizes = IntArray(count)
        when (fieldSize) {
            16 -> {
                var q = start
                for (i in 0 until count) { sizes[i] = buf.u16(q); q += 2 }
            }
            8 -> {
                var q = start
                for (i in 0 until count) { sizes[i] = buf.u8(q); q += 1 }
            }
            4 -> {
                var q = start
                var i = 0
                while (i < count) {
                    val b = buf.u8(q); q += 1
                    sizes[i] = (b ushr 4) and 0x0F
                    if (i + 1 < count) sizes[i + 1] = b and 0x0F
                    i += 2
                }
            }
            else -> throw Mp4ParseException("Unsupported stz2 field size $fieldSize")
        }
        return sizes
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata (iTunes-style udta/meta/ilst) and Nero chpl
    // ---------------------------------------------------------------------------------------------

    private class Tags {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var composer: String? = null
        var cover: M4bCoverArt? = null
    }

    private fun parseMeta(buf: ByteArray, start: Int, end: Int): Tags {
        // `meta` is a FullBox in ISO-BMFF (4-byte version/flags before children) but a plain
        // box in QuickTime. Detect by checking where the first child ('hdlr') begins.
        val childStart = when {
            end - start >= 12 && buf.fourCc(start + 4) == "hdlr" -> start          // QuickTime
            end - start >= 12 && buf.fourCc(start + 8) == "hdlr" -> start + 4       // ISO FullBox
            else -> start + 4                                                       // assume ISO
        }
        val tags = Tags()
        forEachChild(buf, childStart, end) { type, ps, pe ->
            if (type == "ilst") parseIlst(buf, ps, pe, tags)
        }
        return tags
    }

    private fun parseIlst(buf: ByteArray, start: Int, end: Int, tags: Tags) {
        forEachChild(buf, start, end) { type, ps, pe ->
            when {
                type == "©nam" -> tags.title = readDataText(buf, ps, pe) ?: tags.title
                type == "©ART" -> tags.artist = readDataText(buf, ps, pe) ?: tags.artist
                type == "aART" -> tags.albumArtist = readDataText(buf, ps, pe) ?: tags.albumArtist
                type == "©alb" -> tags.album = readDataText(buf, ps, pe) ?: tags.album
                type == "©wrt" -> tags.composer = readDataText(buf, ps, pe) ?: tags.composer
                type == "covr" -> tags.cover = readCover(buf, ps, pe) ?: tags.cover
            }
        }
    }

    /** Reads the UTF-8 text payload from the `data` atom inside an iTunes tag atom. */
    private fun readDataText(buf: ByteArray, start: Int, end: Int): String? {
        var result: String? = null
        forEachChild(buf, start, end) { type, ps, pe ->
            if (type == "data" && result == null) {
                // data: u32 typeIndicator, u32 locale, then value
                val valueStart = ps + 8
                if (valueStart <= pe) {
                    result = String(buf, valueStart, pe - valueStart, Charsets.UTF_8).trim().trimNul()
                }
            }
        }
        return result?.ifBlank { null }
    }

    private fun readCover(buf: ByteArray, start: Int, end: Int): M4bCoverArt? {
        var result: M4bCoverArt? = null
        forEachChild(buf, start, end) { type, ps, pe ->
            if (type == "data" && result == null) {
                val typeIndicator = buf.u32(ps).toInt()
                val valueStart = ps + 8
                if (valueStart < pe) {
                    val bytes = buf.copyOfRange(valueStart, pe)
                    val mime = when (typeIndicator) {
                        14 -> "image/png"
                        13 -> "image/jpeg"
                        else -> sniffImageMime(bytes)
                    }
                    result = M4bCoverArt(bytes, mime)
                }
            }
        }
        return result
    }

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.u8(0) == 0x89 && bytes.u8(1) == 0x50 -> "image/png"
        bytes.size >= 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 -> "image/jpeg"
        else -> "image/jpeg"
    }

    /**
     * Parses the Nero `chpl` box. NOTE: the chapter count here is a single byte, so this
     * path is inherently limited to 255 chapters and is used only as a fallback.
     */
    private fun parseNeroChpl(buf: ByteArray, start: Int, end: Int): List<RawChapter> {
        if (start + 5 > end) return emptyList()
        val version = buf.u8(start)
        var p = start + 4 // version(1) + flags(3)
        if (version != 0) p += 4 // reserved (per Nero/ffmpeg, present when version != 0)
        if (p >= end) return emptyList()
        val count = buf.u8(p); p += 1
        val out = ArrayList<RawChapter>(count)
        var i = 0
        while (i < count && p + 9 <= end) {
            val startTime100ns = buf.u64(p); p += 8
            val titleLen = buf.u8(p); p += 1
            val avail = minOf(titleLen, end - p)
            val title = if (avail > 0) String(buf, p, avail, Charsets.UTF_8).trim().trimNul() else ""
            p += avail
            // chpl start times are in 100-nanosecond units -> milliseconds.
            out.add(RawChapter(title = title, startMs = startTime100ns / 10_000L))
            i++
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Low-level box traversal
    // ---------------------------------------------------------------------------------------------

    private data class BoxLocation(val offset: Long, val size: Long, val headerSize: Int)

    /** Scans top-level boxes of the file (reading from the source) to find [type]. */
    private fun findTopLevelBox(src: SeekableSource, type: String): BoxLocation? {
        var pos = 0L
        val total = src.size
        val hdr = ByteArray(16)
        while (pos + 8 <= total) {
            src.readFully(pos, hdr, 0, 8)
            var boxSize = hdr.u32(0)
            var headerSize = 8
            when (boxSize) {
                1L -> {
                    src.readFully(pos + 8, hdr, 8, 8)
                    boxSize = hdr.u64(8)
                    headerSize = 16
                }
                0L -> boxSize = total - pos // extends to end of file
            }
            val t = hdr.fourCc(4)
            if (t == type) return BoxLocation(pos, boxSize, headerSize)
            if (boxSize < headerSize) break // corrupt; avoid infinite loop
            pos += boxSize
        }
        return null
    }

    /**
     * Iterates the immediate child boxes contained in [buf] between [start] and [end],
     * invoking [action] with each child's type and payload bounds. Stops on any
     * inconsistency rather than throwing, so malformed sub-trees degrade gracefully.
     */
    private inline fun forEachChild(
        buf: ByteArray,
        start: Int,
        end: Int,
        action: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit,
    ) {
        var p = start
        while (p + 8 <= end) {
            var boxSize = buf.u32(p)
            var headerSize = 8
            when (boxSize) {
                1L -> {
                    if (p + 16 > end) break
                    boxSize = buf.u64(p + 8)
                    headerSize = 16
                }
                0L -> boxSize = (end - p).toLong()
            }
            if (boxSize < headerSize) break
            val boxEndLong = p + boxSize
            if (boxEndLong > end || boxEndLong < p) break
            val boxEnd = boxEndLong.toInt()
            val type = buf.fourCc(p + 4)
            action(type, p + headerSize, boxEnd)
            p = boxEnd
        }
    }

    /** Scales a timescale-relative value to milliseconds without overflowing on large inputs. */
    private fun scaleToMs(value: Long, timescale: Long): Long {
        if (timescale <= 0) return value
        val whole = value / timescale
        val remainder = value % timescale
        return whole * 1000L + (remainder * 1000L) / timescale
    }

    // ---------------------------------------------------------------------------------------------
    // Internal mutable structures
    // ---------------------------------------------------------------------------------------------

    private class MovieHeader {
        var timescale: Long = 0
        var duration: Long = 0
        fun durationMs(): Long {
            if (timescale <= 0) return 0
            return (duration / timescale) * 1000L + (duration % timescale) * 1000L / timescale
        }
    }

    private class Track {
        var trackId: Int = -1
        var handler: String? = null
        var mediaTimescale: Long = 0
        var mediaDuration: Long = 0
        var chapterTrackIds: List<Int> = emptyList()
        var sampleTable: SampleTable? = null
    }

    private data class RawChapter(val title: String, val startMs: Long)
}

/** Decoded sample table for a track, with helpers to compute per-sample offsets and start times. */
internal class SampleTable(
    val sttsCounts: IntArray,
    val sttsDeltas: LongArray,
    val stscFirstChunk: IntArray,
    val stscSamplesPerChunk: IntArray,
    val chunkOffsets: LongArray,
    val sampleSizes: IntArray,
) {
    /** Cumulative decode start time (in media timescale units) for each sample. */
    fun sampleStartTimes(): LongArray {
        val n = sampleSizes.size
        val out = LongArray(n)
        var t = 0L
        var sampleIndex = 0
        for (run in sttsCounts.indices) {
            val count = sttsCounts[run]
            val delta = sttsDeltas[run]
            var k = 0
            while (k < count && sampleIndex < n) {
                out[sampleIndex] = t
                t += delta
                sampleIndex++
                k++
            }
        }
        // If stts under-describes (rare/corrupt), remaining samples keep their default 0/last value.
        return out
    }

    /**
     * Absolute file offset of each sample, derived from the chunk offset table and the
     * sample-to-chunk runs. All counts are 32-bit, so this scales to any chapter count.
     */
    fun sampleFileOffsets(): LongArray {
        val n = sampleSizes.size
        val offsets = LongArray(n)
        val totalChunks = chunkOffsets.size
        if (totalChunks == 0 || n == 0) return offsets

        var sampleIndex = 0
        val runCount = stscFirstChunk.size
        var run = 0
        while (run < runCount && sampleIndex < n) {
            val firstChunk = stscFirstChunk[run]
            val perChunk = stscSamplesPerChunk[run]
            val nextFirstChunk = if (run + 1 < runCount) stscFirstChunk[run + 1] else totalChunks + 1
            var chunk = firstChunk
            while (chunk < nextFirstChunk && sampleIndex < n) {
                if (chunk - 1 in 0 until totalChunks) {
                    var offsetInChunk = chunkOffsets[chunk - 1]
                    var s = 0
                    while (s < perChunk && sampleIndex < n) {
                        offsets[sampleIndex] = offsetInChunk
                        offsetInChunk += sampleSizes[sampleIndex]
                        sampleIndex++
                        s++
                    }
                }
                chunk++
            }
            run++
        }
        return offsets
    }
}

/** Removes NUL characters that some encoders embed in text payloads, then trims whitespace. */
internal fun String.trimNul(): String = replace("\u0000", "").trim()
