package dev.dmigrate.cli.commands

import dev.dmigrate.connection.AesGcmCredentialStore
import dev.dmigrate.driver.connection.CredentialStorePort
import dev.dmigrate.driver.connection.StoredCredential
import java.nio.file.Path

/**
 * Master-Secret-/Store-Cache für die Dauer **einer** CLI-Invocation (LN-049 Stufe 4). Das Master-Secret
 * wird höchstens **einmal** beschafft (Env `D_MIGRATE_MASTER_PASSWORD` oder Prompt) und der Store einmal
 * gebaut, damit eine Operation den Store nicht mehrfach entschlüsselt / mehrfach nach dem Master-Secret
 * fragt.
 *
 * **Copy-on-read (kritisch):** der Store wiped die vom `masterSecretProvider` gelieferte Kopie nach jeder
 * Entschlüsselung — der Provider gibt daher `cachedSecret.copyOf()` heraus, sonst bekäme der zweite Konsum
 * ein geblanktes Secret. [wipe] gibt das gecachte Secret best-effort am Invocation-Ende frei.
 *
 * Kein fail-closed: fehlt das Master-Secret (non-TTY ohne Env), liefert [resolve] `null` und der Aufrufer
 * verbindet passwortlos weiter (statt hart zu scheitern).
 */
internal class CredentialFillSession(
    private val masterSecretResolver: MasterSecretResolver,
    baseDir: Path,
    storeFactory: (Path, () -> CharArray) -> CredentialStorePort = { dir, provider ->
        AesGcmCredentialStore(baseDir = dir, masterSecretProvider = provider)
    },
) {
    private var cachedSecret: CharArray? = null
    private val store: CredentialStorePort = storeFactory(baseDir) {
        cachedSecret?.copyOf() ?: error("master secret accessed before it was resolved")
    }

    /** Ob überhaupt ein Store existiert (ohne Entschlüsselung / ohne Master-Secret). */
    fun storeExists(): Boolean = store.isInitialized()

    /**
     * Zugangsdaten für [name], oder `null` wenn kein Store existiert, kein Master-Secret verfügbar ist
     * (non-TTY ohne Env) oder kein Eintrag passt. Wirft `CredentialStoreException` bei falschem
     * Master-Secret / beschädigtem Store (Aufrufer mappt auf Exit 7).
     */
    fun resolve(name: String): StoredCredential? {
        if (!store.isInitialized()) return null
        if (cachedSecret == null) {
            cachedSecret = masterSecretResolver.resolve(isNewStore = false) ?: return null
        }
        return store.resolve(name)
    }

    /** Best-effort-Freigabe des gecachten Master-Secrets. */
    fun wipe() {
        cachedSecret?.fill(' ')
        cachedSecret = null
    }
}
