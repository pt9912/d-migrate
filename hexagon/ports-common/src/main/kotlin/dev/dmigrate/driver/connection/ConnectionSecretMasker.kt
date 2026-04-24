package dev.dmigrate.driver.connection

/**
 * Shared masking logic for connection URLs and JDBC/DSN-like strings that may
 * contain inline secrets in the authority or query parameters.
 */
object ConnectionSecretMasker {

    internal val sensitiveQueryKeys = listOf(
        "password",
        "pwd",
        "passwd",
        "passphrase",
        "sslpassword",
        "token",
        "access_token",
        "access-token",
        "api_key",
        "api-key",
        "secret",
    )

    private val authorityPasswordPattern = Regex(
        """(?<prefix>(?:jdbc:)?[a-zA-Z][a-zA-Z0-9+\-.]*://)(?<user>[^:/@?#]*):(?<pwd>[^@/?#]*)@"""
    )
    private val sensitiveQueryParamPattern = Regex(
        """(?i)(?<lead>[?&;])(?<key>${sensitiveQueryKeys.joinToString("|") { Regex.escape(it) }})=(?<value>[^&#;\s]*)"""
    )

    fun mask(raw: String): String {
        val maskedAuthority = authorityPasswordPattern.replace(raw) { match ->
            "${match.groups["prefix"]!!.value}${match.groups["user"]!!.value}:***@"
        }
        return sensitiveQueryParamPattern.replace(maskedAuthority) { match ->
            "${match.groups["lead"]!!.value}${match.groups["key"]!!.value}=***"
        }
    }
}
