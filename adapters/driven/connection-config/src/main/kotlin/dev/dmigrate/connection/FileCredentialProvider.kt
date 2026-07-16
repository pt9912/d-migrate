package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialProvider
import dev.dmigrate.server.ports.CredentialResolution
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `file:`-[CredentialProvider] (ADR 0035): löst `file:/pfad` auf, indem der **Datei-Inhalt** (getrimmt)
 * als vollständige Connect-URL genommen wird (World-B-Parität). Erster Nicht-`env:`-Provider —
 * cross-platform, headless-tauglich (CI/Server/k8s-Secret-Mounts).
 *
 * Fail-closed: fehlende/kein reguläres File → [CredentialResolution.REASON_FILE_NOT_FOUND];
 * I/O-Fehler → [CredentialResolution.REASON_FILE_UNREADABLE]; leerer Inhalt →
 * [CredentialResolution.REASON_EMPTY_VALUE]. Der **Datei-Inhalt** (das Secret) wird niemals in
 * `detail`/Logs echot — nur der operator-taugliche Pfad.
 *
 * Ein Size-Cap ([MAX_FILE_BYTES]) fängt versehentlich referenzierte Riesen-Dateien fail-closed ab
 * (statt uncaught OOM). File-Permissions werden **bewusst nicht** erzwungen und Symlinks **gefolgt**
 * (k8s-Secret-Mounts sind world-readable + symlinked; s. ADR 0035 Security-Review).
 * [readFile]/[isRegularFile]/[fileSize] sind für deterministische Fehlerpfad-Tests injizierbar.
 */
class FileCredentialProvider(
    private val isRegularFile: (Path) -> Boolean = { Files.isRegularFile(it) },
    private val readFile: (Path) -> String = { Files.readString(it) },
    private val fileSize: (Path) -> Long = { Files.size(it) },
) : CredentialProvider {

    override val scheme: String = SCHEME

    override fun resolve(credentialRef: String): CredentialResolution {
        if (!credentialRef.startsWith(SCHEME)) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_PROVIDER_MISSING,
                detail = "credentialRef is not a '$SCHEME' reference",
            )
        }
        val rawPath = credentialRef.removePrefix(SCHEME)
        if (rawPath.isBlank()) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_FILE_NOT_FOUND,
                detail = "file: credentialRef carries no path",
            )
        }
        val path = try {
            Paths.get(rawPath)
        } catch (_: InvalidPathException) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_FILE_NOT_FOUND,
                detail = "file: credentialRef path is invalid",
            )
        }
        if (!isRegularFile(path)) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_FILE_NOT_FOUND,
                detail = "credential file does not exist or is not a regular file: $path",
            )
        }
        val content = try {
            // Size-Cap: verhindert, dass ein versehentlich referenzierter Riesen-Pfad (Log/DB-Datei)
            // einen uncaught OutOfMemoryError statt eines sauberen fail-closed Failure erzeugt.
            // Eine Connect-URL ist stets << 1 MiB.
            if (fileSize(path) > MAX_FILE_BYTES) {
                return CredentialResolution.Failure(
                    reason = CredentialResolution.REASON_FILE_UNREADABLE,
                    detail = "credential file exceeds $MAX_FILE_BYTES bytes: $path",
                )
            }
            readFile(path)
        } catch (_: IOException) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_FILE_UNREADABLE,
                detail = "credential file is not readable: $path",
            )
        }
        // Führendes UTF-8-BOM strippen (Windows-Editoren schreiben es; es ist kein Whitespace,
        // würde `trim()` überleben und die URL/den Leer-Check verfälschen), dann trimmen.
        val url = content.removePrefix("\uFEFF").trim()
        if (url.isEmpty()) {
            return CredentialResolution.Failure(
                reason = CredentialResolution.REASON_EMPTY_VALUE,
                detail = "credential file is empty: $path",
            )
        }
        return CredentialResolution.Success(url = url)
    }

    companion object {
        const val SCHEME: String = "file:"

        /** Obergrenze für die Credential-Datei (1 MiB) — eine Connect-URL ist um Größenordnungen kleiner. */
        const val MAX_FILE_BYTES: Long = 1_048_576
    }
}
