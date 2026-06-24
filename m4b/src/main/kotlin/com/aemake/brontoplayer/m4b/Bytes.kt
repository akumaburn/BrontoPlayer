package com.aemake.brontoplayer.m4b

/**
 * Big-endian (network byte order) primitive readers over a [ByteArray]. MP4/ISO-BMFF
 * stores all multi-byte integers big-endian. All reads return unsigned values widened
 * into a signed Kotlin type large enough to hold them.
 */

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

internal fun ByteArray.u16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)

internal fun ByteArray.u24(o: Int): Int = (u8(o) shl 16) or (u8(o + 1) shl 8) or u8(o + 2)

internal fun ByteArray.u32(o: Int): Long =
    ((u8(o).toLong() shl 24) or
        (u8(o + 1).toLong() shl 16) or
        (u8(o + 2).toLong() shl 8) or
        u8(o + 3).toLong()) and 0xFFFF_FFFFL

internal fun ByteArray.i32(o: Int): Int =
    (u8(o) shl 24) or (u8(o + 1) shl 16) or (u8(o + 2) shl 8) or u8(o + 3)

internal fun ByteArray.u64(o: Int): Long {
    var r = 0L
    for (i in 0 until 8) r = (r shl 8) or u8(o + i).toLong()
    return r
}

/** Reads a 4-character box type (FourCC). Bytes are treated as Latin-1 (one byte per char). */
internal fun ByteArray.fourCc(o: Int): String {
    val c = CharArray(4)
    for (i in 0 until 4) c[i] = u8(o + i).toChar()
    return String(c)
}
