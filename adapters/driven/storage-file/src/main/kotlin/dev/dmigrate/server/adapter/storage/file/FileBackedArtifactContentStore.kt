package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
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
        return try {
            Files.createLink(finalBin, tmpBin)
            Files.deleteIfExists(tmpBin)
            Sidecar.writeAtomically(finalMeta, Sidecar(written.sha256, written.sizeBytes))
            WriteArtifactOutcome.Stored(artifactId, written.sha256, written.sizeBytes)
        } catch (_: FileAlreadyExistsException) {
            Files.deleteIfExists(tmpBin)
            val existingSha = Sidecar.readOrRecover(finalBin, finalMeta).sha256
            if (existingSha == written.sha256) {
                WriteArtifactOutcome.AlreadyExists(artifactId, existingSha)
            } else {
                WriteArtifactOutcome.Conflict(artifactId, existingSha, written.sha256)
            }
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
