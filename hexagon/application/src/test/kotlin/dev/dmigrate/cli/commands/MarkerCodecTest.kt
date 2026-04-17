package dev.dmigrate.cli.commands

import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1): Roundtrip-Tests
 * fuer den Marker-Codec. Der Codec wiederverwendet
 * `TemporalFormatPolicy.parseSinceLiteral` fuer die Decode-Seite, damit
 * der Manifest-Vertrag keine eigene Typ-Annotation tragen muss.
 */
class MarkerCodecTest : FunSpec({

    test("encodeValue maps null to null and everything else to toString()") {
        MarkerCodec.encodeValue(null) shouldBe null
        MarkerCodec.encodeValue(42) shouldBe "42"
        MarkerCodec.encodeValue(42L) shouldBe "42"
        MarkerCodec.encodeValue("acme") shouldBe "acme"
    }

    test("decodeValue maps null to null and parses other literals via TemporalFormatPolicy") {
        MarkerCodec.decodeValue(null) shouldBe null
        // Numeric literal → Long (see TemporalFormatPolicy.parseSinceLiteral)
        MarkerCodec.decodeValue("42").shouldBeInstanceOf<Long>() shouldBe 42L
        // ISO date → LocalDate
        MarkerCodec.decodeValue("2026-04-16")
            .shouldBeInstanceOf<LocalDate>() shouldBe LocalDate.of(2026, 4, 16)
        // ISO offset datetime → OffsetDateTime
        val offset = MarkerCodec.decodeValue("2026-04-16T10:15:00+02:00")
        offset.shouldBeInstanceOf<OffsetDateTime>()
    }

    test("toPersisted converts ResumeMarker + Position to CheckpointResumePosition") {
        val marker = ResumeMarker(
            markerColumn = "updated_at",
            tieBreakerColumns = listOf("tenant", "id"),
            position = null,
        )
        val position = ResumeMarker.Position(
            lastMarkerValue = LocalDate.of(2026, 4, 16),
            lastTieBreakerValues = listOf("acme", 42L),
        )
        val persisted = MarkerCodec.toPersisted(marker, position)
        persisted.markerColumn shouldBe "updated_at"
        persisted.markerValue shouldBe "2026-04-16"
        persisted.tieBreakerColumns shouldContainExactly listOf("tenant", "id")
        persisted.tieBreakerValues shouldContainExactly listOf("acme", "42")
    }

    test("toRuntimePosition reverses toPersisted (roundtrip through manifest form)") {
        val persisted = CheckpointResumePosition(
            markerColumn = "updated_at",
            markerValue = "2026-04-16",
            tieBreakerColumns = listOf("tenant", "id"),
            tieBreakerValues = listOf("acme", "42"),
        )
        val runtime = MarkerCodec.toRuntimePosition(persisted)
        runtime.lastMarkerValue shouldBe LocalDate.of(2026, 4, 16)
        runtime.lastTieBreakerValues.size shouldBe 2
        runtime.lastTieBreakerValues[0] shouldBe "acme"
        (runtime.lastTieBreakerValues[1] as Long) shouldBe 42L
    }

    test("toPersisted encodes null marker value without corrupting tie-breakers") {
        val marker = ResumeMarker(
            markerColumn = "seq",
            tieBreakerColumns = listOf("id"),
            position = null,
        )
        val position = ResumeMarker.Position(
            lastMarkerValue = null,
            lastTieBreakerValues = listOf(null),
        )
        val persisted = MarkerCodec.toPersisted(marker, position)
        persisted.markerValue shouldBe null
        persisted.tieBreakerValues shouldContainExactly listOf<String?>(null)
    }

    test("toRuntimePosition handles null marker value and null tie-breaker values") {
        val persisted = CheckpointResumePosition(
            markerColumn = "seq",
            markerValue = null,
            tieBreakerColumns = listOf("id"),
            tieBreakerValues = listOf(null),
        )
        val runtime = MarkerCodec.toRuntimePosition(persisted)
        runtime.lastMarkerValue shouldBe null
        runtime.lastTieBreakerValues shouldContainExactly listOf<Any?>(null)
    }
})
