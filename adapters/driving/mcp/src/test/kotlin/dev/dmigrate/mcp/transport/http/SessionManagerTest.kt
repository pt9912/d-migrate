package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpServiceImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private fun newState(now: Instant) = SessionState(
    negotiatedProtocolVersion = "2025-11-25",
    createdAt = now,
    lastSeen = now,
    service = McpServiceImpl(serverVersion = "0.1.0"),
)

class SessionManagerTest : FunSpec({

    test("create returns a unique UUID and stores the state") {
        val now = Instant.parse("2026-04-26T12:00:00Z")
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = { now }).use { sm ->
            val id = sm.create(newState(now))
            sm.size() shouldBe 1
            sm.peek(id).shouldNotBeNull()
        }
    }

    test("peek does NOT refresh lastSeen") {
        val clockRef = AtomicReference(Instant.parse("2026-04-26T12:00:00Z"))
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = clockRef::get).use { sm ->
            val id = sm.create(newState(clockRef.get()))
            clockRef.set(Instant.parse("2026-04-26T12:25:00Z"))
            val state = sm.peek(id).shouldNotBeNull()
            state.lastSeen shouldBe Instant.parse("2026-04-26T12:00:00Z")
        }
    }

    test("touch refreshes lastSeen") {
        val clockRef = AtomicReference(Instant.parse("2026-04-26T12:00:00Z"))
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = clockRef::get).use { sm ->
            val id = sm.create(newState(clockRef.get()))
            clockRef.set(Instant.parse("2026-04-26T12:25:00Z"))
            sm.touch(id)
            sm.peek(id)!!.lastSeen shouldBe Instant.parse("2026-04-26T12:25:00Z")
        }
    }

    test("touch on unknown id is a no-op") {
        val now = Instant.parse("2026-04-26T12:00:00Z")
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = { now }).use { sm ->
            sm.touch(java.util.UUID.randomUUID()) // must not throw
            sm.size() shouldBe 0
        }
    }

    test("evictExpired removes entries older than idleTimeout") {
        val clockRef = AtomicReference(Instant.parse("2026-04-26T12:00:00Z"))
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = clockRef::get).use { sm ->
            val id = sm.create(newState(clockRef.get()))
            clockRef.set(Instant.parse("2026-04-26T12:35:00Z"))
            sm.evictExpired()
            sm.peek(id).shouldBeNull()
        }
    }

    test("evictExpired keeps entries inside idleTimeout") {
        val clockRef = AtomicReference(Instant.parse("2026-04-26T12:00:00Z"))
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = clockRef::get).use { sm ->
            val id = sm.create(newState(clockRef.get()))
            clockRef.set(Instant.parse("2026-04-26T12:29:59Z"))
            sm.evictExpired()
            sm.peek(id).shouldNotBeNull()
        }
    }

    test("remove returns true on hit, false on miss") {
        val now = Instant.parse("2026-04-26T12:00:00Z")
        SessionManager(idleTimeout = Duration.ofMinutes(30), clock = { now }).use { sm ->
            val id = sm.create(newState(now))
            sm.remove(id) shouldBe true
            sm.remove(id) shouldBe false
        }
    }

    test("close cancels the reaper and clears the map") {
        val now = Instant.parse("2026-04-26T12:00:00Z")
        val sm = SessionManager(idleTimeout = Duration.ofMinutes(30), clock = { now })
        sm.create(newState(now))
        sm.close()
        sm.size() shouldBe 0
    }
})
