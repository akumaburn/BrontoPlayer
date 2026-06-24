package com.aemake.brontoplayer.m4b

import java.io.ByteArrayOutputStream

/**
 * Minimal MP4/M4B builder used by tests. It produces a structurally valid container
 * with an audio track and (optionally) a QuickTime chapter text track and/or a Nero
 * `chpl` box, so the parser can be exercised against real byte layouts.
 *
 * File layout produced: `ftyp` + `mdat`(chapter text samples) + `moov`. Because `mdat`
 * comes before `moov`, the absolute sample offsets stored in `stco`/`co64` are known
 * while building `moov`.
 */
object Mp4Builder {

    data class ChapterSpec(val title: String, val startMs: Long)

    enum class OffsetMode { SINGLE_CHUNK_STCO, MULTI_CHUNK_STCO, CO64 }
    enum class MetaStyle { ISO, QUICKTIME }

    private const val TIMESCALE = 1000L
    private const val AUDIO_TRACK_ID = 1
    private const val TEXT_TRACK_ID = 2

    fun build(
        chapters: List<ChapterSpec>,
        durationMs: Long,
        includeTextTrack: Boolean = true,
        includeTref: Boolean = true,
        includeChpl: Boolean = false,
        offsetMode: OffsetMode = OffsetMode.SINGLE_CHUNK_STCO,
        metaStyle: MetaStyle = MetaStyle.ISO,
        title: String? = "The Test Audiobook",
        author: String? = "Ada Author",
        narrator: String? = "Nat Narrator",
        album: String? = "Test Album",
        cover: Pair<ByteArray, Int>? = null, // bytes + typeIndicator (13 jpeg / 14 png)
        utf16Titles: Boolean = false,
    ): ByteArray {
        // --- chapter text samples (each: u16 length prefix + encoded title) ---
        val sampleBytes = ArrayList<ByteArray>()
        for (ch in chapters) {
            val encoded = if (utf16Titles) {
                // UTF-16BE with BOM so the parser's BOM detection kicks in.
                val body = ch.title.toByteArray(Charsets.UTF_16BE)
                byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body
            } else {
                ch.title.toByteArray(Charsets.UTF_8)
            }
            sampleBytes.add(u16(encoded.size) + encoded)
        }
        val textData = concat(sampleBytes)

        val ftyp = buildFtyp()
        val mdatHeader = 8
        val mdatContentStart = ftyp.size.toLong() + mdatHeader

        // within-data offsets of each sample
        val sampleAbsOffsets = LongArray(sampleBytes.size)
        run {
            var off = mdatContentStart
            for (i in sampleBytes.indices) {
                sampleAbsOffsets[i] = off
                off += sampleBytes[i].size
            }
        }
        val sampleSizes = IntArray(sampleBytes.size) { sampleBytes[it].size }

        val moov = buildMoov(
            chapters = chapters,
            durationMs = durationMs,
            includeTextTrack = includeTextTrack,
            includeTref = includeTref,
            includeChpl = includeChpl,
            offsetMode = offsetMode,
            metaStyle = metaStyle,
            title = title, author = author, narrator = narrator, album = album, cover = cover,
            sampleAbsOffsets = sampleAbsOffsets,
            sampleSizes = sampleSizes,
        )

        val mdat = box("mdat", textData)
        return concat(listOf(ftyp, mdat, moov))
    }

    // ---- top-level boxes ----

    private fun buildFtyp(): ByteArray = box(
        "ftyp",
        fourcc("M4A "), u32(0), fourcc("M4A "), fourcc("M4B "), fourcc("mp42"), fourcc("isom"),
    )

    private fun buildMoov(
        chapters: List<ChapterSpec>,
        durationMs: Long,
        includeTextTrack: Boolean,
        includeTref: Boolean,
        includeChpl: Boolean,
        offsetMode: OffsetMode,
        metaStyle: MetaStyle,
        title: String?, author: String?, narrator: String?, album: String?,
        cover: Pair<ByteArray, Int>?,
        sampleAbsOffsets: LongArray,
        sampleSizes: IntArray,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts.add(buildMvhd(durationMs))
        parts.add(buildAudioTrak(includeTref && includeTextTrack))
        if (includeTextTrack) {
            parts.add(buildTextTrak(chapters, durationMs, offsetMode, sampleAbsOffsets, sampleSizes))
        }
        parts.add(buildUdta(title, author, narrator, album, cover, metaStyle, includeChpl, chapters, durationMs))
        return box("moov", *parts.toTypedArray())
    }

