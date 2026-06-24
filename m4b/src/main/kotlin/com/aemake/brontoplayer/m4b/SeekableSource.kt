package com.aemake.brontoplayer.m4b

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access, read-only byte source. Abstracted so the parser can read from a
 * plain [File] (desktop/tests) or from an Android content `Uri` (via a
 * `ParcelFileDescriptor`-backed implementation in the app module) without the
 * parser itself depending on the Android framework.
 *
 * Implementations must be safe to call from a single thread; callers should not
 * assume concurrent access.
 */
interface SeekableSource : Closeable {
    /** Total size of the underlying resource, in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes starting at absolute [position] into [dest] at
     * [destOffset]. Returns the number of bytes read, or -1 at end of input.
     */
    fun readAt(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int
}

/** Reads exactly [length] bytes at [position], throwing [EOFException] if fewer are available. */
fun SeekableSource.readFully(position: Long, dest: ByteArray, destOffset: Int, length: Int) {
    var off = destOffset
    var remaining = length
    var pos = position
    while (remaining > 0) {
        val n = readAt(pos, dest, off, remaining)
        if (n <= 0) throw EOFException("Unexpected EOF at $pos (needed $remaining more byte(s))")
        off += n
        pos += n
        remaining -= n
    }
}

/** Convenience that allocates and fully reads [length] bytes at [position]. */
fun SeekableSource.readBytes(position: Long, length: Int): ByteArray {
    require(length >= 0) { "length must be >= 0, was $length" }
    val buf = ByteArray(length)
    readFully(position, buf, 0, length)
    return buf
}

/** [SeekableSource] backed by a [RandomAccessFile]. */
class RandomAccessFileSource private constructor(
    private val raf: RandomAccessFile,
) : SeekableSource {
    constructor(file: File) : this(RandomAccessFile(file, "r"))

    override val size: Long get() = raf.length()

    @Synchronized
    override fun readAt(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (position >= raf.length()) return -1
        raf.seek(position)
        return raf.read(dest, destOffset, length)
    }

    override fun close() = raf.close()
}

/** In-memory [SeekableSource]; primarily used by tests. */
class ByteArraySource(private val data: ByteArray) : SeekableSource {
    override val size: Long get() = data.size.toLong()

    override fun readAt(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (position < 0) return -1
        if (position >= data.size) return -1
        val n = minOf(length.toLong(), data.size.toLong() - position).toInt()
        System.arraycopy(data, position.toInt(), dest, destOffset, n)
        return n
    }

    override fun close() {}
}
