package dev.dmigrate.cli.commands

/**
 * Beschafft das Master-Secret für den Credential-Store (LN-025 Slice 1). Präzedenz:
 * `D_MIGRATE_MASTER_PASSWORD` **>** interaktiver TTY-Prompt. **Non-TTY ohne Env → `null`**
 * (fail-closed → Exit 7). Bei Neuanlage des Stores promptet [prompt] mit Bestätigung (Doppel-Eingabe),
 * damit ein Tippfehler den Store nicht dauerhaft unentschlüsselbar macht.
 *
 * `env`/`isTty`/`prompt` sind injizierbar, damit die Beschaffungslogik ohne echtes TTY testbar ist.
 */
class MasterSecretResolver(
    private val prompt: (confirm: Boolean) -> CharArray?,
    private val env: (String) -> String? = System::getenv,
    private val isTty: () -> Boolean = { System.console() != null },
) {

    /** @param isNewStore steuert die Confirm-Doppel-Eingabe. @return `null` = fail-closed. */
    fun resolve(isNewStore: Boolean): CharArray? {
        val fromEnv = env(ENV_VAR)
        if (!fromEnv.isNullOrEmpty()) return fromEnv.toCharArray()
        if (!isTty()) return null
        return prompt(isNewStore)
    }

    companion object {
        const val ENV_VAR = "D_MIGRATE_MASTER_PASSWORD"
    }
}
