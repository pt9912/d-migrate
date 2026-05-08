package dev.dmigrate.text

/**
 * Unicode normalization mode used by [UnicodeTextService].
 *
 * Lives in `dev.dmigrate.text` (not `cli`) because it is shared between
 * the CLI driving adapter, the MCP/REST/gRPC adapters and the application
 * layer. The neutral package keeps the type free of CLI-specific
 * connotations.
 */
enum class UnicodeNormalizationMode {
    NFC,
    NFD,
    NFKC,
    NFKD,
}
