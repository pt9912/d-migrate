package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

class FileBackedUploadSegmentStore(private val root: Path) : UploadSegmentStore {

    private val segmentsRoot: Path = root.resolve("segments").also { Files.createDirectories(it) }

    override fun writeSegment(segment: UploadSegment, source: InputStream): WriteSegmentOutcome {
        PathSafety.requireSafeId(segment.uploadSessionId, "uploadSessionId")
        PathSafety.requireNonNegativeIndex(segment.segmentIndex)
        val sessionDir = segmentsRoot.resolve(segment.uploadSessionId)
        Files.createDirectories(sessionDir)
        val finalBin = sessionDir.resolve("${segment.segmentIndex}${FileLayout.BIN}")
        val finalMeta = sessionDir.resolve("${segment.segmentIndex}${FileLayout.META}")

        val tmpBin = sessionDir.resolve(
            "${segment.segmentIndex}${FileLayout.BIN}${FileLayout.TMP_INFIX}${java.util.UUID.randomUUID()}",
        )
        val written = try {
            StreamingHashWriter.copyAndHash(source, tmpBin)
        } catch (failure: IOException) {
            Files.deleteIfExists(tmpBin)
            throw failure
        }

        if (written.sizeBytes != segment.sizeBytes) {
            Files.deleteIfExists(tmpBin)
            return WriteSegmentOutcome.SizeMismatch(
                segmentIndex = segment.segmentIndex,
                expected = segment.sizeBytes,
                actual = written.sizeBytes,
            )
        }

        return try {
            Files.createLink(finalBin, tmpBin)
            Files.deleteIfExists(tmpBin)
            Sidecar.writeAtomically(finalMeta, Sidecar(written.sha256, written.sizeBytes))
            WriteSegmentOutcome.Stored(segment.copy(segmentSha256 = written.sha256))
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(tmpBin)
            resolveExisting(segment, written.sha256, finalBin, finalMeta)
        }
    }

    private fun resolveExisting(
        segment: UploadSegment,
        attemptedSha: String,
        finalBin: Path,
        finalMeta: Path,
    ): WriteSegmentOutcome {
        val existingSha = Sidecar.readOrRecover(finalBin, finalMeta).sha256
        return if (existingSha == attemptedSha) {
            WriteSegmentOutcome.AlreadyStored(segment.copy(segmentSha256 = existingSha))
        } else {
            WriteSegmentOutcome.Conflict(
                segmentIndex = segment.segmentIndex,
                existingSegmentSha256 = existingSha,
                attemptedSegmentSha256 = attemptedSha,
            )
        }
    }

    override fun listSegments(uploadSessionId: String): List<UploadSegment> {
        PathSafety.requireSafeId(uploadSessionId, "uploadSessionId")
        val sessionDir = segmentsRoot.resolve(uploadSessionId)
        if (!Files.exists(sessionDir)) return emptyList()
        return Files.list(sessionDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(FileLayout.BIN) }
                .map { binPath ->
                    val name = binPath.fileName.toString()
                    val index = name.removeSuffix(FileLayout.BIN).toInt()
                    val meta = sessionDir.resolve("$index${FileLayout.META}")
                    val sidecar = Sidecar.readOrRecover(binPath, meta)
                    UploadSegment(
                        uploadSessionId = uploadSessionId,
                        segmentIndex = index,
                        segmentOffset = 0L,
                        sizeBytes = sidecar.sizeBytes,
                        segmentSha256 = sidecar.sha256,
                    )
                }
                .sorted(compareBy { it.segmentIndex })
                .toList()
        }
    }

    override fun openSegmentRangeRead(
        uploadSessionId: String,
        segmentIndex: Int,
        offset: Long,
        length: Long,
    ): InputStream {
        PathSafety.requireSafeId(uploadSessionId, "uploadSessionId")
        PathSafety.requireNonNegativeIndex(segmentIndex)
        val finalBin = segmentsRoot.resolve(uploadSessionId).resolve("$segmentIndex${FileLayout.BIN}")
        return try {
            RangeRead.open(finalBin, offset, length)
        } catch (_: NoSuchFileException) {
            error("segment $segmentIndex of $uploadSessionId not found")
        }
    }

    override fun deleteAllForSession(uploadSessionId: String): Int {
        PathSafety.requireSafeId(uploadSessionId, "uploadSessionId")
        val sessionDir = segmentsRoot.resolve(uploadSessionId)
        if (!Files.exists(sessionDir)) return 0
        var binCount = 0
        Files.walk(sessionDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry ->
                if (entry.fileName?.toString()?.endsWith(FileLayout.BIN) == true) binCount++
                Files.deleteIfExists(entry)
            }
        }
        return binCount
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanupOrphans(activeSessions: Set<String>): Int {
        if (!Files.exists(segmentsRoot)) return 0
        val sessionDirs = Files.list(segmentsRoot).use { it.toList() }
        var removed = 0
        for (sessionDir in sessionDirs) {
            val name = sessionDir.fileName.toString()
            if (name !in activeSessions) {
                sessionDir.deleteRecursively()
                removed++
            } else {
                removed += cleanupSessionInternals(sessionDir)
            }
        }
        return removed
    }

    private fun cleanupSessionInternals(sessionDir: Path): Int {
        val entries = Files.list(sessionDir).use { it.toList() }
        var removed = 0
        for (entry in entries) {
            val name = entry.fileName.toString()
            val isTmp = name.contains(FileLayout.TMP_INFIX)
            val isOrphanMeta = name.endsWith(FileLayout.META) &&
                !Files.exists(sessionDir.resolve(name.removeSuffix(FileLayout.META) + FileLayout.BIN))
            if (isTmp || isOrphanMeta) {
                Files.deleteIfExists(entry)
                removed++
            }
        }
        return removed
    }
}
