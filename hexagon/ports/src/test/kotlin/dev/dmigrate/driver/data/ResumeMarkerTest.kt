package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §5.1): Port-Level-Tests
 * fuer [ResumeMarker] + den Default-Pfad der
 * [DataReader.streamTable]-Ueberladung. Treiber-spezifische Paging-
 * Semantik wird in `AbstractJdbcDataReaderTest` und den Dialekt-
 * Integrationstests gefahren; hier geht es nur um das Port-
 * Vertragslevel (init-Regeln + Default-Fallback).
 */
class ResumeMarkerTest : FunSpec({

    test("ResumeMarker can be constructed in fresh-track mode (no position)") {
        val marker = ResumeMarker(
            markerColumn = "updated_at",
            tieBreakerColumns = listOf("id"),
            position = null,
        )
        marker.position shouldBe null
        marker.tieBreakerColumns shouldContainExactly listOf("id")
    }

    test("ResumeMarker.Position carries marker value + tie-breaker values in parallel") {
        val marker = ResumeMarker(
            markerColumn = "updated_at",
            tieBreakerColumns = listOf("tenant", "id"),
            position = ResumeMarker.Position(
                lastMarkerValue = "2026-04-16",
                lastTieBreakerValues = listOf("acme", 42L),
            ),
        )
        marker.position!!.lastMarkerValue shouldBe "2026-04-16"
        marker.position!!.lastTieBreakerValues shouldContainExactly listOf("acme", 42L)
    }

    test("ResumeMarker init rejects position with mismatched tie-breaker size") {
        val ex = shouldThrow<IllegalArgumentException> {
            ResumeMarker(
                markerColumn = "updated_at",
                tieBreakerColumns = listOf("a", "b"),
                position = ResumeMarker.Position(
                    lastMarkerValue = 1,
                    lastTieBreakerValues = listOf(1), // 1 value, 2 cols
                ),
            )
        }
        ex.message!! shouldContain "must have the same size"
    }

    test("ResumeMarker init rejects blank markerColumn") {
        shouldThrow<IllegalArgumentException> {
            ResumeMarker(markerColumn = "", tieBreakerColumns = emptyList())
        }
    }

    test("ResumeMarker init rejects blank tie-breaker entry") {
        shouldThrow<IllegalArgumentException> {
            ResumeMarker(markerColumn = "x", tieBreakerColumns = listOf("id", " "))
        }
    }

    test("ResumeMarker init allows empty tie-breaker columns") {
        val marker = ResumeMarker(
            markerColumn = "seq",
            tieBreakerColumns = emptyList(),
            position = ResumeMarker.Position(
                lastMarkerValue = 10,
                lastTieBreakerValues = emptyList(),
            ),
        )
        marker.tieBreakerColumns.isEmpty() shouldBe true
    }

    // ─── DataReader default overload behavior ───────────────────────

    test("default DataReader.streamTable(resumeMarker = null) delegates to the 4-param overload") {
        val reader = FakeReader()
        reader.streamTable(
            pool = DummyPool,
            table = "items",
            filter = null,
            chunkSize = 100,
            resumeMarker = null,
        )
        reader.legacyCalls shouldBe 1
    }

    test("default DataReader.streamTable(resumeMarker != null) throws UnsupportedOperationException") {
        val reader = FakeReader()
        val marker = ResumeMarker(
            markerColumn = "qty",
            tieBreakerColumns = emptyList(),
            position = ResumeMarker.Position(
                lastMarkerValue = 0,
                lastTieBreakerValues = emptyList(),
            ),
        )
        val ex = shouldThrow<UnsupportedOperationException> {
            reader.streamTable(
                pool = DummyPool,
                table = "items",
                filter = null,
                chunkSize = 100,
                resumeMarker = marker,
            )
        }
        ex.message!! shouldContain "mid-table resume"
    }
})

private object DummyPool : ConnectionPool {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    override fun borrow(): java.sql.Connection =
        throw UnsupportedOperationException("test fake")
    override fun activeConnections(): Int = 0
    override fun close() {}
}

/**
 * Minimal DataReader fake that only implements the 4-param overload and
 * otherwise relies on the port's default 5-param implementation — which
 * is exactly the contract Phase C.2 needs to verify.
 */
private class FakeReader : DataReader {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    var legacyCalls: Int = 0
        private set

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence {
        legacyCalls += 1
        return FakeChunkSequence()
    }
}

private class FakeChunkSequence : ChunkSequence {
    private var used = false
    override fun iterator(): Iterator<DataChunk> {
        check(!used) { "single-use" }
        used = true
        val chunk = DataChunk(
            table = "items",
            columns = listOf(ColumnDescriptor(name = "id", nullable = false, sqlTypeName = "INTEGER")),
            rows = emptyList(),
            chunkIndex = 0L,
        )
        return listOf(chunk).iterator()
    }

    override fun close() {}
}
