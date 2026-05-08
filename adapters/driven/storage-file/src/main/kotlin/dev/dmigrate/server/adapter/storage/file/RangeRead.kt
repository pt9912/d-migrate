package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.RangeBounds
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object RangeRead {

    fun open(path: Path, offset: Long, length: Long): InputStream {
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        try {
            RangeBounds.check(offset, length, channel.size())
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
        if (length == 0L) {
            channel.close()
            return ByteArrayInputStream(ByteArray(0))
        }
        channel.position(offset)
        return ChannelRangeInputStream(channel, length)
    }
}

private class ChannelRangeInputStream(
    private val channel: FileChannel,
    length: Long,
) : InputStream() {

    private var remaining: Long = length
    private val singleByte: ByteBuffer = ByteBuffer.allocate(1)

    override fun read(): Int {
        if (remaining <= 0) return -1
        singleByte.clear()
        val n = channel.read(singleByte)
        if (n < 0) return -1
        remaining -= n
        return singleByte.get(0).toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val buf = ByteBuffer.wrap(b, off, toRead)
        val n = channel.read(buf)
        if (n < 0) return -1
        remaining -= n
        return n
    }

    override fun close() {
        channel.close()
    }
}
