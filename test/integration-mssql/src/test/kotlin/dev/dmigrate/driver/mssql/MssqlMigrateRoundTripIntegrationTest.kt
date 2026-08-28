package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

/**
 * Round-Trip-Beleg fuer `schema migrate` gegen echtes SQL Server — das
 * MSSQL-Gegenstueck zu den Smokes der drei anderen Dialekte.
 *
 * Gefahren werden die ECHTEN Runner, nicht der Renderer allein:
 *
 * 1. Ausgangsschema in der Datenbank einrichten,
 * 2. `schema migrate --execute --generate-rollback` (der Post-Compare des
 *    Runners muss selbst durchgehen → Exit 0, Artefakt geschrieben),
 * 3. **unabhaengig** zurueckliesen und den Inhalts-Fingerprint gegen das
 *    Soll-Schema pruefen — die Gegenprobe zum Post-Compare des Runners,
 * 4. `schema rollback --execute --allow-destructive` mit dem Artefakt aus 2,
 * 5. unabhaengig zurueckliesen und gegen das Ausgangsschema pruefen.
 *
 * Die Aenderung ist bewusst eine, an der mehrere T-SQL-Eigenheiten haengen:
 * eine hinzugefuegte Spalte MIT Default. Der Default ist in SQL Server ein
 * eigenes benanntes Objekt, und der Rollback muss ihn wieder loesen, bevor
 * er die Spalte entfernen kann.
 */
class MssqlMigrateRoundTripIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_roundtrip") }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_roundtrip",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
        pool = HikariConnectionPoolFactory.create(config)
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("AddColumn round-trip leaves the database exactly as it started") {
        val tmp = createTempDirectory("mssql-roundtrip")
        try {
            execDdl(
                pool,
                "CREATE TABLE round_trip (id BIGINT NOT NULL CONSTRAINT pk_round_trip PRIMARY KEY, " +
                    "name NVARCHAR(100) NOT NULL)",
            )

            val original = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            val desired = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            // Vorbedingung: die Datenbank IST das Ausgangsschema.
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                // Wie die CLI: der zieldialekt-bewusste Vergleich unterdrueckt
                // Unterschiede, die der Reverse gar nicht ausdruecken kann —
                // etwa das PK-implizite `required`, das SQL Server (wie MySQL)
                // an der Spalte NICHT meldet. Ohne diese Naht plante der Lauf
                // eine Nullability-Aenderung auf dem Schluessel, samt Loesen
                // und Neuanlegen des Primaerschluessels.
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            migrateExit shouldBe 0
            Files.exists(rollbackPath) shouldBe true
            val reportText = Files.readString(reportPath)
            reportText shouldContain "status=ok"
            reportText shouldContain "executionError=null"

            // Gegenprobe zum Post-Compare des Runners.
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(desired)

            val rollbackExit = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            rollbackExit shouldBe 0

            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS round_trip")
            tmp.toFile().deleteRecursively()
        }
    }

    test("a reshaped primary key and a changed UNIQUE are dropped before they are added") {
        val tmp = createTempDirectory("mssql-replace-order")
        try {
            execDdl(
                pool,
                // Beide Seiten mehrspaltig: einspaltige UNIQUE-Constraints hebt der
                // Reverse auf `column.unique`, daraus wuerden zwei verschiedene
                // Objekte statt eines geaenderten Paares.
                "CREATE TABLE replace_order (id BIGINT NOT NULL, tenant BIGINT NOT NULL, code NVARCHAR(50), " +
                    "CONSTRAINT pk_replace_order PRIMARY KEY (id), " +
                    "CONSTRAINT uq_code UNIQUE (code, tenant))",
            )
            val desired = SchemaDefinition(
                name = "replace-order", version = "1",
                tables = mapOf(
                    "replace_order" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "tenant" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "code" to ColumnDefinition(NeutralType.Text(maxLength = 50)),
                        ),
                        primaryKey = listOf("id", "tenant"),
                        constraints = listOf(ConstraintDefinition(
                            name = "uq_code", type = ConstraintType.UNIQUE, columns = listOf("code", "id"),
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            // Exit 5 traegt beide Ausgaenge der falschen Reihenfolge: der Server
            // lehnt ab (Msg 1779 / 2714), oder der Post-Compare meldet Drift.
            withClue(
                "ausgefuehrt:\n" + executed.joinToString("\n") + "\nmeldungen:\n" + errors.joinToString("\n"),
            ) { exit shouldBe 0 }

            val dropPk = executed.indexOfFirst { it.contains("DROP CONSTRAINT") && it.contains("'PK'") }
            val addPk = executed.indexOfFirst { it.contains("PRIMARY KEY") && it.contains("ADD CONSTRAINT") }
            val dropUq = executed.indexOfFirst { it.contains("DROP CONSTRAINT") && it.contains("uq_code") }
            val addUq = executed.indexOfFirst { it.contains("ADD CONSTRAINT") && it.contains("uq_code") }
            withClue("ausgefuehrt:\n" + executed.joinToString("\n")) {
                (dropPk >= 0 && addPk > dropPk) shouldBe true
                (dropUq >= 0 && addUq > dropUq) shouldBe true
            }
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS replace_order")
            tmp.toFile().deleteRecursively()
        }
    }

    test("switching the storage frees the primary key from inbound foreign keys first") {
        // Ein Fremdschluessel, der auf den Primaerschluessel zeigt, haelt ihn fest:
        // `DROP CONSTRAINT` scheitert mit Msg 3725. Ohne den Tanz um ihn herum
        // endet der Lauf mit Exit 5 statt 0 -- und die Beziehung muss danach
        // wieder stehen, sonst verschwindet sie still.
        val tmp = createTempDirectory("mssql-storage-fk")
        try {
            execDdl(
                pool,
                "CREATE TABLE parents (id BIGINT NOT NULL CONSTRAINT pk_parents PRIMARY KEY, " +
                    "label NVARCHAR(50) NOT NULL)",
                "CREATE INDEX ix_parents_label ON parents (label)",
                "CREATE TABLE children (id BIGINT NOT NULL CONSTRAINT pk_children PRIMARY KEY, " +
                    "parent_id BIGINT NOT NULL CONSTRAINT fk_children_parent REFERENCES parents(id))",
            )

            fun schema(clustered: Boolean) = SchemaDefinition(
                name = "storage-fk", version = if (clustered) "1" else "0",
                tables = mapOf(
                    "parents" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "label" to ColumnDefinition(NeutralType.Text(maxLength = 50), required = true),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(IndexDefinition(
                            name = "ix_parents_label",
                            columns = listOf(IndexColumn("label")),
                            clustered = clustered,
                        )),
                    ),
                    "children" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "parent_id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                        ),
                        primaryKey = listOf("id"),
                        // Benannter Constraint statt `references` an der Spalte: die
                        // Umbenennung eines spaltenlevel Fremdschluessels ist eine
                        // eigene, dokumentierte Sache und wuerde hier nur den Beweis
                        // verdecken.
                        constraints = listOf(ConstraintDefinition(
                            name = "fk_children_parent",
                            type = ConstraintType.FOREIGN_KEY,
                            columns = listOf("parent_id"),
                            // Mit den Aktionen, die der Katalog meldet: ohne sie sieht
                            // der Vergleich ein geaendertes Paar und der Plan tauscht
                            // den Fremdschluessel zusaetzlich aus -- eine Churn, die
                            // den Beweis verdeckt.
                            references = ConstraintReferenceDefinition(
                                table = "parents", columns = listOf("id"),
                                onDelete = ReferentialAction.NO_ACTION,
                                onUpdate = ReferentialAction.NO_ACTION,
                            ),
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(
                        reference = "desired", schema = schema(clustered = true), validation = ValidationResult(),
                    )
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(
                "ausgefuehrt:\n" + executed.joinToString("\n") + "\nmeldungen:\n" + errors.joinToString("\n"),
            ) { exit shouldBe 0 }

            indexStorageForm(pool, "parents", "ix_parents_label") shouldBe "CLUSTERED"
            primaryKeyStorageForm(pool, "parents") shouldBe "NONCLUSTERED"
            // Und die Beziehung steht wieder — nicht abgeraeumt und vergessen.
            foreignKeyExists(pool, "children", "fk_children_parent") shouldBe true
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS children", "DROP TABLE IF EXISTS parents")
            tmp.toFile().deleteRecursively()
        }
    }

    test("renaming the clustered index releases the storage before taking it over") {
        // Ein Namenswechsel erscheint als Entfernen + Hinzufuegen mit
        // verschiedenen Objektnamen. Der neue Name sortiert hier VOR dem alten —
        // ohne Ordnungskante liefe `CREATE CLUSTERED INDEX` gegen Msg 1902,
        // waehrend der alte die Ablage noch haelt.
        val tmp = createTempDirectory("mssql-storage-rename")
        try {
            execDdl(
                pool,
                "CREATE TABLE renamed_storage (id BIGINT NOT NULL " +
                    "CONSTRAINT pk_renamed_storage PRIMARY KEY NONCLUSTERED, label NVARCHAR(50) NOT NULL)",
                "CREATE CLUSTERED INDEX ix_z_storage ON renamed_storage (label)",
            )
            indexStorageForm(pool, "renamed_storage", "ix_z_storage") shouldBe "CLUSTERED"

            val desired = SchemaDefinition(
                name = "storage-rename", version = "1",
                tables = mapOf(
                    "renamed_storage" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "label" to ColumnDefinition(NeutralType.Text(maxLength = 50), required = true),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(IndexDefinition(
                            name = "ix_a_storage",
                            columns = listOf(IndexColumn("label")),
                            clustered = true,
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(
                "ausgefuehrt:\n" + executed.joinToString("\n") + "\nmeldungen:\n" + errors.joinToString("\n"),
            ) { exit shouldBe 0 }

            val dropAt = executed.indexOfFirst { it.contains("DROP INDEX") && it.contains("ix_z_storage") }
            val createAt = executed.indexOfFirst { it.contains("CREATE CLUSTERED INDEX") }
            withClue("ausgefuehrt:\n" + executed.joinToString("\n")) {
                (dropAt >= 0 && createAt > dropAt) shouldBe true
            }
            indexStorageForm(pool, "renamed_storage", "ix_a_storage") shouldBe "CLUSTERED"
            indexStorageForm(pool, "renamed_storage", "ix_z_storage").shouldBeNull()
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS renamed_storage")
            tmp.toFile().deleteRecursively()
        }
    }

    test("switching the table's storage round-trips against a real server") {
        val tmp = createTempDirectory("mssql-clustered")
        try {
            // Ausgangslage wie ohne Zutun: der Primaerschluessel haelt die
            // Ablage, der Index daneben ist nonclustered.
            execDdl(
                pool,
                "CREATE TABLE storage_rt (id BIGINT NOT NULL CONSTRAINT pk_storage_rt PRIMARY KEY, " +
                    "placed_on DATE NOT NULL)",
                "CREATE INDEX ix_storage_rt_placed ON storage_rt (placed_on)",
            )
            indexStorageForm(pool, "storage_rt", "ix_storage_rt_placed") shouldBe "NONCLUSTERED"
            primaryKeyStorageForm(pool, "storage_rt") shouldBe "CLUSTERED"

            fun schemaWith(clustered: Boolean, version: String) = SchemaDefinition(
                name = "storage-rt", version = version,
                tables = mapOf(
                    "storage_rt" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "placed_on" to ColumnDefinition(NeutralType.Date, required = true),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(
                            IndexDefinition(
                                name = "ix_storage_rt_placed",
                                columns = listOf(IndexColumn("placed_on")),
                                clustered = clustered,
                            ),
                        ),
                    ),
                ),
            )
            val original = schemaWith(clustered = false, version = "0")
            val desired = schemaWith(clustered = true, version = "1")

            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")
            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            // Haette der Renderer die Reihenfolge verfehlt, waere der Lauf hier
            // schon an Msg 1902 gescheitert statt an einer Zusicherung.
            withClue(
                "migrate meldete $migrateExit\nausgefuehrt:\n" + executed.joinToString("\n") +
                    "\nmeldungen:\n" + errors.joinToString("\n"),
            ) { migrateExit shouldBe 0 }
            Files.readString(reportPath) shouldContain "executionError=null"

            // Der eigentliche Nachweis: die Ablage ist gewandert. Im Modell ist
            // davon nur die eine Haelfte sichtbar.
            indexStorageForm(pool, "storage_rt", "ix_storage_rt_placed") shouldBe "CLUSTERED"
            primaryKeyStorageForm(pool, "storage_rt") shouldBe "NONCLUSTERED"
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(desired)

            val rollbackExit = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            rollbackExit shouldBe 0

            // Und zurueck — auch das ist im Fingerabdruck nur halb zu sehen.
            indexStorageForm(pool, "storage_rt", "ix_storage_rt_placed") shouldBe "NONCLUSTERED"
            primaryKeyStorageForm(pool, "storage_rt") shouldBe "CLUSTERED"
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS storage_rt")
            tmp.toFile().deleteRecursively()
        }
    }
})

private fun noRenderer(): DiffDdlGenerator = error("test wires only the MSSQL renderer")

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }
}

private fun readSchema(pool: ConnectionPool): SchemaDefinition = MssqlSchemaReader().read(pool).schema

/**
 * Die Ablageform eines Index direkt aus dem Katalog -- `CLUSTERED` oder
 * `NONCLUSTERED`.
 *
 * Der Fingerabdruck taugt fuer diese Frage nicht: der Reverse liest die Indizes
 * mit `is_primary_key = 0`, ueber die Ablageform des Primaerschluessels sagt das
 * neutrale Modell also nichts. Eine Tabelle mit clustered Schluessel und eine
 * mit nonclustered Schluessel plus Heap sehen darin gleich aus. Die Reihenfolge,
 * die T-SQL erzwingt, laesst sich deshalb nur hier nachweisen.
 */
private fun indexStorageForm(pool: ConnectionPool, table: String, index: String): String? =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT i.type_desc FROM sys.indexes i " +
                "WHERE i.object_id = OBJECT_ID(?) AND i.name = ?",
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.setString(2, index)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

/** Ob ein Fremdschluessel dieses Namens an der Tabelle haengt. */
private fun foreignKeyExists(pool: ConnectionPool, table: String, name: String): Boolean =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT 1 FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID(?) AND name = ?",
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.setString(2, name)
            stmt.executeQuery().use { it.next() }
        }
    }

/** Die Ablageform des Primaerschluessels derselben Tabelle. */
private fun primaryKeyStorageForm(pool: ConnectionPool, table: String): String? =
    pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT i.type_desc FROM sys.indexes i " +
                "WHERE i.object_id = OBJECT_ID(?) AND i.is_primary_key = 1",
        ).use { stmt ->
            stmt.setString(1, table)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

/** Derselbe Kanonisierer, den der Migrate-Pfad fuer diesen Dialekt waehlt. */
private fun fingerprintOf(schema: SchemaDefinition) = MigrationFingerprint.compute(
    schema,
    // Benannt statt als Trailing-Lambda: `compute` traegt seit v9 eine zweite
    // Projektion, und ein nachgestelltes `{ … }` bezoege sich auf die letzte.
    canonicalizeType = { type ->
        MssqlDriver().typeCanonicalizer().canonicalize(type, schema.customTypes)
    },
)

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-mssql",
    schema = readSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.MSSQL,
)
