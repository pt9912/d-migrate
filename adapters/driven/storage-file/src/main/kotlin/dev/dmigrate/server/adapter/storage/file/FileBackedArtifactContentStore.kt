package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class FileBackedArtifactContentStore(private val root: Path) : ArtifactContentStore {

    private val artifactsRoot: Path = root.resolve("artifacts").also { Files.createDirectories(it) }
    private val stagingDir: Path = artifactsRoot.resolve(FileLayout.STAGING_DIR)
        .also { Files.createDirectories(it) }

    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome {
        PathSafety.requireSafeId(artifactId, "artifactId")
        val finalBin = finalBinFor(artifactId)
        val finalMeta = metaFor(finalBin, artifactId)

        // Always hash the new content first — the §6.3 contract demands
        // AlreadyExists vs Conflict is decided by the new SHA, not by
        // mere existence of the slot.
        val tmpBin = stagingDir.resolve("$artifactId${FileLayout.BIN}${FileLayout.TMP_INFIX}${UUID.randomUUID()}")
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

        Files.createDirectories(finalBin.parent)
        // Sidecar first, then data, both via Files.move(... ATOMIC_MOVE)
        // per §6.3. Sidecar-first means a crash between the two steps
        // leaves only a dangling sidecar (no visible target without
        // sidecar); cleanupOrphans removes it. Pre-`exists` check on
        // each move provides the plan's "fail-on-existing" semantics
        // on Linux, where rename(2) overwrites silently otherwise.
        val sidecar = Sidecar(written.sha256, written.sizeBytes)
        if (Files.exists(finalBin)) {
            Files.deleteIfExists(tmpBin)
            return resolveExisting(artifactId, written.sha256, finalMeta)
        }
        Sidecar.writeAtomically(finalMeta, sidecar)
        return try {
            atomicMove(tmpBin, finalBin)
            WriteArtifactOutcome.Stored(artifactId, written.sha256, written.sizeBytes)
        } catch (_: IOException) {
            Files.deleteIfExists(tmpBin)
            resolveExisting(artifactId, written.sha256, finalMeta)
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
        if (Files.exists(target)) throw IOException("target $target already exists")
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
}
