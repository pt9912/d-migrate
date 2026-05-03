package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.WriteSegmentOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.deleteRecursively

class FileBackedUploadSegmentStore(private val root: Path) : UploadSegmentStore {

    private val segmentsRoot: Path = root.resolve("segments").also { Files.createDirectories(it) }

    // In-process per-key lock keeps the §6.3 "exactly one Stored under
    // concurrency" guarantee with `Files.move(... ATOMIC_MOVE)` —
    // POSIX rename(2) silently overwrites, so without serialization
    // two writers could publish a (sidecar, data) pair from different
    // payloads. Phase A is single-process; cross-process locking
    // would land with a real persistent store.
    private val keyLocks = ConcurrentHashMap<Pair<String, Int>, ReentrantLock>()

    override fun writeSegment(segment: UploadSegment, source: InputStream): WriteSegmentOutcome {
        PathSafety.requireSafeId(segment.uploadSessionId, "uploadSessionId")
        PathSafety.requireNonNegativeIndex(segment.segmentIndex)
        val sessionDir = segmentsRoot.resolve(segment.uploadSessionId)
        Files.createDirectories(sessionDir)
        val finalBin = sessionDir.resolve("${segment.segmentIndex}${FileLayout.BIN}")
        val finalMeta = sessionDir.resolve("${segment.segmentIndex}${FileLayout.META}")

        val tmpBin = sessionDir.resolve(
            "${segment.segmentIndex}${FileLayout.BIN}${FileLayout.TMP_INFIX}${UUID.randomUUID()}",
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

        val lock = keyLocks.computeIfAbsent(segment.uploadSessionId to segment.segmentIndex) {
            ReentrantLock()
        }
        lock.lock()
        try {
            // Sidecar first, then data, both via Files.move(... ATOMIC_MOVE)
            // per §6.3. Sidecar-first means a crash between the two
            // steps leaves only a dangling sidecar (no visible target
            // without sidecar); cleanupOrphans removes it. Under the
            // per-key lock, fail-on-existing is deterministic.
            val sidecar = Sidecar(written.sha256, written.sizeBytes, segment.segmentOffset)
            if (Files.exists(finalBin)) {
                Files.deleteIfExists(tmpBin)
                return resolveExisting(segment, written.sha256, finalMeta)
            }
            return try {
                Sidecar.writeAtomically(finalMeta, sidecar)
                atomicMove(tmpBin, finalBin)
                WriteSegmentOutcome.Stored(segment.copy(segmentSha256 = written.sha256))
            } catch (_: FileAlreadyExistsException) {
                Files.deleteIfExists(tmpBin)
                resolveExisting(segment, written.sha256, finalMeta)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun resolveExisting(
        segment: UploadSegment,
        attemptedSha: String,
        finalMeta: Path,
    ): WriteSegmentOutcome {
        val existingSha = Sidecar.read(finalMeta).sha256
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

    private fun atomicMove(source: Path, target: Path) {
        if (Files.exists(target)) throw FileAlreadyExistsException(target.toString())
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
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
                    val sidecar = Sidecar.read(meta)
                    UploadSegment(
                        uploadSessionId = uploadSessionId,
                        segmentIndex = index,
                        segmentOffset = sidecar.segmentOffset
                            ?: error("segment $index of $uploadSessionId has no persisted offset"),
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

    fun cleanupOrphans(activeSessions: Set<String>): Int =
        cleanupOrphans(root, activeSessions)

    companion object {
        /**
         * Layout-aware orphan sweep over `<root>/segments/...` for the
         * §6.21 startup cleanup. Session dirs whose id is NOT in
         * [activeSessions] are dropped recursively (segments without
         * surviving session metadata are unreferenceable after restart);
         * dirs that ARE active have their internal tmp / dangling
         * sidecar / dangling bin leftovers removed.
         *
         * The companion form lets `McpCommands` run the sweep without
         * constructing a [FileBackedUploadSegmentStore] (which would
         * eagerly create `segments/`). Returns the number of session
         * dirs and orphan internals removed.
         */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fun cleanupOrphans(root: Path, activeSessions: Set<String>): Int {
            val segmentsRoot = root.resolve("segments")
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
                // Sidecar present without matching .bin = crash between
                // sidecar-move and data-move. Drop the dangling sidecar
                // so a retry can write a fresh one.
                val isOrphanMeta = name.endsWith(FileLayout.META) &&
                    !Files.exists(sessionDir.resolve(name.removeSuffix(FileLayout.META) + FileLayout.BIN))
                // Data present without matching .meta.json = crash
                // before sidecar move (impossible under the new write
                // order, covered defensively for legacy spools).
                val isOrphanBin = name.endsWith(FileLayout.BIN) &&
                    !Files.exists(sessionDir.resolve(name.removeSuffix(FileLayout.BIN) + FileLayout.META))
                if (isTmp || isOrphanMeta || isOrphanBin) {
                    Files.deleteIfExists(entry)
                    removed++
                }
            }
            return removed
        }
    }
}
