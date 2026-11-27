package dev.dmigrate.mcp.cursor

import dev.dmigrate.mcp.resources.ResourcesListCursor
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

private val ISSUED: Instant = Instant.ofEpochSecond(1_795_790_433, 596_704_330)
private val KEY = CursorKey("kid-1", ByteArray(32) { 7 })

private fun codec() = McpCursorCodec(CursorKeyring(signing = KEY), Clock.fixed(ISSUED, ZoneOffset.UTC))

private fun decodedPayload(cursor: String): String =
    String(Base64.getUrlDecoder().decode(cursor.substringBefore('.')), Charsets.UTF_8)

/**
 * Das Wire-Format beider Cursor, Zeichen fuer Zeichen. Es haengt nicht an Reflexion: Im Native-Image lieferte
 * Gson fuer die Cursor-Klassen `{}`, ohne Fehler (Issue #88). Wer das Format aendert, macht umlaufende Cursor
 * ungueltig.
 */
class CursorWireFormatTest : FunSpec({

    val payload = McpCursorPayload(
        cursorType = "schema_list",
        version = McpCursorCodec.SUPPORTED_VERSION,
        kid = KEY.kid,
        tenantId = TenantId("default"),
        family = "schemas",
        filters = mapOf("jobId" to "a<b"),
        pageSize = 1,
        sort = null,
        resumeToken = "1",
        issuedAt = ISSUED,
        expiresAt = ISSUED.plusSeconds(900),
    )

    test("versiegelter Cursor: jedes Feld, null-Felder eingeschlossen, Instant als seconds und nanos") {
        decodedPayload(codec().encode(payload)) shouldBe
            """{"cursorType":"schema_list","version":1,"kid":"kid-1","tenantId":"default","family":"schemas",""" +
            """"filters":{"jobId":"a<b"},"pageSize":1,"sort":null,"resumeToken":"1",""" +
            """"issuedAt":{"seconds":1795790433,"nanos":596704330},""" +
            """"expiresAt":{"seconds":1795791333,"nanos":596704330}}"""
    }

    test("versiegelter Cursor uebersteht den Rundlauf mit allen Feldern") {
        val binding = CursorBinding(
            cursorType = payload.cursorType,
            tenantId = payload.tenantId,
            family = payload.family,
            filters = payload.filters,
            pageSize = payload.pageSize,
            sort = payload.sort,
        )

        codec().decode(codec().encode(payload), binding) shouldBe McpCursorDecodeResult.Valid(payload)
    }

    test("resources/list-Cursor: kind und innerToken, ein fehlender innerToken entfaellt") {
        fun body(cursor: ResourcesListCursor) =
            String(Base64.getUrlDecoder().decode(cursor.encode()), Charsets.UTF_8)

        body(ResourcesListCursor(ResourceKind.SCHEMAS, "50")) shouldBe """{"kind":"SCHEMAS","innerToken":"50"}"""
        body(ResourcesListCursor(ResourceKind.JOBS, null)) shouldBe """{"kind":"JOBS"}"""
        ResourcesListCursor.decode(ResourcesListCursor(ResourceKind.DIFFS, null).encode()) shouldBe
            ResourcesListCursor(ResourceKind.DIFFS, null)
    }
})

/** Ein gueltig kodierter, aber unbrauchbarer Inhalt ist eine Ablehnung, keine Ausnahme. */
class CursorPayloadRejectionTest : FunSpec({

    val binding = CursorBinding("schema_list", TenantId("default"), "schemas", pageSize = 1)

    listOf(
        "kein JSON" to "nope",
        "kein Objekt" to "[1]",
        "leeres Objekt" to "{}",
        "Zahl statt Text" to """{"cursorType":{"a":1},"version":1}""",
        "Text statt Zahl" to """{"cursorType":"schema_list","version":"x"}""",
    ).forEach { (name, body) ->
        test("versiegelter Cursor mit $name im Payload ist ungueltig") {
            val cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray()) + ".c2ln"

            codec().decode(cursor, binding) shouldBe McpCursorDecodeResult.Invalid("payload json malformed")
        }
    }
})