    private fun buildMvhd(durationMs: Long): ByteArray = fullBox(
        "mvhd", 0, 0,
        u32(0), u32(0),          // creation, modification
        u32(TIMESCALE),          // timescale
        u32(durationMs),         // duration (in timescale units == ms here)
        u32(0x00010000),         // rate 1.0
        u16(0x0100), u16(0),     // volume 1.0 + reserved
        u32(0), u32(0),          // reserved
        identityMatrix(),
        ByteArray(24),           // pre_defined
        u32(0xFFFFFFFF),         // next_track_ID
    )

    private fun buildAudioTrak(withChapRef: Boolean): ByteArray {
        val children = ArrayList<ByteArray>()
        children.add(buildTkhd(AUDIO_TRACK_ID))
        if (withChapRef) {
            children.add(box("tref", box("chap", u32(TEXT_TRACK_ID))))
        }
        children.add(
            box(
                "mdia",
                buildMdhd(),
                buildHdlr("soun", "SoundHandler"),
                box("minf", box("smhd", fullBoxBody(0, 0, u16(0), u16(0))), buildMinimalStbl("mp4a")),
            ),
        )
        return box("trak", *children.toTypedArray())
    }

    private fun buildTextTrak(
        chapters: List<ChapterSpec>,
        durationMs: Long,
        offsetMode: OffsetMode,
        sampleAbsOffsets: LongArray,
        sampleSizes: IntArray,
    ): ByteArray = box(
        "trak",
        buildTkhd(TEXT_TRACK_ID),
        box(
            "mdia",
            buildMdhd(),
            buildHdlr("text", "ChapterHandler"),
            box("minf", buildChapterStbl(chapters, durationMs, offsetMode, sampleAbsOffsets, sampleSizes)),
        ),
    )

    private fun buildTkhd(trackId: Int): ByteArray = fullBox(
        "tkhd", 0, 0x000007,
        u32(0), u32(0),       // creation, modification
        u32(trackId.toLong()),
        u32(0),               // reserved
        u32(0),               // duration
        u32(0), u32(0),       // reserved
        u16(0), u16(0),       // layer, alternate_group
        u16(0), u16(0),       // volume, reserved
        identityMatrix(),
        u32(0), u32(0),       // width, height
    )

    private fun buildMdhd(): ByteArray = fullBox(
        "mdhd", 0, 0,
        u32(0), u32(0),       // creation, modification
        u32(TIMESCALE),       // timescale
        u32(0),               // duration
        u16(0x55C4),          // language ('und')
        u16(0),               // pre_defined
    )

    private fun buildHdlr(handlerType: String, name: String): ByteArray = fullBox(
        "hdlr", 0, 0,
        u32(0),               // pre_defined
        fourcc(handlerType),  // handler_type
        u32(0), u32(0), u32(0), // reserved
        name.toByteArray(Charsets.UTF_8) + byteArrayOf(0),
    )

    private fun buildMinimalStbl(format: String): ByteArray = box(
        "stbl",
        box("stsd", fullBoxBody(0, 0, u32(0))),       // 0 entries
        box("stts", fullBoxBody(0, 0, u32(0))),
        box("stsc", fullBoxBody(0, 0, u32(0))),
        box("stsz", fullBoxBody(0, 0, u32(0), u32(0))),
        box("stco", fullBoxBody(0, 0, u32(0))),
    )

    private fun buildChapterStbl(
        chapters: List<ChapterSpec>,
        durationMs: Long,
        offsetMode: OffsetMode,
        sampleAbsOffsets: LongArray,
        sampleSizes: IntArray,
    ): ByteArray {
        val n = chapters.size

        // stts: one entry per sample, delta = chapter duration.
        val sttsEntries = ArrayList<ByteArray>()
        for (i in 0 until n) {
            val next = if (i + 1 < n) chapters[i + 1].startMs else durationMs
            val delta = (next - chapters[i].startMs).coerceAtLeast(0)
            sttsEntries.add(u32(1) + u32(delta))
        }
        val stts = box("stts", fullBoxBody(0, 0, u32(n.toLong()), concat(sttsEntries)))

        // stsz: explicit per-sample sizes.
        val stszSizes = concat((0 until n).map { u32(sampleSizes[it].toLong()) })
        val stsz = box("stsz", fullBoxBody(0, 0, u32(0), u32(n.toLong()), stszSizes))

        // stsc + chunk offset table per requested mode.
        val (stsc, chunkOffsetBox) = when (offsetMode) {
            OffsetMode.SINGLE_CHUNK_STCO -> {
                val stscBody = fullBoxBody(0, 0, u32(1), u32(1) + u32(n.toLong()) + u32(1))
                val stco = box("stco", fullBoxBody(0, 0, u32(1), u32(sampleAbsOffsets[0])))
                box("stsc", stscBody) to stco
            }
            OffsetMode.MULTI_CHUNK_STCO -> {
                // one sample per chunk
                val stscBody = fullBoxBody(0, 0, u32(1), u32(1) + u32(1) + u32(1))
                val entries = concat((0 until n).map { u32(sampleAbsOffsets[it]) })
                val stco = box("stco", fullBoxBody(0, 0, u32(n.toLong()), entries))
                box("stsc", stscBody) to stco
            }
            OffsetMode.CO64 -> {
                // one sample per chunk, 64-bit offsets
                val stscBody = fullBoxBody(0, 0, u32(1), u32(1) + u32(1) + u32(1))
                val entries = concat((0 until n).map { u64(sampleAbsOffsets[it]) })
                val co64 = box("co64", fullBoxBody(0, 0, u32(n.toLong()), entries))
                box("stsc", stscBody) to co64
            }
        }

        val textStsd = box(
            "stsd",
            fullBoxBody(0, 0, u32(1), box("text", ByteArray(8) /* reserved */ + u32(0))),
        )

        return box("stbl", textStsd, stts, stsc, stsz, chunkOffsetBox)
    }

