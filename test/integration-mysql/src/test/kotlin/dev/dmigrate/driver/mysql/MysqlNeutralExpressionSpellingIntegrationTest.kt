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
     * Der Typname eines `CAST` ist mehrwortig (MySQL-Handbuch, „Cast Functions
     * and Operators"). Schuetzte die Stellungsregel nur das **unmittelbar**
     * folgende Wort, schrieb der Generator `cast(total as signed \`integer\`)`
     * — `ERROR 1064` auf 9.7.2 und 8.0.46. Hinter der schliessenden Klammer
     * des Aufrufs muss die Quotierung wieder greifen, sonst faellt
     * `cast(note as char) <> key` am selben Fehler.
     */
    test("a multi-word CAST type stays bare, and behind it the quoting resumes") {
        val typed = SchemaDefinition(
            name = "casts", version = "1",
            tables = mapOf(
                "Metering" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "total" to ColumnDefinition(NeutralType.Integer, required = true),
                        "note" to ColumnDefinition(NeutralType.Text(maxLength = 40)),
                        "ratio" to ColumnDefinition(NeutralType.Float()),
                        // Ein reserviertes Wort als Spaltenname — der Fall aus M1.
                        "key" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "ck_cast_signed", type = ConstraintType.CHECK,
                            expression = "cast(total as signed integer) > 0",
                        ),
                        ConstraintDefinition(
                            name = "ck_cast_charset", type = ConstraintType.CHECK,
                            expression = "cast(note as char character set utf8mb4) <> 'x'",
                        ),
                        ConstraintDefinition(
                            name = "ck_cast_double", type = ConstraintType.CHECK,
                            expression = "cast(ratio as double precision) > 0",
                        ),
                        ConstraintDefinition(
                            name = "ck_cast_key", type = ConstraintType.CHECK,
                            expression = "length(cast(note as char)) <> key",
                        ),
                    ),
                ),
            ),
        )

        // Der neutrale Text ist gueltig: ein Typname hinter `AS` ist keine
        // Spalte, auch mehrwortig (`CheckExpressionColumns`).
        val validation = SchemaValidator().validate(typed)
        withClue(validation.errors.map { "${it.code}: ${it.message}" }.toString()) {
            validation.errors.filter { it.code == "E012" }.shouldBeEmpty()
        }

        val ddl = MysqlDdlGenerator().generate(typed)
        ddl.render() shouldContain "cast(total as signed integer)"
        ddl.render() shouldContain "cast(note as char character set utf8mb4)"
        ddl.render() shouldContain "cast(ratio as double precision)"
        withClue("hinter der Klammer des CAST greift die Quotierung wieder") {
            ddl.render() shouldContain "length(cast(note as char)) <> `key`"
        }

        pool!!.borrow().asJdbc().use { c ->
            c.createStatement().use { s -> s.execute("DROP TABLE IF EXISTS Metering") }
            c.apply(ddl.statements)

            fun insert(id: Int, total: Int, note: String, key: Int) = runCatching {
                c.prepareStatement("INSERT INTO Metering (id, total, note, ratio, `key`) VALUES (?, ?, ?, 1.5, ?)")
                    .use { ps ->
                        ps.setInt(1, id)
                        ps.setInt(2, total)
                        ps.setString(3, note)
                        ps.setInt(4, key)
                        ps.executeUpdate()
                    }
                Unit
            }

            insert(1, 5, "abc", 7).isSuccess shouldBe true
            withClue("der CHECK mit dem mehrwortigen Typnamen greift nicht") {
                (insert(2, 0, "abc", 7).exceptionOrNull()?.message ?: "") shouldContain "ck_cast_signed"
            }
            withClue("der CHECK hinter dem Typnamen greift nicht") {
                (insert(3, 5, "abc", 3).exceptionOrNull()?.message ?: "") shouldContain "ck_cast_key"
            }
        }
    }

    /**
     * Dasselbe Wort, zwei Stellungen: `` `match` `` ist als Operand ein
     * Spaltenname und braucht Backticks, `between` daneben ist Syntax und
     * darf keine bekommen. Beides in **einem** Ausdruck, ueber den Reverse
     * zurueck an denselben Server — vorher endete das in `ERROR 1064`.
     */
    test("MySQL to MySQL: the operand gets its backticks, the syntax next to it keeps none") {
        val active = pool!!
        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS Operand_Source")
                s.execute(
                    """CREATE TABLE Operand_Source (
                         id INT PRIMARY KEY,
                         `default` INT NOT NULL,
                         `mod` INT NOT NULL,
                         `match` INT NOT NULL,
                         total INT GENERATED ALWAYS AS (`default` + `mod`) STORED,
                         CONSTRAINT ck_operand_default CHECK (`default` > 0),
                         CONSTRAINT ck_operand_match CHECK (`match` between 1 and 9))""",
                )
            }
        }

        val source = MysqlSchemaReader().read(active).schema.tables.getValue("Operand_Source")
        withClue("der neutrale Text traegt die Woerter nackt") {
            source.constraints.first { it.name == "ck_operand_default" }.expression shouldBe "(default > 0)"
            source.constraints.first { it.name == "ck_operand_match" }.expression shouldBe
                "(match between 1 and 9)"
            (source.columns.getValue("total").generation as ColumnGeneration.Computed).expression shouldBe
                "(default + mod)"
        }

        val ddl = MysqlDdlGenerator().generate(
            SchemaDefinition(name = "operand", version = "1", tables = mapOf("Operand_Target" to source)),
        )
        ddl.render() shouldContain "CHECK ((`default` > 0))"
        withClue("`between` ist Syntax und darf keine Backticks bekommen") {
            ddl.render() shouldContain "CHECK ((`match` between 1 and 9))"
        }
        ddl.render() shouldContain "(`default` + `mod`)"

        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS Operand_Target")
                // MySQL verlangt CHECK-Namen schemaweit eindeutig.
                s.execute("DROP TABLE IF EXISTS Operand_Source")
            }
            c.apply(ddl.statements)
            withClue("der CHECK auf `default` greift nicht") {
                val failure = runCatching {
                    c.createStatement().use { s ->
                        s.executeUpdate(
                            "INSERT INTO Operand_Target (id, `default`, `mod`, `match`) VALUES (1, 0, 1, 5)",
                        )
                    }
                }.exceptionOrNull()?.message ?: ""
                failure shouldContain "ck_operand_default"
            }
            withClue("der CHECK auf `match` greift nicht") {
                val failure = runCatching {
                    c.createStatement().use { s ->
                        s.executeUpdate(
                            "INSERT INTO Operand_Target (id, `default`, `mod`, `match`) VALUES (2, 1, 1, 99)",
                        )
                    }
                }.exceptionOrNull()?.message ?: ""
                failure shouldContain "ck_operand_match"
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
