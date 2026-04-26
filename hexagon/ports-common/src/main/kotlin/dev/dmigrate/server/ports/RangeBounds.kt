package dev.dmigrate.server.ports

object RangeBounds {

    fun check(offset: Long, length: Long, size: Long) {
        require(offset >= 0) { "offset must be >= 0, was $offset" }
        require(length >= 0) { "length must be >= 0, was $length" }
        require(offset <= size) { "offset $offset > size $size" }
        require(offset + length <= size) {
            "offset+length ${offset + length} > size $size"
        }
    }
}
