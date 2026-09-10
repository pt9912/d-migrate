package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.TargetProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * In welcher Gestalt PostgreSQL eine Werteaufzaehlung zurueckgibt — und dass
 * die Erkennung sie trifft.
 *
 * Kein Unit-Test kann das zeigen: der Server schreibt den Ausdruck beim
 * Speichern in seine eigene Normalform um, und wie die aussieht, sagt nur er
 * selbst. Sie ist reichhaltiger, als man erwartet — `IN` wird zu
 * `= ANY (ARRAY[…])`, jedes Literal traegt einen Typ-Cast, eine
 * `varchar`-Spalte wird selbst gecastet, und eine einelementige Liste kommt
 * als Gleichheit zurueck.
 *
 * Trifft die Erkennung eine dieser Formen nicht, faellt der CHECK im Vergleich
 * auseinander: die Datenbank fuehrt ihn, das Soll fuehrt den Wertevorrat am
 * Spaltentyp, und der Lauf plant das Loesen eines Constraints, den er selbst
 * angelegt hat.
 */
class PostgresEnumCheckReverseFormIntegrationTest : FunSpec({

    val container = org.testcontainers.containers.PostgreSQLContainer("postgres:16-alpine")
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE mood_probe (
                         id bigint PRIMARY KEY,
                         mood text CONSTRAINT ck_mood CHECK (mood IN ('red', 'green')),
                         shade varchar(5) CONSTRAINT ck_shade CHECK (shade IN ('a')),
                         tone text CONSTRAINT ck_tone CHECK (tone = 'x' OR tone = 'y'),
                         quoted text CONSTRAINT ck_quoted CHECK (quoted IN ('it''s', 'b,c')))""",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun reverseTable(): TableDefinition = PostgresSchemaReader().read(pool).schema.tables.getValue("mood_probe")

    fun expressionOf(name: String): String =
        reverseTable().constraints.single { it.name == name }.expression.orEmpty()

    test("the server's normal form for a value list is ANY over an ARRAY, with casts") {
        expressionOf("ck_mood") shouldBe "((mood = ANY (ARRAY['red'::text, 'green'::text])))"
        expressionOf("ck_quoted") shouldBe "((quoted = ANY (ARRAY['it''s'::text, 'b,c'::text])))"
        // Eine einelementige Liste ist keine Liste mehr, und die Spalte selbst
        // traegt den Cast, weil ihr Typ nicht der des Literals ist.
        expressionOf("ck_shade") shouldBe "(((shade)::text = 'a'::text))"
        expressionOf("ck_tone") shouldBe "(((tone = 'x'::text) OR (tone = 'y'::text)))"
    }

    test("an authored enum matches the reverse-read column with its CHECK") {
        val reverse = reverseTable()
        val current = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to reverse.copy(
                // Nur die Enum-Spalte betrachten: die uebrigen CHECKs dieser
                // Tabelle sind eigene Faelle mit eigenen Zusicherungen.
                columns = linkedMapOf("mood" to reverse.columns.getValue("mood")),
                constraints = reverse.constraints.filter { it.name == "ck_mood" },
                primaryKey = emptyList(),
            )),
        )
        val authored = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to TableDefinition(
                columns = linkedMapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
            )),
        )
        val canon = PostgresDriver().typeCanonicalizer()
        val diff = SchemaComparator(TargetProjection(type = { t -> canon.canonicalize(t, emptyMap()) }))
            .compare(current, authored)

        withClue(diff.tablesChanged.toString()) { diff.tablesChanged.shouldBeEmpty() }
    }

    test("a hand-written IN check matches its own authored text after the round trip") {
        // Der Fall, den ein Anwender heute als Nacharbeit fuehren muss: das
        // Soll traegt eine Textspalte MIT dem CHECK, die Datenbank gibt ihn in
        // Serverform zurueck. Ohne Erkennung stuenden zwei Texte gegeneinander,
        // die nie uebereinstimmen.
        val reverse = reverseTable()
        val current = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to reverse.copy(
                columns = linkedMapOf("mood" to reverse.columns.getValue("mood")),
                constraints = reverse.constraints.filter { it.name == "ck_mood" },
                primaryKey = emptyList(),
            )),
        )
        val authored = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to TableDefinition(
                columns = linkedMapOf("mood" to ColumnDefinition(NeutralType.Text(null))),
                constraints = listOf(ConstraintDefinition(
                    name = "ck_mood", type = ConstraintType.CHECK, expression = "mood IN ('red', 'green')",
                )),
            )),
        )
        val canon = PostgresDriver().typeCanonicalizer()
        val diff = SchemaComparator(TargetProjection(type = { t -> canon.canonicalize(t, emptyMap()) }))
            .compare(current, authored)

        withClue(diff.tablesChanged.toString()) { diff.tablesChanged.shouldBeEmpty() }
    }

    test("a CHECK that is not a value list stays a constraint") {
        val reverse = reverseTable()
        val current = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to reverse.copy(
                columns = linkedMapOf("shade" to reverse.columns.getValue("shade")),
                constraints = listOf(ConstraintDefinition(
                    name = "ck_range", type = ConstraintType.CHECK, expression = "length(shade) > 0",
                )),
                primaryKey = emptyList(),
            )),
        )
        val authored = SchemaDefinition(
            name = "pg", version = "1",
            tables = mapOf("mood_probe" to TableDefinition(
                columns = linkedMapOf("shade" to ColumnDefinition(NeutralType.Text(5))),
            )),
        )
        val canon = PostgresDriver().typeCanonicalizer()
        val diff = SchemaComparator(TargetProjection(type = { t -> canon.canonicalize(t, emptyMap()) }))
            .compare(current, authored)

        diff.tablesChanged.single().constraintsRemoved.map { it.name } shouldBe listOf("ck_range")
    }
})
