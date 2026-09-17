package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.RawSqlExpressionPortability
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.MySQLContainer
import java.math.BigDecimal
import java.sql.Connection

/**
 * Ein neutraler Ausdruck mit `"…"`-Bezeichnern und einem Backslash im Literal,
 * auf MySQL angelegt und **ausgewertet**.
 *
 * Ein Textvergleich zeigt nur, dass Backticks im DDL stehen. Ob MySQL damit
 * rechnet, sagt nur der Server: vorher las er `"Qty"` als Zeichenkette,
 * `'Qty' * 'UnitPrice'` ergab still `0`, und `'Qty' > 0` war immer falsch.
 * Das ist der Weg einer PascalCase-Spalte von SQL Server nach MySQL.
 */
class MysqlNeutralExpressionSpellingIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("dmigrate_spelling")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            ),
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    val schema = SchemaDefinition(
        name = "spelling", version = "1",
        tables = mapOf(
            "OrderLine" to TableDefinition(
                columns = linkedMapOf(
                    "Id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "Qty" to ColumnDefinition(NeutralType.Integer, required = true),
                    "UnitPrice" to ColumnDefinition(NeutralType.Decimal(10, 2), required = true),
                    "Note" to ColumnDefinition(NeutralType.Text(maxLength = 40)),
                    "LineTotal" to ColumnDefinition(
                        NeutralType.Decimal(12, 2),
                        generation = ColumnGeneration.Computed("\"Qty\" * \"UnitPrice\"", stored = true),
                    ),
                ),
                primaryKey = listOf("Id"),
                indices = listOf(
                    IndexDefinition(
                        name = "ix_orderline_note",
                        columns = listOf(IndexColumn.expression("lower(\"Note\")")),
                    ),
                ),
                constraints = listOf(
                    ConstraintDefinition(name = "ck_orderline_qty", type = ConstraintType.CHECK, expression = "\"Qty\" > 0"),
                    // Der Backslash ist im neutralen Literal ein Zeichen; die
                    // Zeichenkette mit `"` bleibt eine Zeichenkette.
                    ConstraintDefinition(
                        name = "ck_orderline_note",
                        type = ConstraintType.CHECK,
                        expression = "\"Note\" <> 'a\\b' AND \"Note\" <> 'say \"hi\"'",
                    ),
                ),
            ),
        ),
    )

    fun Connection.apply(statements: List<DdlStatement>) = createStatement().use { stmt ->
        statements
            .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
            .map { it.joinToString("\n").trim().removeSuffix(";") }
            .filter { it.isNotBlank() }
            .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
    }

    fun Connection.insert(id: Int, qty: Int, note: String?): Result<Unit> = runCatching {
        prepareStatement("INSERT INTO OrderLine (Id, Qty, UnitPrice, Note) VALUES (?, ?, 2.50, ?)").use { ps ->
            ps.setInt(1, id)
            ps.setInt(2, qty)
            ps.setString(3, note)
            ps.executeUpdate()
        }
        Unit
    }

    test("the generated DDL computes, checks and indexes the named columns") {
        val ddl = MysqlDdlGenerator().generate(schema)
        ddl.render() shouldContain "GENERATED ALWAYS AS (`Qty` * `UnitPrice`)"
        pool!!.borrow().asJdbc().use { c ->
            c.apply(ddl.statements)

            c.insert(1, 3, "ok").isSuccess shouldBe true
            val total = c.createStatement().use { s ->
                s.executeQuery("SELECT LineTotal FROM OrderLine WHERE Id = 1").use { rs ->
                    rs.next()
                    rs.getBigDecimal(1)
                }
            }
            withClue("MySQL rechnete mit Zeichenketten?") { total.compareTo(BigDecimal("7.50")) shouldBe 0 }

            withClue("der CHECK auf Qty greift nicht") {
                (c.insert(2, 0, "ok").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_qty"
            }
            withClue("das Literal 'a\\b' kam mit einem anderen Wert an") {
                (c.insert(3, 1, "a\\b").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_note"
            }
            withClue("das Literal mit \" kam nicht als Zeichenkette an") {
                (c.insert(4, 1, "say \"hi\"").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_note"
            }
            // Gegenprobe zum Backslash: ohne Verdopplung stuende dort `a<BS>`.
            c.insert(5, 1, "a\b").isSuccess shouldBe true
        }
    }

    /**
     * P6: derselbe Weg zurueck. Der Server gibt CHECK, Berechnungsausdruck und
     * den Ausdrucks-Schluessel eines Index in seiner eigenen Schreibweise
     * zurueck — mit Zeichensatz-Introducer, Backslash-Escapes und Backticks,
     * das Ganze ein zweites Mal escapet. Was davon im Modell landet, sagt nur
     * der Server; ein Unit-Test wuerde die Serverform bloss behaupten.
     */
    test("the reverse reads the server text in neutral spelling and the schema is valid") {
        val active = pool!!
        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS Reverse_Line")
                s.execute(
                    """CREATE TABLE Reverse_Line (
                         id INT PRIMARY KEY,
                         email VARCHAR(100),
                         `Qty` INT NOT NULL,
                         `UnitPrice` DECIMAL(10,2) NOT NULL,
                         `LineTotal` DECIMAL(12,2) GENERATED ALWAYS AS (`Qty` * `UnitPrice`) STORED,
                         label VARCHAR(60) GENERATED ALWAYS AS (CONCAT('q-', `Qty`)) VIRTUAL,
                         `key` INT NOT NULL,
                         CONSTRAINT ck_reverse_mail CHECK (email LIKE '%@%'),
                         CONSTRAINT ck_reverse_key CHECK (`key` > 0),
                         CONSTRAINT ck_reverse_qty CHECK (`Qty` > 0))""",
                )
                s.execute("CREATE INDEX ix_reverse_label ON Reverse_Line ((lower(label)))")
            }
        }

        val read = MysqlSchemaReader().read(active)
        val table = read.schema.tables.getValue("Reverse_Line")

        // Kein Introducer, kein Backslash-Escape, kein Backtick — und die
        // PascalCase-Spalte in der neutralen Bezeichner-Schreibweise.
        table.constraints.first { it.name == "ck_reverse_mail" }.expression shouldBe "(email like '%@%')"
        table.constraints.first { it.name == "ck_reverse_qty" }.expression shouldBe "(\"Qty\" > 0)"
        (table.columns.getValue("LineTotal").generation as ColumnGeneration.Computed).expression shouldBe
            "(\"Qty\" * \"UnitPrice\")"
        (table.columns.getValue("label").generation as ColumnGeneration.Computed).expression shouldBe
            "concat('q-',\"Qty\")"
        table.indices.first { it.name == "ix_reverse_label" }.columns.single().expression shouldBe "lower(label)"

        // C1/M10: das Schema ist gueltig — vorher meldete die Validierung den
        // Introducer als unbekannte Spalte (`E012`, `E136`).
        val validation = SchemaValidator().validate(read.schema)
        withClue(validation.errors.map { "${it.code}: ${it.message}" }.toString()) {
            validation.errors.filter { it.code == "E012" || it.code == "E136" }.shouldBeEmpty()
        }

        // M1: ein reserviertes Wort ueberlebt den Weg MySQL -> MySQL.
        table.constraints.first { it.name == "ck_reverse_key" }.expression shouldBe "(key > 0)"

        // N2: und er ist auf jedem anderen Ziel portabel.
        for (target in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.SQLITE, DatabaseDialect.MSSQL)) {
            for (constraint in table.constraints) {
                withClue("$target: ${constraint.name}") {
                    RawSqlExpressionPortability.assess(constraint.expression, target).portable shouldBe true
                }
            }
            withClue("$target: LineTotal") {
                RawSqlExpressionPortability.computedRefusal(
                    "LineTotal",
                    (table.columns.getValue("LineTotal").generation as ColumnGeneration.Computed).expression,
                    target,
                ) shouldBe null
            }
        }
    }

    /**
     * M1: derselbe Reverse, zurueck an denselben Server. Der neutrale Text
     * traegt das reservierte Wort **nackt** (`key > 0`), und genau so
     * geschrieben lehnt MySQL die Anweisung ab (`ERROR 1064`, an 9.7.2
     * gemessen). Ein Textvergleich zeigte nur Backticks im DDL; ob der Server
     * die Anweisung annimmt, sagt nur der Server.
     */
    test("MySQL to MySQL: a reserved column name comes back quoted and the server takes it") {
        val active = pool!!
        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS Reserved_Source")
                s.execute(
                    """CREATE TABLE Reserved_Source (
                         id INT PRIMARY KEY,
                         `key` INT NOT NULL,
                         `order` INT NOT NULL,
                         total INT GENERATED ALWAYS AS (`key` + `order`) STORED,
                         CONSTRAINT ck_reserved_key CHECK (`key` > 0))""",
                )
                s.execute("CREATE INDEX ix_reserved_key ON Reserved_Source ((`key` * 2))")
            }
        }

        val source = MysqlSchemaReader().read(active).schema.tables.getValue("Reserved_Source")
        withClue("der neutrale Text traegt das Wort nackt") {
            source.constraints.first { it.name == "ck_reserved_key" }.expression shouldBe "(key > 0)"
            (source.columns.getValue("total").generation as ColumnGeneration.Computed).expression shouldBe
                "(key + order)"
        }

        val roundTrip = SchemaDefinition(
            name = "reserved", version = "1",
            tables = mapOf("Reserved_Target" to source),
        )
        val ddl = MysqlDdlGenerator().generate(roundTrip)
        ddl.render() shouldContain "CHECK ((`key` > 0))"

        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS Reserved_Target")
                // MySQL verlangt CHECK-Namen schemaweit eindeutig; die Quelle
                // traegt denselben und hat ihren Zweck getan.
                s.execute("DROP TABLE IF EXISTS Reserved_Source")
            }
            c.apply(ddl.statements)
            withClue("der CHECK auf `key` greift nicht") {
                val failure = runCatching {
                    c.createStatement().use { s ->
                        s.executeUpdate("INSERT INTO Reserved_Target (id, `key`, `order`) VALUES (1, 0, 1)")
                    }
                }.exceptionOrNull()?.message ?: ""
                failure shouldContain "ck_reserved_key"
            }
        }
    }
})
