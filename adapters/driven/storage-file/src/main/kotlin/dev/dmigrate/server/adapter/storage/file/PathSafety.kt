package dev.dmigrate.server.adapter.storage.file

internal object PathSafety {

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

    fun requireSafeId(id: String, label: String) {
        require(ID_PATTERN.matches(id)) {
            "$label '$id' must match ${ID_PATTERN.pattern}"
        }
    }

    fun requireNonNegativeIndex(index: Int) {
        require(index >= 0) { "segmentIndex must be >= 0, was $index" }
    }
}
