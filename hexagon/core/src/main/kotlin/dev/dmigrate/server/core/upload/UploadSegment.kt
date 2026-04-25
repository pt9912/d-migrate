package dev.dmigrate.server.core.upload

data class UploadSegment(
    val uploadSessionId: String,
    val segmentIndex: Int,
    val segmentOffset: Long,
    val sizeBytes: Long,
    val segmentSha256: String,
)
