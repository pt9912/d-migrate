package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class FileBackedArtifactContentStore(private val root: Path) : ArtifactContentStore {

    private val artifactsRoot: Path = root.resolve("artifacts").also { Files.createDirectories(it) }
    private val keyLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome {
        PathSafety.requireSafeId(artifactId, "artifactId")
        val finalBin = finalBinFor(artifactId)
        val finalMeta = metaFor(finalBin, artifactId)
        val shardDir = finalBin.parent
        Files.createDirectories(shardDir)

        // §6.3 demands the tmp file lives next to the final path so
        // the atomic move never crosses a filesystem boundary.
        val tmpBin = shardDir.resolve(
            "$artifactId${FileLayout.BIN}${FileLayout.TMP_INFIX}${UUID.randomUUID()}",
        )
        val written = try {
            StreamingHashWriter.copyAndHash(source, tmpBin)
        } catch (failure: IOException) {
            Files.deleteIfExists(tmpBin)
            throw failure
        }

        if (written.sizeBytes != expectedSizeBytes) {
            Files.deleteIfExists(tmpBin)
            return WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, written.sizeBytes)
        }

        val lock = keyLocks.computeIfAbsent(artifactId) { ReentrantLock() }
        lock.lock()
        try {
            // Sidecar first, then data, both via Files.move(... ATOMIC_MOVE)
            // per §6.3. The per-key lock plus fail-on-existing in
            // Sidecar.writeAtomically and atomicMove gives the §6.3
            // "exactly one Stored under concurrency" contract back —
            // POSIX rename(2) silently overwrites otherwise.
            val sidecar = Sidecar(written.sha256, written.sizeBytes)
            if (Files.exists(finalBin)) {
                Files.deleteIfExists(tmpBin)
                return resolveExisting(artifactId, written.sha256, finalMeta)
            }
            return try {
                Sidecar.writeAtomically(finalMeta, sidecar)
                atomicMove(tmpBin, finalBin)
                WriteArtifactOutcome.Stored(artifactId, written.sha256, written.sizeBytes)
            } catch (_: FileAlreadyExistsException) {
                Files.deleteIfExists(tmpBin)
                resolveExisting(artifactId, written.sha256, finalMeta)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun resolveExisting(
        artifactId: String,
        attemptedSha: String,
        finalMeta: Path,
    ): WriteArtifactOutcome {
        val existingSha = Sidecar.read(finalMeta).sha256
        return if (existingSha == attemptedSha) {
            WriteArtifactOutcome.AlreadyExists(artifactId, existingSha)
        } else {
            WriteArtifactOutcome.Conflict(artifactId, existingSha, attemptedSha)
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

    override fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream {
        PathSafety.requireSafeId(artifactId, "artifactId")
        return try {
            RangeRead.open(finalBinFor(artifactId), offset, length)
        } catch (_: NoSuchFileException) {
            error("artifact $artifactId not found")
        }
    }

    override fun exists(artifactId: String): Boolean {
        PathSafety.requireSafeId(artifactId, "artifactId")
        return Files.exists(finalBinFor(artifactId))
    }

    override fun delete(artifactId: String): Boolean {
        PathSafety.requireSafeId(artifactId, "artifactId")
        val finalBin = finalBinFor(artifactId)
        val finalMeta = metaFor(finalBin, artifactId)
        val removed = Files.deleteIfExists(finalBin)
        Files.deleteIfExists(finalMeta)
        return removed
    }

    private fun finalBinFor(artifactId: String): Path =
        artifactsRoot.resolve(shardOf(artifactId)).resolve("$artifactId${FileLayout.BIN}")

    private fun metaFor(finalBin: Path, artifactId: String): Path =
        finalBin.resolveSibling("$artifactId${FileLayout.META}")

    private fun shardOf(artifactId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(artifactId.toByteArray(Charsets.UTF_8))
        return digest.toHex().substring(0, 2)
    }

    companion object {
        /**
         * Layout-aware orphan sweep over `<root>/artifacts/<shard>/...`
         * for the §6.21 startup cleanup. `.bin` and matching
         * `.meta.json` siblings are treated as a unit; dangling
         * sidecar / tmp leftovers are removed defensively after the
         * same retention cutoff. Files that do not look like store
         * artefacts (extension neither `.bin`, `.meta.json` nor a
         * `.tmp.<uuid>` infix) are left untouched so operator-managed
         * tooling can drop diagnostic files into the dir.
         *
         * @param olderThan keep files newer than this; pass `null`
         *   for "delete every store file unconditionally" — used by
         *   the `0`/`0s` retention policy.
         * @return number of `.bin` files removed (sidecar/tmp deletions
         *   are not counted to keep the operator-facing diagnostic
         *   stable).
         */
        fun cleanupOrphans(
            root: Path,
            olderThan: Duration?,
            clock: Clock = Clock.systemUTC(),
        ): Int {
            val artifactsRoot = root.resolve("artifacts")
            if (!Files.exists(artifactsRoot)) return 0
            val cutoff: Instant? = olderThan?.let { Instant.now(clock).minus(it) }
            val storeFiles = Files.walk(artifactsRoot).use { walk ->
                walk
                    .filter { Files.isRegularFile(it) }
                    .filter {
                        val name = it.fileName.toString()
                        name.endsWith(FileLayout.BIN) ||
                            name.endsWith(FileLayout.META) ||
                            name.contains(FileLayout.TMP_INFIX)
                    }
                    .toList()
            }
            var removed = 0
            // Pass 1: drop bin + matching meta as a unit so sidecars
            // never outlive their data file.
            for (file in storeFiles) {
                val name = file.fileName.toString()
                if (!name.endsWith(FileLayout.BIN)) continue
                val mtime = file.lastModified() ?: continue
                if (!shouldDelete(mtime, cutoff)) continue
                val meta = file.resolveSibling(name.removeSuffix(FileLayout.BIN) + FileLayout.META)
                if (Files.deleteIfExists(file)) removed++
                Files.deleteIfExists(meta)
            }
            // Pass 2: prune dangling sidecars and tmp leftovers.
            for (file in storeFiles) {
                if (!Files.exists(file)) continue
                val name = file.fileName.toString()
                if (name.endsWith(FileLayout.BIN)) continue
                val mtime = file.lastModified() ?: continue
                if (shouldDelete(mtime, cutoff)) {
                    Files.deleteIfExists(file)
                }
            }
            return removed
        }

        private fun shouldDelete(mtime: Instant, cutoff: Instant?): Boolean =
            cutoff == null || mtime.isBefore(cutoff)

        private fun Path.lastModified(): Instant? = try {
            Files.getLastModifiedTime(this).toInstant()
        } catch (_: IOException) {
            null
        }
    }
}
