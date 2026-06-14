package dev.dmigrate.format.parquet

import org.apache.parquet.io.OutputFile
import org.apache.parquet.io.PositionOutputStream
import java.io.OutputStream

/**
 * Adapter, der einen [OutputStream] in das Parquet-eigene
 * [OutputFile]-Interface wickelt (AP10 §3.4 / AP12 §5.2).
 * Erlaubt `parquet-java` ohne Hadoop-`Path`-Pfad zu schreiben
 * und unterstuetzt damit den Cut-A-Default fuer
 * `data export --format parquet -o <file>` und Single-File-
 * Stream-Targets.
 *
 * Stdout ist Cut-A bewusst nicht im Scope (AP12 §4 lehnt
 * Stdin-Parquet ab); ein zaehlender, nicht-seekbarer
 * Stream-Pfad bleibt aber durch diesen Adapter
 * grundsaetzlich offen.
 */
internal class OutputStreamOutputFile(
    private val stream: OutputStream,
    private val blockSize: Long = DEFAULT_BLOCK_SIZE,
) : OutputFile {

    override fun create(blockSizeHint: Long): PositionOutputStream =
        OutputStreamPositionStream(stream)

    override fun createOrOverwrite(blockSizeHint: Long): PositionOutputStream =
        OutputStreamPositionStream(stream)

    override fun supportsBlockSize(): Boolean = false

    override fun defaultBlockSize(): Long = blockSize

    override fun toString(): String = "OutputStreamOutputFile(stream=${stream::class.simpleName})"

    private class OutputStreamPositionStream(
        private val delegate: OutputStream,
    ) : PositionOutputStream() {
        private var position: Long = 0L

        override fun getPos(): Long = position

        override fun write(b: Int) {
            delegate.write(b)
            position += 1
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            position += b.size.toLong()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            position += len.toLong()
        }

        override fun flush() = delegate.flush()

        override fun close() {
            // Der Adapter schliesst den unterliegenden Stream nicht —
            // Lifetime gehoert dem Caller (DataChunkWriter.close()).
        }
    }

    companion object {
        private const val DEFAULT_BLOCK_SIZE: Long = 128L * 1024L * 1024L
    }
}
