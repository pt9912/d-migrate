package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * Constraint-Namen beim SQLite-Reverse: aus der Quelle, wo sie dastehen —
 * sonst gebildet, schemaweit eindeutig und von Lauf zu Lauf gleich.
 *
 * Der Katalog fuehrt keine: `PRAGMA foreign_key_list` nummeriert je Tabelle
 * durch. Der Reverse vergab daraufhin in jeder Tabelle `fk_0`, `uq_0` — und
 * die Ziele lehnten das ab (`Msg 2714` auf SQL Server,
 * `relation "uq_0" already exists` auf PostgreSQL, beides in der
 * Compare-Matrix gemessen).
 *
 * Die Faelle hier laufen gegen eine echte In-Memory-SQLite: der abgelegte
 * `CREATE TABLE`-Text, aus dem die Namen kommen, ist genau das, was der Server
 * daraus macht — eine Behauptung darueber waere keine Messung.
 */
class SqliteConstraintNamingTest : FunSpec({

    val reader = SqliteSchemaReader()

    fun pool(conn: Connection) = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = JdbcDatabaseConnection(conn)
        override fun activeConnections(): Int = 1
        override fun close() {}
    }

    fun withDb(vararg statements: String, block: (ConnectionPool) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt -> statements.forEach { stmt.execute(it.trim()) } }
            block(pool(conn))
        }
    }

    test("a named table-level foreign key keeps its name") {
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY, pid INTEGER NOT NULL,
                 CONSTRAINT fk_c_parent FOREIGN KEY (pid) REFERENCES p(id))""",
        ) { pool ->
            val fk = reader.read(pool).schema.tables.getValue("c")
                .constraints.single { it.type == ConstraintType.FOREIGN_KEY }
            fk.name shouldBe "fk_c_parent"
        }
    }

    test("FOREIGN KEY with more than one space or a line break keeps its name") {
        // Der Scanner duldet beliebigen Leerraum zwischen den beiden Woertern,
        // sprang fuer die Spaltenliste aber um die feste Laenge `FOREIGN KEY`
        // weiter — und landete dann neben der Klammer. Folge: die Klausel fiel
        // weg und der Name mit ihr, still.
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY, pid INTEGER NOT NULL,
                 CONSTRAINT fk_c_spaced FOREIGN  KEY (pid) REFERENCES p(id))""",
        ) { pool ->
            val fk = reader.read(pool).schema.tables.getValue("c")
                .constraints.single { it.type == ConstraintType.FOREIGN_KEY }
            fk.name shouldBe "fk_c_spaced"
        }
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY, pid INTEGER NOT NULL,
                 CONSTRAINT fk_c_broken FOREIGN
                 KEY (pid) REFERENCES p(id))""",
        ) { pool ->
            val fk = reader.read(pool).schema.tables.getValue("c")
                .constraints.single { it.type == ConstraintType.FOREIGN_KEY }
            fk.name shouldBe "fk_c_broken"
        }
    }

    test("a named column-level foreign key keeps its name, an unnamed one is generated") {
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY,
                 owner_id INTEGER CONSTRAINT fk_c_owner REFERENCES p(id),
                 plain_id INTEGER REFERENCES p(id))""",
        ) { pool ->
            val fks = reader.read(pool).schema.tables.getValue("c")
                .constraints.filter { it.type == ConstraintType.FOREIGN_KEY }
            fks.map { it.name }.toSet() shouldBe setOf("fk_c_owner", "fk_c_plain_id")
        }
    }

    test("two tables no longer share a name") {
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            "CREATE TABLE a (id INTEGER PRIMARY KEY, pid INTEGER REFERENCES p(id))",
            "CREATE TABLE b (id INTEGER PRIMARY KEY, pid INTEGER REFERENCES p(id))",
        ) { pool ->
            val schema = reader.read(pool).schema
            val names = schema.tables.values.flatMap { table ->
                table.constraints.filter { it.type == ConstraintType.FOREIGN_KEY }.map { it.name }
            }
            names.toSet() shouldHaveSize names.size
            names.toSet() shouldBe setOf("fk_a_pid", "fk_b_pid")
        }
    }

    test("a generated name gives way to a real one from another table") {
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            // Der echte Name der zweiten Tabelle ist genau der, den die erste
            // sonst bilden wuerde — und er wird zuerst reserviert.
            "CREATE TABLE a (id INTEGER PRIMARY KEY, pid INTEGER REFERENCES p(id))",
            """CREATE TABLE b (
                 id INTEGER PRIMARY KEY, x INTEGER NOT NULL,
                 CONSTRAINT fk_a_pid FOREIGN KEY (x) REFERENCES p(id))""",
        ) { pool ->
            val schema = reader.read(pool).schema
            schema.tables.getValue("b").constraints.single { it.type == ConstraintType.FOREIGN_KEY }
                .name shouldBe "fk_a_pid"
            schema.tables.getValue("a").constraints.single { it.type == ConstraintType.FOREIGN_KEY }
                .name shouldBe "fk_a_pid_2"
        }
    }

    test("a generated name is cut to the smallest identifier limit of the targets") {
        val longTable = "t".repeat(40)
        val longColumn = "c".repeat(40)
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY)",
            "CREATE TABLE $longTable (id INTEGER PRIMARY KEY, $longColumn INTEGER REFERENCES p(id))",
        ) { pool ->
            val fk = reader.read(pool).schema.tables.getValue(longTable)
                .constraints.single { it.type == ConstraintType.FOREIGN_KEY }
            fk.name.length shouldBe 63
            fk.name shouldBe ("fk_$longTable" + "_" + longColumn).take(63)
        }
    }

    test("a foreign key without a column list reads the primary key of the target (I3)") {
        withDb(
            "CREATE TABLE p (id INTEGER PRIMARY KEY, code TEXT)",
            "CREATE TABLE c (id INTEGER PRIMARY KEY, pid INTEGER REFERENCES p)",
        ) { pool ->
            val fk = reader.read(pool).schema.tables.getValue("c")
                .constraints.single { it.type == ConstraintType.FOREIGN_KEY }
            fk.references!!.table shouldBe "p"
            fk.references!!.columns shouldBe listOf("id")
        }
    }

    test("a comment in the table text is not read as SQL") {
        withDb(
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY, -- der Schluessel, it's fine
                 note TEXT /* CHECK (note <> 'x') steht hier nur als Kommentar */,
                 amount REAL)""",
        ) { pool ->
            val table = reader.read(pool).schema.tables.getValue("c")
            // Weder der CHECK aus dem Kommentar noch ein durch das Apostroph
            // verschobenes Literal: die Tabelle traegt drei Spalten und nichts sonst.
            table.constraints.shouldHaveSize(0)
            table.columns.keys shouldBe setOf("id", "note", "amount")
            table.columns.getValue("amount").type shouldBe NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.DOUBLE)
        }
    }

    test("AUTOINCREMENT only in a comment is not an autoincrement") {
        withDb(
            """CREATE TABLE c (
                 id INTEGER PRIMARY KEY, -- kein AUTOINCREMENT, nur ein Wort
                 note TEXT)""",
        ) { pool ->
            val id = reader.read(pool).schema.tables.getValue("c").columns.getValue("id")
            // Ohne das echte Schluesselwort ist es eine gewoehnliche
            // INTEGER-PK-Spalte, kein `identifier(auto)`.
            (id.type as? NeutralType.Identifier)?.autoIncrement shouldBe null
            id.generation shouldBe null as ColumnGeneration?
        }
    }

    test("two reverses of the same database give the same names") {
        // Eine Datei statt `:memory:`: der Leser schliesst die Verbindung, die
        // er borgt — zwei Laeufe brauchen also zwei Verbindungen auf dieselbe
        // Datenbank, wie im Betrieb.
        val file = kotlin.io.path.createTempFile("sqlite-naming", ".db")
        try {
            val url = "jdbc:sqlite:${file.toAbsolutePath()}"
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE p (id INTEGER PRIMARY KEY, a TEXT, b TEXT, UNIQUE (a, b))")
                    stmt.execute(
                        "CREATE TABLE c (id INTEGER PRIMARY KEY, pid INTEGER REFERENCES p(id), " +
                            "x TEXT, y TEXT, UNIQUE (x, y))",
                    )
                }
            }
            fun names(): List<String> = DriverManager.getConnection(url).use { conn ->
                reader.read(pool(conn)).schema.tables.values.flatMap { table -> table.constraints.map { it.name } }
            }
            val first = names()
            first shouldBe names()
            first.toSet() shouldBe setOf("uq_p_a_b", "fk_c_pid", "uq_c_x_y")
        } finally {
            file.toFile().delete()
        }
    }
})
