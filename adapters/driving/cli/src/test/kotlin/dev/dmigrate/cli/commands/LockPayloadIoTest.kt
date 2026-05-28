package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LockPayloadIoTest : FunSpec({

    // ── escapeJsonString — alle when-Arme ─────────────────────────

    test("render escapes backspace, form-feed, newline, carriage return, tab as JSON escapes") {
        val rendered = LockPayloadIo.render(
            pid = 1L,
            startedAt = "2026-05-28T10:00:00Z",
            instance = "i",
            // Alle fünf direkt benannten control chars + ein expliziter " für Symmetrie.
            version = "v\b\n\r\t\"",
        )
        rendered shouldContain "\\b"
        rendered shouldContain "\\f"
        rendered shouldContain "\\n"
        rendered shouldContain "\\r"
        rendered shouldContain "\\t"
        rendered shouldContain "\\\""
    }

    test("render escapes other sub-0x20 control characters as \\u00xx (else-branch)") {
        // 0x01 (SOH) ist nicht in den benannten when-Armen, fällt in den
        // else-Zweig und wird als  ausgegeben.
        val rendered = LockPayloadIo.render(
            pid = 1L,
            startedAt = "x",
            instance = "i",
            version = "v",
        )
        rendered shouldContain "\\u0001"
        rendered shouldContain "\\u001f"
    }

    test("render keeps printable ASCII unescaped") {
        val rendered = LockPayloadIo.render(
            pid = 1L,
            startedAt = "x",
            instance = "i",
            version = "0.9.7-clean",
        )
        rendered shouldContain "\"version\":\"0.9.7-clean\""
        // Keine fragwürdige Escape-Notation eingeschmuggelt
        rendered shouldNotContain "\\u00"
    }

    // ── readBestEffort — alle drei Rückgabe-Wege ──────────────────

    test("readBestEffort returns trimmed content for a non-empty file") {
        val dir = Files.createTempDirectory("dmigrate-payload-read-")
        try {
            val file = dir.resolve(".lock")
            Files.writeString(file, "  {\"pid\":42}\n", StandardCharsets.UTF_8)
            LockPayloadIo.readBestEffort(file) shouldContain "{\"pid\":42}"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("readBestEffort returns null for an empty file (takeIf isNotEmpty filter)") {
        val dir = Files.createTempDirectory("dmigrate-payload-empty-")
        try {
            val file = dir.resolve(".lock")
            Files.writeString(file, "", StandardCharsets.UTF_8)
            assert(LockPayloadIo.readBestEffort(file) == null) {
                "Empty payload should produce null per .takeIf { it.isNotEmpty() }"
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("readBestEffort returns null when the file does not exist (IOException catch)") {
        val dir = Files.createTempDirectory("dmigrate-payload-missing-")
        try {
            assert(LockPayloadIo.readBestEffort(dir.resolve("nonexistent.lock")) == null) {
                "Missing file should fall through the IOException catch to null"
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── write — Round-Trip durch render ───────────────────────────

    test("write persists a render-shaped payload that read-backs identically") {
        val dir = Files.createTempDirectory("dmigrate-payload-write-")
        try {
            val file = dir.resolve(".lock")
            val payload = LockPayloadIo.render(
                pid = 7L,
                startedAt = "2026-05-28T10:00:00Z",
                instance = "abc-uuid",
                version = "0.9.7",
            )
            LockPayloadIo.write(file, payload)
            Files.readString(file, StandardCharsets.UTF_8) shouldContain "\"pid\":7"
        } finally {
            dir.deleteRecursively()
        }
    }
})
