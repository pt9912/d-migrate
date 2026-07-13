package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.dmigrate.cli.DMigrate

/**
 * `d-migrate config` — Konfigurations- und Credential-Verwaltung (LN-025 Slice 1). Reine
 * Kommandogruppe; die Arbeit machen die Leaf-Kommandos unter `config credentials`.
 */
class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "Configuration and credential management"

    init {
        subcommands(ConfigCredentialsCommand())
    }

    override fun run() = Unit
}

class ConfigCredentialsCommand : CliktCommand(name = "credentials") {
    override fun help(context: Context) = "Manage the encrypted local credential store"

    init {
        subcommands(ConfigCredentialsSetCommand(), ConfigCredentialsListCommand())
    }

    override fun run() = Unit
}

/**
 * `config credentials set` — legt verschlüsselte DB-Zugangsdaten unter einem Verbindungsnamen ab. Das
 * Master-Secret kommt aus `D_MIGRATE_MASTER_PASSWORD` oder (interaktiv) per verstecktem Prompt; bei
 * Neuanlage mit Bestätigung. Das DB-Passwort kommt aus `--password` (unsicher) oder per Prompt.
 */
class ConfigCredentialsSetCommand : CliktCommand(name = "set") {
    override fun help(context: Context) = "Store encrypted database credentials under a connection name."

    private val name by option("--name", help = "Connection name.").required()
    private val user by option("--user", help = "Database user.").required()
    private val password by option(
        "--password",
        help = "Database password (INSECURE on the command line — omit to be prompted).",
    )

    override fun run() {
        if (password == null && System.console() == null) {
            echo("Error: --password is required when not running in an interactive terminal.", err = true)
            throw ProgramResult(2)
        }
        val exit = ConfigCredentialsWiring.executeSet(
            options = CredentialSetOptions(name = name, user = user, configPath = rootConfigPath()),
            masterSecretResolver = MasterSecretResolver(prompt = ::promptMasterSecret),
            dbPasswordProvider = ::acquireDbPassword,
            stdout = { echo(it) },
            stderr = { echo(it, err = true) },
        )
        if (exit != 0) throw ProgramResult(exit)
    }

    private fun promptMasterSecret(confirm: Boolean): CharArray? {
        val console = System.console() ?: return null
        val first = console.readPassword("Master passphrase: ") ?: return null
        if (!confirm) return first
        val second = console.readPassword("Confirm master passphrase: ")
        return when {
            second == null -> { first.fill(' '); null }
            first.contentEquals(second) -> { second.fill(' '); first }
            else -> {
                first.fill(' '); second.fill(' ')
                echo("Error: passphrases do not match.", err = true)
                null
            }
        }
    }

    private fun acquireDbPassword(): CharArray {
        password?.let {
            echo("Warning: --password on the command line may leak via shell history / process list.", err = true)
            return it.toCharArray()
        }
        return System.console()?.readPassword("Database password for '$user': ") ?: CharArray(0)
    }

    private fun rootConfigPath() = (currentContext.parent?.parent?.parent?.command as? DMigrate)?.config
}

/** `config credentials list` — listet gespeicherte Verbindungsnamen (nie Werte/Passwörter). */
class ConfigCredentialsListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List stored connection names (no values)."

    override fun run() {
        val exit = ConfigCredentialsWiring.executeList(
            options = CredentialListOptions(configPath = rootConfigPath()),
            masterSecretResolver = MasterSecretResolver(
                prompt = { System.console()?.readPassword("Master passphrase: ") },
            ),
            stdout = { echo(it) },
            stderr = { echo(it, err = true) },
        )
        if (exit != 0) throw ProgramResult(exit)
    }

    private fun rootConfigPath() = (currentContext.parent?.parent?.parent?.command as? DMigrate)?.config
}
