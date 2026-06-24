package com.aemake.brontoplayer.data.io

import android.content.ContentResolver
import android.net.Uri
import com.aemake.brontoplayer.m4b.SeekableSource
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * [SeekableSource] over an Android content [Uri]. Uses a [java.nio.channels.FileChannel]
 * positioned read so the parser can random-access the file without copying it. Works for
 * SAF document URIs that resolve to seekable file descriptors (the common case for local
 * audiobook files).
 */
class UriSeekableSource(resolver: ContentResolver, uri: Uri) : SeekableSource {

    private val pfd = resolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Unable to open file descriptor for $uri")
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel

    override val size: Long = pfd.statSize.let { if (it >= 0) it else channel.size() }

    override fun readAt(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val buffer = ByteBuffer.wrap(dest, destOffset, length)
        return channel.read(buffer, position)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }
}