    private fun buildUdta(
        title: String?, author: String?, narrator: String?, album: String?,
        cover: Pair<ByteArray, Int>?,
        metaStyle: MetaStyle,
        includeChpl: Boolean,
        chapters: List<ChapterSpec>,
        durationMs: Long,
    ): ByteArray {
        val udtaChildren = ArrayList<ByteArray>()

        val ilstChildren = ArrayList<ByteArray>()
        title?.let { ilstChildren.add(textTag("©nam", it)) }
        author?.let { ilstChildren.add(textTag("©ART", it)) }
        album?.let { ilstChildren.add(textTag("©alb", it)) }
        narrator?.let { ilstChildren.add(textTag("©wrt", it)) }
        cover?.let { (bytes, type) -> ilstChildren.add(coverTag(bytes, type)) }

        val ilst = box("ilst", *ilstChildren.toTypedArray())
        val hdlr = buildHdlr("mdir", "")
        val meta = when (metaStyle) {
            MetaStyle.ISO -> fullBox("meta", 0, 0, hdlr, ilst)
            MetaStyle.QUICKTIME -> box("meta", hdlr, ilst)
        }
        udtaChildren.add(meta)

        if (includeChpl) udtaChildren.add(buildChpl(chapters, durationMs))

        return box("udta", *udtaChildren.toTypedArray())
    }

    private fun textTag(name: String, value: String): ByteArray =
        box(name, box("data", u32(1) /* UTF-8 */ + u32(0) /* locale */ + value.toByteArray(Charsets.UTF_8)))

    private fun coverTag(bytes: ByteArray, typeIndicator: Int): ByteArray =
        box("covr", box("data", u32(typeIndicator.toLong()) + u32(0) + bytes))

    /**
     * Nero `chpl` box. Matches the layout the parser expects: version 1, 3 flag bytes,
     * a 4-byte reserved field, then a *single byte* chapter count, then entries of
     * `[u64 start(100ns)][u8 titleLen][title]`. Capped at 255 by the format.
     */
    private fun buildChpl(chapters: List<ChapterSpec>, @Suppress("UNUSED_PARAMETER") durationMs: Long): ByteArray {
        val count = minOf(chapters.size, 255)
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(1)) // version
        body.write(byteArrayOf(0, 0, 0)) // flags
        body.write(u32(0)) // reserved (present because version != 0)
        body.write(count) // single-byte count
        for (i in 0 until count) {
            val ch = chapters[i]
            body.write(u64(ch.startMs * 10_000L)) // ms -> 100ns units
            val t = ch.title.toByteArray(Charsets.UTF_8)
            val len = minOf(t.size, 255)
            body.write(len)
            body.write(t, 0, len)
        }
        return box("chpl", body.toByteArray())
    }

    // ---- byte helpers ----

    private fun identityMatrix(): ByteArray = concat(
        listOf(u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000)),
    )

    private fun fullBoxBody(version: Int, flags: Int, vararg body: ByteArray): ByteArray =
        concat(listOf(byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte())) + body.toList())

    private fun fullBox(type: String, version: Int, flags: Int, vararg body: ByteArray): ByteArray =
        box(type, fullBoxBody(version, flags, *body))

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val payload = concat(body.toList())
        return concat(listOf(u32((8 + payload.size).toLong()), fourcc(type), payload))
    }

    private fun fourcc(s: String): ByteArray {
        require(s.length == 4) { "FourCC must be 4 chars: '$s'" }
        return ByteArray(4) { s[it].code.toByte() }
    }

    private fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u32(v: Long): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun u32(v: Int): ByteArray = u32(v.toLong())

    private fun u64(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * (7 - it))) and 0xFF).toByte() }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    private fun concat(parts: ArrayList<ByteArray>): ByteArray = concat(parts as List<ByteArray>)
}
