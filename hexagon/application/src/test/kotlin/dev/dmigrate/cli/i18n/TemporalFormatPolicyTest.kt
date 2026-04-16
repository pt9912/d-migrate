package dev.dmigrate.cli.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 0.8.0 Phase E — Kontraktabdeckung fuer [TemporalFormatPolicy].
 *
 * Referenz: `docs/ImpPlan-0.8.0-E.md` Abschnitt 4 (Leitlinien) und
 * Abschnitt 6 (Teststrategie).
 */
class TemporalFormatPolicyTest : FunSpec({

    // ────────────────────────────────────────────────────────────
    // §4.1 / §4.3 — Lokale Werte bleiben ohne Offset
    // ────────────────────────────────────────────────────────────

    context("§4.3 formatLocalDate/Time/DateTime — ISO ohne Offset") {

        test("LocalDate -> ISO 8601 ohne Offset") {
            TemporalFormatPolicy.formatLocalDate(LocalDate.of(2026, 4, 16)) shouldBe "2026-04-16"
        }

        test("LocalTime -> ISO 8601 ohne Offset") {
            TemporalFormatPolicy.formatLocalTime(LocalTime.of(10, 15, 30)) shouldBe "10:15:30"
        }

        test("LocalDateTime -> ISO 8601 ohne Offset") {
            TemporalFormatPolicy.formatLocalDateTime(
                LocalDateTime.of(2026, 4, 16, 10, 15, 30)
            ) shouldBe "2026-04-16T10:15:30"
        }
    }

    // ────────────────────────────────────────────────────────────
    // §4.2 — Offsethaltige Werte bleiben offsethaltig
    // ────────────────────────────────────────────────────────────

    context("§4.2 formatOffsetDateTime / formatZonedDateTime — Offset bleibt") {

        test("OffsetDateTime traegt Offset in der Ausgabe") {
            val value = OffsetDateTime.of(2026, 4, 16, 10, 15, 30, 0, ZoneOffset.ofHours(2))
            TemporalFormatPolicy.formatOffsetDateTime(value) shouldBe "2026-04-16T10:15:30+02:00"
        }

        test("ZonedDateTime wird offsetbasiert serialisiert; ZoneId-Region entfaellt bewusst") {
            val value = ZonedDateTime.of(
                LocalDateTime.of(2026, 4, 16, 10, 15, 30),
                ZoneId.of("Europe/Berlin"),
            )
            // §4.2 / §8 R3: ZoneId ist nicht Teil des 0.8.0-Vertrags,
            // aber der Offset bleibt verbindlich enthalten.
            TemporalFormatPolicy.formatZonedDateTime(value) shouldBe "2026-04-16T10:15:30+02:00"
        }
    }

    // ────────────────────────────────────────────────────────────
    // §4.3 hasOffsetOrZone-Heuristik
    // ────────────────────────────────────────────────────────────

    context("§4.3 hasOffsetOrZone") {

        test("ohne T -> niemals zoniert") {
            TemporalFormatPolicy.hasOffsetOrZone("2026-04-16") shouldBe false
        }

        test("lokaler DateTime-String ohne Offset -> false") {
            TemporalFormatPolicy.hasOffsetOrZone("2026-04-16T10:15:30") shouldBe false
        }

        test("Z-Suffix -> true") {
            TemporalFormatPolicy.hasOffsetOrZone("2026-04-16T10:15:30Z") shouldBe true
        }

        test("+hh:mm am Ende -> true") {
            TemporalFormatPolicy.hasOffsetOrZone("2026-04-16T10:15:30+02:00") shouldBe true
        }

        test("-hh:mm am Ende -> true") {
            TemporalFormatPolicy.hasOffsetOrZone("2026-04-16T10:15:30-05:00") shouldBe true
        }
    }

    // ────────────────────────────────────────────────────────────
    // §4.5 Parser-Vertrag
    // ────────────────────────────────────────────────────────────

    context("§4.5 parseSinceLiteral") {

        test("Offset-Input -> OffsetDateTime (§4.2)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01T10:15:30+01:00")
            result.shouldBeInstanceOf<OffsetDateTime>()
            result shouldBe OffsetDateTime.parse("2026-01-01T10:15:30+01:00")
        }

        test("Z-Input -> OffsetDateTime (§4.2)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01T10:15:30Z")
            result.shouldBeInstanceOf<OffsetDateTime>()
        }

        test("Lokaler DateTime-Input -> LocalDateTime (§4.3)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01T10:15:30")
            result.shouldBeInstanceOf<LocalDateTime>()
            result shouldBe LocalDateTime.parse("2026-01-01T10:15:30")
        }

        test("Date-Input -> LocalDate (§4.3)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01")
            result.shouldBeInstanceOf<LocalDate>()
            result shouldBe LocalDate.parse("2026-01-01")
        }

        test("Integer -> Long") {
            TemporalFormatPolicy.parseSinceLiteral("42") shouldBe 42L
        }

        test("zu grosse Integer -> BigDecimal") {
            TemporalFormatPolicy.parseSinceLiteral("9223372036854775808123") shouldBe
                BigDecimal("9223372036854775808123")
        }

        test("Dezimal -> BigDecimal") {
            TemporalFormatPolicy.parseSinceLiteral("3.14") shouldBe BigDecimal("3.14")
        }

        test("Fallback -> Rohstring") {
            TemporalFormatPolicy.parseSinceLiteral("release-42") shouldBe "release-42"
        }

        // §4.1: nur kanonische erweiterte ISO-Profile sind Teil des Vertrags.
        // Andere ISO-Varianten bleiben Rohstring (kein silent type-promotion).
        test("§4.1 ISO-Basic-Form ohne Trenner -> Rohstring") {
            // `20260116T101530` (Basic Date + Basic Time) ist nicht Teil des
            // unterstuetzten Kontrakts und wird nicht als LocalDateTime
            // typisiert.
            TemporalFormatPolicy.parseSinceLiteral("20260116T101530") shouldBe "20260116T101530"
        }

        test("§4.1 ISO-Wochen-Datum -> Rohstring") {
            TemporalFormatPolicy.parseSinceLiteral("2026-W16-4") shouldBe "2026-W16-4"
        }

        test("§4.1 ISO-Ordinal-Datum -> Rohstring") {
            // `2026-106` koennte von parseLocalDate als ungueltig abgelehnt
            // werden, landet dann aber ebenfalls im Rohstring-Branch.
            TemporalFormatPolicy.parseSinceLiteral("2026-106") shouldBe "2026-106"
        }

        test("§4.1 dialekt-lokale Form `16.04.2026` -> Rohstring") {
            TemporalFormatPolicy.parseSinceLiteral("16.04.2026") shouldBe "16.04.2026"
        }

        // §4.1 Lesepfad-Toleranz: die JDK-ISO-Profile akzeptieren bewusst
        // reduzierte Zeitformen (z.B. ohne Sekunden). Phase E garantiert das
        // nur *lesend* — der Schreibpfad bleibt kanonisch mit Sekunden.
        test("§4.1 Minuten-Praezision ohne Sekunden -> LocalDateTime (Lesepfad)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01T10:15")
            result.shouldBeInstanceOf<LocalDateTime>()
            result shouldBe LocalDateTime.parse("2026-01-01T10:15")
        }

        test("§4.1 Minuten-Praezision mit Offset -> OffsetDateTime (Lesepfad)") {
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-01-01T10:15+02:00")
            result.shouldBeInstanceOf<OffsetDateTime>()
            result shouldBe OffsetDateTime.parse("2026-01-01T10:15+02:00")
        }

        test("§4.4 keine Auto-Zonierung: lokaler Input bleibt LocalDateTime, auch wenn JVM-TZ abweicht") {
            // Der Policy-Vertrag darf keine versteckte Zone-Auflosung haben —
            // der Parser kennt keine ZoneId, punkt.
            val result = TemporalFormatPolicy.parseSinceLiteral("2026-06-15T12:00:00")
            result.shouldBeInstanceOf<LocalDateTime>()
            // kein Offset, keine Zone.
            (result as LocalDateTime) shouldBe LocalDateTime.of(2026, 6, 15, 12, 0, 0)
        }
    }

    // ────────────────────────────────────────────────────────────
    // §4.3 Einzel-Parser
    // ────────────────────────────────────────────────────────────

    context("§4.3 parseLocalDateTime verweigert Offset/Zone-Input") {

        test("Offset-Input -> null (keine stille Abschneidung)") {
            TemporalFormatPolicy.parseLocalDateTime("2026-01-01T10:15:30+02:00").shouldBeNull()
        }

        test("Z-Input -> null") {
            TemporalFormatPolicy.parseLocalDateTime("2026-01-01T10:15:30Z").shouldBeNull()
        }

        test("rein lokaler Input -> LocalDateTime") {
            TemporalFormatPolicy.parseLocalDateTime("2026-01-01T10:15:30") shouldBe
                LocalDateTime.parse("2026-01-01T10:15:30")
        }

        test("Date-only -> null (kein T)") {
            TemporalFormatPolicy.parseLocalDateTime("2026-01-01").shouldBeNull()
        }
    }

    context("§4.3 parseLocalDate verweigert DateTime-Input") {

        test("DateTime-Input -> null") {
            TemporalFormatPolicy.parseLocalDate("2026-01-01T10:15:30").shouldBeNull()
        }

        test("reines Datum -> LocalDate") {
            TemporalFormatPolicy.parseLocalDate("2026-01-01") shouldBe LocalDate.parse("2026-01-01")
        }
    }

    context("§4.2 parseOffsetDateTime") {

        test("Offset-Input -> OffsetDateTime") {
            TemporalFormatPolicy.parseOffsetDateTime("2026-01-01T10:15:30+01:00") shouldBe
                OffsetDateTime.parse("2026-01-01T10:15:30+01:00")
        }

        test("lokaler Input -> null (kein stiller Offset-Default)") {
            TemporalFormatPolicy.parseOffsetDateTime("2026-01-01T10:15:30").shouldBeNull()
        }
    }

    // ────────────────────────────────────────────────────────────
    // §4.4 — Default-Zeitzone ist nur explizit
    // ────────────────────────────────────────────────────────────

    context("§4.4 toZoned — explizite Konvertierung") {

        test("lokalen Wert mit explizit uebergebener Zone zonieren") {
            val local = LocalDateTime.of(2026, 6, 15, 12, 0, 0)
            val zoned = TemporalFormatPolicy.toZoned(local, ZoneId.of("Europe/Berlin"))
            zoned shouldBe ZonedDateTime.of(local, ZoneId.of("Europe/Berlin"))
        }

        test("UTC-Fallback greift nur, wenn der Caller UTC uebergibt") {
            val local = LocalDateTime.of(2026, 6, 15, 12, 0, 0)
            val zoned = TemporalFormatPolicy.toZoned(local, ZoneOffset.UTC)
            zoned.offset shouldBe ZoneOffset.UTC
            zoned.toLocalDateTime() shouldBe local
        }

        test("reproduzierbar: gleiche Eingaben ergeben gleiches Ergebnis") {
            val local = LocalDateTime.of(2026, 6, 15, 12, 0, 0)
            val zone = ZoneId.of("America/New_York")
            TemporalFormatPolicy.toZoned(local, zone) shouldBe
                TemporalFormatPolicy.toZoned(local, zone)
        }
    }

    // ────────────────────────────────────────────────────────────
    // Formatter-Konstanten sind die JDK-ISO-Formatter
    // ────────────────────────────────────────────────────────────

    context("ISO-Formatter-Konstanten") {

        test("ISO_LOCAL_DATE entspricht dem JDK-Standard") {
            TemporalFormatPolicy.ISO_LOCAL_DATE shouldBe
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        }

        test("ISO_LOCAL_TIME entspricht dem JDK-Standard") {
            TemporalFormatPolicy.ISO_LOCAL_TIME shouldBe
                java.time.format.DateTimeFormatter.ISO_LOCAL_TIME
        }

        test("ISO_LOCAL_DATE_TIME entspricht dem JDK-Standard") {
            TemporalFormatPolicy.ISO_LOCAL_DATE_TIME shouldBe
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        }

        test("ISO_OFFSET_DATE_TIME entspricht dem JDK-Standard") {
            TemporalFormatPolicy.ISO_OFFSET_DATE_TIME shouldBe
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        }
    }
})
