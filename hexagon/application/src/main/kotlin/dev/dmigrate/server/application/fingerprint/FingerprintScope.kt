package dev.dmigrate.server.application.fingerprint

enum class FingerprintScope(val wireValue: String) {
    START_TOOL("start_tool"),
    SYNC_TOOL("sync_tool"),
    UPLOAD_INIT("upload_init"),
}
