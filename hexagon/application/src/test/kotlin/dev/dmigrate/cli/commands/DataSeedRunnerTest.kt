package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.SchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.sql.Types

/**
 * Unit-Tests für [DataSeedRunner] mit Fakes für alle externen
 * Collaborators, analog zum Vorbild [DataImportRunnerCallbackTest] und
 * [DataTransferRunnerTest]. Deckt jeden Exit-Code-Pfad aus
 * ImpPlan-1.3.0-cli-data-seed-p1.md ab.
 */
class DataSeedRunnerTest : FunSpec({

    val usersSchema = SchemaDefinition(
        name = "test",
        version = "1.0",
        tables = mapOf(
            "customers" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                ),
            ),
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                    "customer_id" to ColumnDefinition(
                        type = NeutralType.Integer,
                        required = true,
                        references = ReferenceDefinition(table = "customers", column = "id"),
                    ),
                ),
            ),
        ),
    )

    fun fakeSchemaCodec(schema: SchemaDefinition) = object : SchemaCodec {
        override fun read(input: InputStream) = error("not used in these tests")
        override fun read(path: Path) = schema
        override fun write(output: OutputStream, schema: SchemaDefinition) = error("not used in these tests")
    }

    val fakeConnectionConfig = ConnectionConfig(DatabaseDialect.SQLITE, "h", null, "d", null, null)

    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        var closeCount = 0
        override fun borrow(): DatabaseConnection = error("not used in these tests")
        override fun activeConnections() = 0
        override fun close() {
            closeCount++
        }
    }

    fun fakeSession(targetColumns: List<TargetColumn>) = object : TableImportSession {
        override val targetColumns = targetColumns
        val writtenChunks = mutableListOf<DataChunk>()
        override fun write(chunk: DataChunk): WriteResult {
            writtenChunks += chunk
            return WriteResult(chunk.rows.size.toLong(), 0, 0)
        }
        override fun commitChunk() {}
        override fun rollbackChunk() {}
        override fun markTruncatePerformed() {}
        override fun finishTable() = FinishTableResult.Success(emptyList())
        override fun close() {}
    }

    fun fakeWriter(sessions: Map<String, TableImportSession>) = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync(): SchemaSync = error("not used in these tests")
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession =
            sessions[table] ?: error("no fake session registered for table '$table'")
    }

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined() = lines.joinToString("\n")
    }

    fun runner(
        schema: SchemaDefinition = usersSchema,
        sessions: Map<String, TableImportSession> = mapOf(
            "customers" to fakeSession(listOf(TargetColumn("id", false, Types.INTEGER))),
            "orders" to fakeSession(
                listOf(TargetColumn("id", false, Types.INTEGER), TargetColumn("customer_id", false, Types.INTEGER)),
            ),
        ),
        targetResolver: (String?, Path?) -> String = { target, _ -> target ?: "sqlite:///tmp/x.db" },
        urlParser: (String) -> ConnectionConfig = { fakeConnectionConfig },
        poolFactory: (ConnectionConfig) -> ConnectionPool = { fakePool },
        out: Capture = Capture(),
        err: Capture = Capture(),
    ) = Triple(
        DataSeedRunner(
            schemaCodec = fakeSchemaCodec(schema),
            targetResolver = targetResolver,
            urlParser = urlParser,
            poolFactory = poolFactory,
            writerLookup = { fakeWriter(sessions) },
            stdout = out.sink,
            stderr = err.sink,
        ),
        out,
        err,
    )

    fun request(
        seed: Long? = 42L,
        count: Int = 5,
        locale: String = "en",
        target: String? = "sqlite:///tmp/x.db",
    ) = DataSeedRequest(
        schema = Path.of("unused.yaml"),
        target = target,
        count = count,
        seed = seed,
        locale = locale,
        cliConfigPath = null,
    )

    test("success: writes rows for every table, prints the seed and a summary") {
        val (r, out, _) = runner()
        val exitCode = r.execute(request())
        exitCode shouldBe 0
        out.joined() shouldContain "Verwendeter Seed: 42"
        out.joined() shouldContain "10 Zeile(n) in 2 Tabelle(n) erzeugt."
    }

    test("determinism: two runs with the same seed write identical rows") {
        val onlyCustomers = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf("customers" to usersSchema.tables.getValue("customers")),
        )
        val sessionA = fakeSession(listOf(TargetColumn("id", false, Types.INTEGER)))
        val sessionB = fakeSession(listOf(TargetColumn("id", false, Types.INTEGER)))
        val (r1, _, _) = runner(schema = onlyCustomers, sessions = mapOf("customers" to sessionA))
        val (r2, _, _) = runner(schema = onlyCustomers, sessions = mapOf("customers" to sessionB))
        r1.execute(request(seed = 7))
        r2.execute(request(seed = 7))
        sessionA.writtenChunks shouldBe sessionB.writtenChunks
        sessionA.writtenChunks.isEmpty() shouldBe false
    }

    test("unknown locale returns exit 7 and does not open a connection") {
        val (r, _, err) = runner(poolFactory = { error("must not connect") })
        val exitCode = r.execute(request(locale = "fr"))
        exitCode shouldBe 7
        err.joined() shouldContain "--locale"
    }

    test("schema read failure returns exit 7") {
        val throwingCodec = object : SchemaCodec {
            override fun read(input: InputStream) = error("unused")
            override fun read(path: Path): SchemaDefinition = error("boom")
            override fun write(output: OutputStream, schema: SchemaDefinition) = error("unused")
        }
        val r = DataSeedRunner(
            schemaCodec = throwingCodec,
            targetResolver = { target, _ -> target ?: "sqlite:///tmp/x.db" },
            urlParser = { fakeConnectionConfig },
            poolFactory = { fakePool },
            writerLookup = { fakeWriter(emptyMap()) },
        )
        r.execute(request()) shouldBe 7
    }

    test("target resolution failure returns exit 7") {
        val (r, _, err) = runner(targetResolver = { _, _ -> throw IllegalArgumentException("no default target") })
        r.execute(request(target = null)) shouldBe 7
        err.joined() shouldContain "no default target"
    }

    test("connection failure returns exit 4") {
        val (r, _, err) = runner(poolFactory = { throw RuntimeException("refused") })
        r.execute(request()) shouldBe 4
        err.joined() shouldContain "Failed to connect"
    }

    test("target column that is NOT NULL and missing from the source schema returns exit 3") {
        val sessions = mapOf(
            "customers" to fakeSession(
                listOf(TargetColumn("id", false, Types.INTEGER), TargetColumn("extra", false, Types.VARCHAR)),
            ),
        )
        val onlyCustomers = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf("customers" to usersSchema.tables.getValue("customers")),
        )
        val (r, _, err) = runner(schema = onlyCustomers, sessions = sessions)
        r.execute(request()) shouldBe 3
        err.joined() shouldContain "extra"
    }

    test("a real FK cycle on a required column returns exit 3") {
        val cyclic = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf(
                "a" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                        "b_id" to ColumnDefinition(
                            type = NeutralType.Integer, required = true,
                            references = ReferenceDefinition(table = "b", column = "id"),
                        ),
                    ),
                ),
                "b" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                        "a_id" to ColumnDefinition(
                            type = NeutralType.Integer, required = true,
                            references = ReferenceDefinition(table = "a", column = "id"),
                        ),
                    ),
                ),
            ),
        )
        val sessions = mapOf(
            "a" to fakeSession(listOf(TargetColumn("id", false, Types.INTEGER), TargetColumn("b_id", false, Types.INTEGER))),
            "b" to fakeSession(listOf(TargetColumn("id", false, Types.INTEGER), TargetColumn("a_id", false, Types.INTEGER))),
        )
        val (r, _, _) = runner(schema = cyclic, sessions = sessions)
        r.execute(request()) shouldBe 3
    }

    test("a unique column with too few possible values returns exit 5") {
        val schema = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf(
                "flags" to TableDefinition(
                    columns = mapOf(
                        "flag" to ColumnDefinition(
                            type = NeutralType.Enum(values = listOf("a", "b")),
                            required = true,
                            unique = true,
                        ),
                    ),
                ),
            ),
        )
        val sessions = mapOf("flags" to fakeSession(listOf(TargetColumn("flag", false, Types.VARCHAR))))
        val (r, _, _) = runner(schema = schema, sessions = sessions)
        r.execute(request(count = 5)) shouldBe 5
    }

    test("openTable failure returns exit 3") {
        val failingWriter = object : DataWriter {
            override val dialect = DatabaseDialect.SQLITE
            override fun schemaSync(): SchemaSync = error("unused")
            override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession =
                error("target metadata unreadable")
        }
        val onlyCustomers = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf("customers" to usersSchema.tables.getValue("customers")),
        )
        val r = DataSeedRunner(
            schemaCodec = fakeSchemaCodec(onlyCustomers),
            targetResolver = { target, _ -> target ?: "sqlite:///tmp/x.db" },
            urlParser = { fakeConnectionConfig },
            poolFactory = { fakePool },
            writerLookup = { failingWriter },
        )
        r.execute(request()) shouldBe 3
    }

    test("write failure returns exit 5 and still closes the pool") {
        val pool = object : ConnectionPool {
            override val dialect = DatabaseDialect.SQLITE
            var closeCount = 0
            override fun borrow(): DatabaseConnection = error("unused")
            override fun activeConnections() = 0
            override fun close() {
                closeCount++
            }
        }
        val failingSession = object : TableImportSession {
            override val targetColumns = listOf(TargetColumn("id", false, Types.INTEGER))
            override fun write(chunk: DataChunk): WriteResult = throw RuntimeException("disk full")
            override fun commitChunk() {}
            override fun rollbackChunk() {}
            override fun markTruncatePerformed() {}
            override fun finishTable() = FinishTableResult.Success(emptyList())
            override fun close() {}
        }
        val onlyCustomers = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf("customers" to usersSchema.tables.getValue("customers")),
        )
        val r = DataSeedRunner(
            schemaCodec = fakeSchemaCodec(onlyCustomers),
            targetResolver = { target, _ -> target ?: "sqlite:///tmp/x.db" },
            urlParser = { fakeConnectionConfig },
            poolFactory = { pool },
            writerLookup = { fakeWriter(mapOf("customers" to failingSession)) },
        )
        r.execute(request()) shouldBe 5
        pool.closeCount shouldBe 1
    }

    test("no seed given: a random seed is still printed") {
        val onlyCustomers = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf("customers" to usersSchema.tables.getValue("customers")),
        )
        val sessions = mapOf("customers" to fakeSession(listOf(TargetColumn("id", false, Types.INTEGER))))
        val (r, out, _) = runner(schema = onlyCustomers, sessions = sessions)
        r.execute(request(seed = null)) shouldBe 0
        out.joined() shouldContain "Verwendeter Seed: "
    }
})
