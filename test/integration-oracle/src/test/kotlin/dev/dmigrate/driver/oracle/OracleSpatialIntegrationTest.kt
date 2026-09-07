package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.cli.commands.TransferExecutionContext
import dev.dmigrate.driver.data.ImportOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Oracle Spatial gegen ein ECHTES Oracle.
 *
 * **Eigenes Image, wie bei Oracle Text.** `23-slim-faststart` bringt Spatial
 * nicht mit -- `ALL_TYPES` kennt `SDO_GEOMETRY` dort nicht,
 * `USER_SDO_GEOM_METADATA` existiert nicht. Die Vollvariante bringt es mit,
 * ist aber deutlich groesser; sie nur hier zu verwenden haelt die Laufzeit
 * der uebrigen Oracle-Tests unveraendert.
 */
class OracleSpatialIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-faststart")
        .withStartupTimeout(Duration.ofMinutes(8))

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(OracleDriver())
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(vararg sqls: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }

    fun <T> queryOne(sql: String, read: (java.sql.ResultSet) -> T): T? =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs -> if (rs.next()) read(rs) else null }
            }
        }

    fun dropTable(name: String) =
        exec("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"$name\" PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;")

    fun schemaFor(table: String) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            table to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "geom" to ColumnDefinition(NeutralType.Geometry(GeometryType.of("point"), srid = 4326)),
                ),
                primaryKey = listOf("id"),
                indices = listOf(
                    IndexDefinition(
                        name = "ix_${table}_geom",
                        columns = listOf(IndexColumn("geom")),
                        type = IndexType.SPATIAL,
                    ),
                ),
            ),
        ),
    )

    /**
     * Ohne das native Profil blockt der Generator jede Tabelle mit einer
     * Geometriespalte (E052) -- der Default von [DdlGenerationOptions] ist
     * [SpatialProfile.NONE], nicht der Dialekt-Default der CLI.
     */
    val spatialOptions = DdlGenerationOptions(spatialProfile = SpatialProfile.NATIVE)

    /**
     * Was ein Runner ausfuehrt: Kommentarzeilen weg, und das abschliessende
     * Semikolon nur dort, wo es der Anweisungstrenner ist.
     *
     * Ein PL/SQL-Block endet selbst auf `END;` -- ihm das Semikolon zu
     * nehmen macht ihn unvollstaendig (PLS-00103). Genau diese Anweisungen
     * tragen einen eigenen [DdlStatement.scriptTerminator].
     */
    fun executable(statements: List<DdlStatement>): List<String> = statements
        .map { stmt ->
            val body = stmt.sql.lines()
                .filterNot { line -> line.trimStart().startsWith("--") }
                .joinToString("\n").trim()
            if (stmt.scriptTerminator == null) body.removeSuffix(";") else body
        }
        .filter { it.isNotBlank() }

    test("the spatial index lands in POST_DATA, never between table and data") {
        val ddl = OracleDdlGenerator().generate(schemaFor("places"), spatialOptions)

        val preData = ddl.statementsForPhase(DdlPhase.PRE_DATA).map { it.sql }
        val postData = ddl.statementsForPhase(DdlPhase.POST_DATA).map { it.sql }

        withClue("die Tabelle gehoert vor die Daten") {
            preData.single { it.contains("CREATE TABLE") } shouldContain "\"geom\" SDO_GEOMETRY"
        }
        withClue("pre-data darf keinen Spatial-Index tragen: er scheitert auf der leeren Tabelle") {
            preData.none { it.contains("SPATIAL_INDEX") } shouldBe true
        }
        postData.single { it.contains("SPATIAL_INDEX_V2") } shouldContain "EXECUTE IMMEDIATE"

        // Der Block endet auf `END;`; ohne den `/`-Trenner kann ein
        // Datei-Konsument sein Ende nicht finden.
        ddl.statements.single { it.sql.contains("SPATIAL_INDEX_V2") }.scriptTerminator shouldBe "/"
    }

    test("pre-data, then rows, then post-data yields a VALID index that answers SDO_FILTER") {
        dropTable("places")
        val ddl = OracleDdlGenerator().generate(schemaFor("places"), spatialOptions)

        executable(ddl.statementsForPhase(DdlPhase.PRE_DATA)).forEach { sql ->
            withClue("pre-data statement failed:\n$sql") { exec(sql) }
        }
        exec(
            "INSERT INTO \"places\" VALUES (1, SDO_GEOMETRY(2001,4326,SDO_POINT_TYPE(9.9,53.5,NULL),NULL,NULL))",
            "COMMIT",
        )
        executable(ddl.statementsForPhase(DdlPhase.POST_DATA)).forEach { sql ->
            withClue("post-data statement failed:\n$sql") { exec(sql) }
        }

        queryOne("SELECT domidx_opstatus FROM user_indexes WHERE index_name = 'ix_places_geom'") {
            it.getString(1)
        } shouldBe "VALID"

        val hits = queryOne(
            """
            SELECT COUNT(*) FROM "places" t
            WHERE SDO_FILTER(t."geom", SDO_GEOMETRY(2003, 4326, NULL,
                SDO_ELEM_INFO_ARRAY(1, 1003, 3), SDO_ORDINATE_ARRAY(0, 0, 20, 60))) = 'TRUE'
            """.trimIndent(),
        ) { it.getInt(1) }
        withClue("der Index findet die Zeile nicht") { hits shouldBe 1 }
    }

    /**
     * Der Kern der Slice-Entscheidung. Auf einer leeren Tabelle kann Oracle
     * die SRID nicht bestimmen und der Index scheitert -- ein blankes
     * `CREATE INDEX` liesse ihn dann als `FAILED` stehen, und die Tabelle
     * waere fuer JEDES `INSERT` gesperrt (ORA-29861).
     */
    test("a spatial index that fails leaves no remnant and the table stays writable") {
        dropTable("empty_places")
        val ddl = OracleDdlGenerator().generate(schemaFor("empty_places"), spatialOptions)

        executable(ddl.statementsForPhase(DdlPhase.PRE_DATA)).forEach { exec(it) }

        // Post-data OHNE vorherige Daten: der Index kann nicht entstehen.
        val failure = runCatching {
            executable(ddl.statementsForPhase(DdlPhase.POST_DATA)).forEach { exec(it) }
        }.exceptionOrNull()
        withClue("Oracle nahm den Index auf einer leeren Tabelle an?") {
            failure shouldNotBe null
            (failure?.message ?: "") shouldContain "ORA-13199"
        }

        withClue("ein FAILED-Index blieb stehen und sperrt die Tabelle") {
            queryOne("SELECT COUNT(*) FROM user_indexes WHERE index_name = 'ix_empty_places_geom'") {
                it.getInt(1)
            } shouldBe 0
        }
        // Und der eigentliche Beweis: die Tabelle nimmt weiterhin Zeilen an.
        exec(
            "INSERT INTO \"empty_places\" VALUES (1, SDO_GEOMETRY(2001,4326,SDO_POINT_TYPE(1,2,NULL),NULL,NULL))",
            "COMMIT",
        )
        queryOne("SELECT COUNT(*) FROM \"empty_places\"") { it.getInt(1) } shouldBe 1
    }

    test("a geometry column and its spatial index read back as geometry and SPATIAL") {
        dropTable("rt_places")
        exec(
            """CREATE TABLE "rt_places" ("id" NUMBER(9) PRIMARY KEY, "geom" SDO_GEOMETRY)""",
            """INSERT INTO "rt_places" VALUES (1, SDO_GEOMETRY(2001,4326,SDO_POINT_TYPE(9.9,53.5,NULL),NULL,NULL))""",
            "COMMIT",
            """CREATE INDEX "ix_rt_geom" ON "rt_places" ("geom") INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2""",
        )

        val table = OracleSchemaReader().read(pool).schema.tables.getValue("rt_places")

        // Faellt SDO_GEOMETRY auf Text, traegt das Modell nichts
        // Raeumliches mehr, und keiner der uebrigen Pfade sieht die
        // Geometrie noch.
        table.columns.getValue("geom").type.shouldBeGeometry()
        val index = table.indices.single { it.name == "ix_rt_geom" }
        index.type shouldBe IndexType.SPATIAL
        index.columnNames shouldBe listOf("geom")
        // Kein R357: der raeumliche Domain-Index ist jetzt abgebildet, nicht
        // als "fremde Indexart" uebersprungen.
        OracleSchemaReader().read(pool).notes.filter { it.code == "R357" }.shouldBeEmpty()
    }

    /**
     * Der Post-Compare vergleicht Soll und Ist ueber denselben
     * verlustbehafteten Kanonisierer. Bei einer Geometrie ist das die
     * schaerfste Probe: Oracle traegt weder Subtyp noch SRID an der Spalte,
     * die Projektion muss also **beide** Seiten gleich falten. Taete sie es
     * nicht, meldete jeder Lauf Drift an einer Spalte, die sich gar nicht
     * aendern laesst.
     */
    test("a geometry column round-trips without drift through the Oracle projection") {
        dropTable("fp_places")
        val desired = schemaFor("fp_places").let { s ->
            // Ohne Index: hier zaehlt allein die Spalten-Projektion.
            s.copy(tables = mapOf("fp_places" to s.tables.getValue("fp_places").copy(indices = emptyList())))
        }
        executable(OracleDdlGenerator().generate(desired, spatialOptions).statements).forEach { exec(it) }

        val canonicalize: (NeutralType) -> NeutralType = OracleDriver().typeCanonicalizer()::canonicalize
        val readBack = OracleSchemaReader().read(pool).schema

        // Nur die Sonde vergleichen: die Datenbank traegt die Tabellen der
        // uebrigen Tests derselben Spezifikation.
        val actual = SchemaDefinition(
            name = desired.name,
            version = desired.version,
            tables = readBack.tables.filterKeys { it == "fp_places" },
        )
        MigrationFingerprint.project(actual, canonicalize) shouldBe
            MigrationFingerprint.project(desired, canonicalize)
    }

    /**
     * Der Datenpfad erkennt eine Geometriespalte am JDBC-Typnamen. Welche
     * Form der Treiber dafuer meldet, ist hier festgehalten statt angenommen:
     * `SDO_GEOMETRY` ist ein Objekttyp, und ein Treiber darf ihn mit seinem
     * Eigner davor melden.
     */
    test("the JDBC type name of a geometry column is recognised as geometry") {
        dropTable("typename_probe")
        exec("""CREATE TABLE "typename_probe" ("geom" SDO_GEOMETRY)""")

        val reported = pool.borrow().asJdbc().use { conn ->
            conn.prepareStatement("""SELECT * FROM "typename_probe" WHERE 1 = 0""").use { ps ->
                ps.executeQuery().use { rs -> rs.metaData.getColumnTypeName(1) }
            }
        }
        // Der Treiber qualifiziert den Objekttyp mit seinem Eigner; der
        // Katalog fuehrt denselben Typ ohne Praefix. Der Abgleich im Treiber
        // vergleicht deshalb das letzte Namenssegment.
        reported shouldBe "MDSYS.SDO_GEOMETRY"
    }

    test("geometry values survive the data path as WKB, and a NULL geometry stays NULL") {
        dropTable("wkb_src")
        dropTable("wkb_dst")
        exec(
            """CREATE TABLE "wkb_src" ("id" NUMBER(9) PRIMARY KEY, "geom" SDO_GEOMETRY)""",
            """CREATE TABLE "wkb_dst" ("id" NUMBER(9) PRIMARY KEY, "geom" SDO_GEOMETRY)""",
            """INSERT INTO "wkb_src" VALUES (1, SDO_GEOMETRY(2001,4326,SDO_POINT_TYPE(9.9,53.5,NULL),NULL,NULL))""",
            """INSERT INTO "wkb_src" VALUES (2, SDO_GEOMETRY(2003,4326,NULL,""" +
                "SDO_ELEM_INFO_ARRAY(1,1003,1), SDO_ORDINATE_ARRAY(0,0, 4,0, 4,3, 0,3, 0,0)))",
            """INSERT INTO "wkb_src" VALUES (3, NULL)""",
            "COMMIT",
        )

        OracleDataReader().streamTable(pool, "wkb_src", null, 100).use { seq ->
            // Der Reverse traegt die Geometrie jetzt als Geometrie, nicht als Text.
            seq.schema.columns.single { it.name == "geom" }.neutralType
                .shouldBeInstanceOf<NeutralType.Geometry>()

            val chunks = seq.toList()
            val rows = chunks.flatMap { it.rows }
            rows.size shouldBe 3
            // Der Lesepfad liefert WKB-Bytes, keinen oracle.sql.STRUCT.
            val at = chunks.first().columns.indexOfFirst { it.name == "geom" }
            rows.filter { it[at] != null }.forEach { row ->
                withClue("Geometrie kam nicht als WKB an: ${row[at]}") {
                    (row[at] is ByteArray) shouldBe true
                }
            }

            OracleDataWriter().openTable(pool, "wkb_dst", ImportOptions()).use { session ->
                chunks.forEach { chunk -> session.write(chunk.copy(table = "wkb_dst")) }
                session.commitChunk()
                session.finishTable()
            }
        }

        queryOne("SELECT COUNT(*) FROM \"wkb_dst\"") { it.getInt(1) } shouldBe 3
        // Eine NULL-Geometrie darf nicht als nicht-NULL Geistergeometrie
        // ankommen -- der SDO_GEOMETRY-Typkonstruktor taete genau das.
        queryOne("SELECT COUNT(*) FROM \"wkb_dst\" WHERE \"geom\" IS NULL") { it.getInt(1) } shouldBe 1
        queryOne(
            """SELECT SDO_UTIL.TO_WKTGEOMETRY(t."geom") FROM "wkb_dst" t WHERE t."id" = 2""",
        ) { it.getString(1) } shouldContain "POLYGON"
    }

    /**
     * Die Kette, die kein Unit-Test schliessen kann: Quellschema lesen →
     * SRID mitgeben → schreiben → in der Datenbank nachsehen.
     *
     * Die **Quelle** traegt eine Zeile in `USER_SDO_GEOM_METADATA` (nur mit
     * grossgeschriebenem, unquotiert angelegtem Namen moeglich), das **Ziel**
     * nicht — es ist quotiert kleingeschrieben, wie d-migrate Tabellen
     * anlegt. Ohne die Angabe der Quelle kaemen die Werte dort ohne
     * Koordinatensystem an.
     */
    test("data transfer carries the source SRID into a target that cannot hold one") {
        dropTable("tgt_places")
        dropTable("tgt_bare")
        exec("BEGIN EXECUTE IMMEDIATE 'DROP TABLE SRC_PLACES PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;")
        exec(
            "DELETE FROM user_sdo_geom_metadata WHERE table_name = 'SRC_PLACES'",
            "COMMIT",
            "CREATE TABLE SRC_PLACES (ID NUMBER(9) PRIMARY KEY, GEOM SDO_GEOMETRY)",
            """
            INSERT INTO user_sdo_geom_metadata (table_name, column_name, diminfo, srid) VALUES (
                'SRC_PLACES', 'GEOM',
                SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -180, 180, 0.005), SDO_DIM_ELEMENT('Y', -90, 90, 0.005)),
                4326)
            """.trimIndent(),
            "COMMIT",
            "INSERT INTO SRC_PLACES VALUES (1, SDO_GEOMETRY(2001, 4326, SDO_POINT_TYPE(9.9, 53.5, NULL), NULL, NULL))",
            "COMMIT",
            """CREATE TABLE "tgt_places" ("ID" NUMBER(9) PRIMARY KEY, "GEOM" SDO_GEOMETRY)""",
        )

        val sourceSchema = OracleSchemaReader().read(pool).schema
        withClue("der Reverse liest die SRID der Quelle nicht — dann kann sie auch nichts weitergeben") {
            (sourceSchema.tables.getValue("SRC_PLACES").columns.getValue("GEOM").type as NeutralType.Geometry)
                .srid shouldBe 4326
        }

        // Genau die Optionen, die der Runner baut -- aus derselben Funktion.
        val srids = TransferExecutionContext.geometrySridsOf(sourceSchema)
        val chunks = OracleDataReader().streamTable(pool, "SRC_PLACES", null, 100).use { it.toList() }

        fun loadInto(target: String, options: ImportOptions) {
            OracleDataWriter().openTable(pool, target, options).use { session ->
                chunks.forEach { session.write(it.copy(table = target)) }
                session.commitChunk()
                session.finishTable()
            }
        }

        loadInto("tgt_places", ImportOptions(sourceGeometrySrids = srids.getValue("SRC_PLACES")))

        // Gegenprobe: ohne die Angabe der Quelle kommt der Wert ohne
        // Koordinatensystem an -- das war der Zustand vor diesem Slice.
        exec("""CREATE TABLE "tgt_bare" ("ID" NUMBER(9) PRIMARY KEY, "GEOM" SDO_GEOMETRY)""")
        loadInto("tgt_bare", ImportOptions())
        queryOne("""SELECT NVL(TO_CHAR(t."GEOM".SDO_SRID), 'keine') FROM "tgt_bare" t""") {
            it.getString(1)
        } shouldBe "keine"

        queryOne("""SELECT t."GEOM".SDO_SRID FROM "tgt_places" t""") { it.getInt(1) } shouldBe 4326
    }
})

private fun NeutralType.shouldBeGeometry() {
    withClue("erwartet wurde eine Geometrie, gelesen wurde $this") {
        (this is NeutralType.Geometry) shouldBe true
    }
}
