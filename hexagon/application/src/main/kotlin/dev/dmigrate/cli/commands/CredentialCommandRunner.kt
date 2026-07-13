package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.CredentialStoreException
import dev.dmigrate.driver.connection.CredentialStorePort

/**
 * Runner für `config credentials set`/`list` (LN-025 Slice 1). **Dünn:** ruft den
 * [CredentialStorePort] und mappt [CredentialStoreException] auf Exit 7. Prompting, Env und
 * Master-Secret-Beschaffung sind CLI-Sache (Wiring), damit der Runner TTY-frei mit einem Fake-Store
 * testbar bleibt. Der Store ist bereits mit dem beschafften Master-Secret-Provider konstruiert.
 */
class CredentialCommandRunner(
    private val store: CredentialStorePort,
    private val stdout: (String) -> Unit,
    private val stderr: (String) -> Unit,
) {

    /** Legt Zugangsdaten ab (upsert). [password] wird nach Gebrauch gewiped. Exit 0 / 7. */
    fun set(name: String, user: String, password: CharArray): Int =
        try {
            store.put(name, user, password)
            stderr("Stored credentials for '$name'.")
            0
        } catch (e: CredentialStoreException) {
            stderr("Error: ${e.message}")
            7
        } finally {
            password.fill(' ')
        }

    /** Gibt die gespeicherten Namen aus (nie Werte/Passwörter). Exit 0 / 7. */
    fun list(): Int =
        try {
            store.listNames().forEach(stdout)
            0
        } catch (e: CredentialStoreException) {
            stderr("Error: ${e.message}")
            7
        }
}
