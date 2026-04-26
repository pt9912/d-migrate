package dev.dmigrate.server.ports.contract

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Test-only [Clock] whose [now] can be advanced explicitly. Used by
 * fingerprint, idempotency-lease, upload-expiry and rate-limiter tests
 * that need to drive time forward deterministically without sleeping.
 */
class MutableClock(start: Instant = Fixtures.NOW) : Clock() {

    var now: Instant = start

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}
