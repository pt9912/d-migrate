package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 5a-2: der Tabellen-Neubau fuer IDENTITY.
 *
 * IDENTITY ist die eine Eigenschaft, die `ALTER TABLE … ALTER COLUMN` in
 * T-SQL weder setzen noch entfernen noch neu deklarieren kann (Msg 156). Wer
 * sie aendert, baut die Tabelle neu — und traegt dabei zwei Lasten, die den
 * Sub-Slice ausmachen:
 *
 * 1. **Die Zwischentabelle darf keine benannten Objekte tragen.** SQL Server
 *    fuehrt Constraints schema-global; `pk_users`, `df_users_nick` &c. sind
 *    vergeben, solange die alte Tabelle lebt (Msg 2714).
 * 2. **Die Schluesselwerte und der Zaehler muessen ueberleben.** Deshalb
 *    `SET IDENTITY_INSERT` um die Kopie.
 */
class MssqlRebuildRendererTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

    fun up(
        diff: SchemaDiff,
        current: SchemaDefinition,
        desired: SchemaDefinition,
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    fun down(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    /** `id` wird von einer gewoehnlichen Spalte zur IDENTITY-Spalte. */
    fun identityAddedDiff() = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "users",
                columnsChanged = listOf(
                    ColumnDiff(
                        name = "id",
                        type = ValueChange(NeutralType.Integer, NeutralType.Identifier(autoIncrement = true)),
                    ),
                ),
            ),
        ),
    )

    fun users(idType: NeutralType, vararg extra: Pair<String, ColumnDefinition>) = TableDefinition(
        columns = linkedMapOf("id" to ColumnDefinition(idType, required = true), *extra),
        primaryKey = listOf("id"),
    )

    test("the sequence is create, copy, drop, rename — in that order") {
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)))
        val sqls = up(identityAddedDiff(), current, desired).statements.map { it.sql }

        val temp = Regex("""users__dmg_rebuild_[0-9a-f]{8}""").find(sqls.joinToString("\n"))?.value
        temp.shouldNotBeNull()
        val create = sqls.indexOfFirst { it.startsWith("CREATE TABLE [$temp]") }
        val copy = sqls.indexOfFirst { it.contains("INSERT INTO [$temp]") }
        val drop = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val rename = sqls.indexOfFirst { it.contains("sp_rename") }
        listOf(create, copy, drop, rename).none { it < 0 } shouldBe true
        (create < copy && copy < drop && drop < rename) shouldBe true
    }

    test("the copy preserves the key values: SET IDENTITY_INSERT wraps it in one statement") {
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)))
        val copy = up(identityAddedDiff(), current, desired).statements
            .map { it.sql }
            .single { it.contains("INSERT INTO") }

        // Beides im SELBEN Statement: der Schalter ist sitzungsweit, ein
        // abgebrochener Lauf duerfte ihn nicht offen lassen.
        copy shouldContainStr "SET IDENTITY_INSERT"
        copy.substringBefore("INSERT INTO") shouldContainStr " ON;"
        copy.substringAfter("FROM [users];") shouldContainStr " OFF;"
        copy shouldContainStr "SELECT [id] FROM [users];"
    }

    test("the intermediate table carries no named constraint — they arrive after the rename") {
        val current = schema("users" to users(NeutralType.Integer, "nick" to ColumnDefinition(NeutralType.Text(50))))
        val desired = schema(
            "users" to users(
                NeutralType.Identifier(autoIncrement = true),
                "nick" to ColumnDefinition(
                    NeutralType.Text(50),
                    unique = true,
                    default = DefaultValue.StringLiteral("anon"),
                ),
            ),
        )
        val sqls = up(identityAddedDiff(), current, desired).statements.map { it.sql }
        val create = sqls.single { it.startsWith("CREATE TABLE [users__dmg_rebuild_") }
        // Msg 2714: solange [users] lebt, sind df_users_nick und pk_users vergeben.
        create.contains("CONSTRAINT") shouldBe false

        val rename = sqls.indexOfFirst { it.contains("sp_rename") }
        val objects = sqls.drop(rename + 1)
        // ... und danach unter ihren ENDGUELTIGEN Namen, nicht unter denen der
        // Zwischentabelle: sonst bliebe der Neubau fuer immer sichtbar.
        objects shouldContainAll listOf(
            "ALTER TABLE [users] ADD CONSTRAINT [pk_users] PRIMARY KEY ([id]);",
            "ALTER TABLE [users] ADD CONSTRAINT [df_users_nick] DEFAULT N'anon' FOR [nick];",
            "ALTER TABLE [users] ADD CONSTRAINT [uq_users_nick] UNIQUE ([nick]);",
        )
        objects.none { it.contains("dmg_rebuild") } shouldBe true
    }

    test("a column added by the same plan is filled from its default, not left to a constraint") {
        // Der Default-Constraint existiert waehrend der Kopie noch nicht — die
        // Zwischentabelle ist nackt. Der Wert muss also im SELECT stehen.
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema(
            "users" to users(
                NeutralType.Identifier(autoIncrement = true),
                "role" to ColumnDefinition(NeutralType.Text(20), required = true, default = DefaultValue.StringLiteral("user")),
                "note" to ColumnDefinition(NeutralType.Text(20)),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf(
                        "role" to ColumnDefinition(NeutralType.Text(20), required = true, default = DefaultValue.StringLiteral("user")),
                        "note" to ColumnDefinition(NeutralType.Text(20)),
                    ),
                    columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged,
                ),
            ),
        )
        val copy = up(diff, current, desired).statements.map { it.sql }.single { it.contains("INSERT INTO") }
        copy shouldContainStr "SELECT [id], N'user', NULL FROM [users];"
    }

    test("a new NOT NULL column without a default blocks the rebuild instead of inventing a value") {
        val current = schema("users" to users(NeutralType.Integer))
        val newCol = ColumnDefinition(NeutralType.Text(20), required = true)
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true), "role" to newCol))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("role" to newCol),
                    columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged,
                ),
            ),
        )
        val r = up(diff, current, desired)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_REBUILD_COLUMN_NOT_FILLABLE"
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("a brand-new IDENTITY column stays out of the copy: SQL Server assigns it") {
        val current = schema("users" to TableDefinition(columns = linkedMapOf("nick" to ColumnDefinition(NeutralType.Text(50)))))
        val idCol = ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true)
        val desired = schema(
            "users" to TableDefinition(
                columns = linkedMapOf("nick" to ColumnDefinition(NeutralType.Text(50)), "id" to idCol),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", columnsAdded = mapOf("id" to idCol))),
        )
        // Ohne Typaenderung an einer IDENTITY-Spalte ist das KEIN Neubau: eine
        // neue IDENTITY-Spalte laesst sich per ALTER TABLE … ADD anlegen.
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.none { it.contains("dmg_rebuild") } shouldBe true
        sqls.single() shouldContainStr "ALTER TABLE [users] ADD [id] INT IDENTITY(1,1) NOT NULL"
    }

    test("an index the same plan adds is rendered once — by the rebuild, not twice") {
        val idx = IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val current = schema("users" to users(NeutralType.Integer, "nick" to ColumnDefinition(NeutralType.Text(50))))
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true), "nick" to ColumnDefinition(NeutralType.Text(50)))
                .copy(indices = listOf(idx)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged,
                    indicesAdded = listOf(idx),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        // Msg 1913: ein zweites CREATE INDEX auf denselben Namen scheitert,
        // T-SQL kennt kein IF NOT EXISTS fuer Indizes.
        sqls.count { it.contains("CREATE INDEX [ix_nick]") } shouldBe 1
    }

    test("inbound foreign keys are dropped before the DROP TABLE and re-created after the rename") {
        val fk = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val orders = TableDefinition(
            columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(fk),
        )
        val current = schema("users" to users(NeutralType.Integer), "orders" to orders)
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "orders" to orders)
        val sqls = up(identityAddedDiff(), current, desired).statements.map { it.sql }

        val dropFk = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user]") }
        val dropTable = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val addFk = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [fk_orders_user]") }
        // Ohne den ersten Schritt lehnt SQL Server das DROP TABLE ab (Msg 3726).
        (dropFk in 0 until dropTable) shouldBe true
        (addFk > dropTable) shouldBe true
    }

    test("a foreign key the same plan adds is left to its own operation, not created twice") {
        // Der Neubau darf nur wiederherstellen, was er auch abgeraeumt hat.
        // Einen erst im Zielschema stehenden FK fuegt eine eigene
        // AddConstraint-Operation hinzu — sie gehoert einer ANDEREN Tabelle und
        // wird deshalb nicht absorbiert. Beides zusammen waere Msg 2714.
        val fk = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val ordersBefore = TableDefinition(columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)))
        val current = schema("users" to users(NeutralType.Integer), "orders" to ordersBefore)
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true)),
            "orders" to ordersBefore.copy(constraints = listOf(fk)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged,
                ),
                TableDiff(name = "orders", constraintsAdded = listOf(fk)),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.count { it.contains("ADD CONSTRAINT [fk_orders_user]") } shouldBe 1
    }

    test("a column-level reference on a child table is dropped too — DROP TABLE would be Msg 3726") {
        // `references` an der Spalte ist im Modell kein Constraint, in der
        // Datenbank aber einer: der Generate-Pfad legt fk_<kind>_<spalte> an.
        val orders = TableDefinition(
            columns = linkedMapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val current = schema("users" to users(NeutralType.Integer), "orders" to orders)
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "orders" to orders)
        val sqls = up(identityAddedDiff(), current, desired).statements.map { it.sql }

        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user_id]") }
        val dropTable = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val restore = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [fk_orders_user_id]") }
        (drop in 0 until dropTable) shouldBe true
        (restore > dropTable) shouldBe true
    }

    test("a foreign key an earlier step of the same plan created is dropped as well, then restored once") {
        // Eine neue Kindtabelle entsteht in einer FRUEHEREN Phase und bringt
        // ihren Fremdschluessel gleich mit. Steht er beim `DROP TABLE` noch,
        // ist das Msg 3726 — nur das Ist-Schema zu betrachten reicht nicht.
        val fk = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val orders = TableDefinition(
            columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(fk),
        )
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "orders" to orders)
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("orders", orders)),
            tablesChanged = identityAddedDiff().tablesChanged,
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }

        val dropTable = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user]") }
        (drop in 0 until dropTable) shouldBe true
        // Die Kindtabelle koennte es zu diesem Zeitpunkt auch NICHT geben —
        // der Abraeumer muss das vertragen.
        sqls[drop] shouldContainStr "IF OBJECT_ID('orders', 'U') IS NOT NULL"
        // Und wiederhergestellt wird er genau einmal.
        sqls.count { it.contains("ADD CONSTRAINT [fk_orders_user]") } shouldBe 1
    }

    test("a foreign key whose column arrives LATER is created by that step, never before it") {
        // Den Fremdschluessel beim Neubau anzulegen waere Msg 1911 — die Spalte
        // gaebe es noch nicht. Anlegen muss ihn die Operation, die die Spalte
        // bringt, und genau einmal.
        val zorders = TableDefinition(columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10))))
        val newCol = ColumnDefinition(
            NeutralType.Integer,
            references = ReferenceDefinition(table = "users", column = "id"),
        )
        val current = schema("users" to users(NeutralType.Integer), "zorders" to zorders)
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true)),
            "zorders" to zorders.copy(
                columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10)), "user_id" to newCol),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged),
                TableDiff(name = "zorders", columnsAdded = mapOf("user_id" to newCol)),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.count { it.contains("ADD CONSTRAINT [fk_zorders_user_id]") } shouldBe 1
        val addColumn = sqls.indexOfFirst { it.contains("ALTER TABLE [zorders] ADD [user_id]") }
        val addFk = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [fk_zorders_user_id]") }
        (addColumn in 0 until addFk) shouldBe true
    }

    test("a foreign key an EARLIER step created is dropped by the rebuild and put back") {
        // Spiegelfall: die Kindspalte entsteht vor dem Neubau (`aorders` sortiert
        // vor `users`), ihr Fremdschluessel steht beim `DROP TABLE` also schon —
        // Msg 3726, wenn der Neubau ihn nicht abraeumt, stiller Verlust, wenn er
        // ihn nicht zurueckbringt.
        val aorders = TableDefinition(columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10))))
        val newCol = ColumnDefinition(
            NeutralType.Integer,
            references = ReferenceDefinition(table = "users", column = "id"),
        )
        val current = schema("users" to users(NeutralType.Integer), "aorders" to aorders)
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true)),
            "aorders" to aorders.copy(
                columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10)), "user_id" to newCol),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "aorders", columnsAdded = mapOf("user_id" to newCol)),
                TableDiff(name = "users", columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        val dropTable = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_aorders_user_id]") }
        (drop in 0 until dropTable) shouldBe true
        // Angelegt wird er zweimal — einmal von der Spalte, einmal vom Neubau,
        // der ihn dazwischen abgeraeumt hat. Nacheinander, nicht doppelt.
        sqls.count { it.contains("ADD CONSTRAINT [fk_aorders_user_id]") } shouldBe 2
        (sqls.indexOfLast { it.contains("ADD CONSTRAINT [fk_aorders_user_id]") } > dropTable) shouldBe true
    }

    test("down restores a foreign key whose DropConstraint the rebuild absorbed") {
        // Aufwaerts entfaellt der Fremdschluessel, abwaerts muss er zurueck.
        // Seine eigene Operation ist im Eimer und rendert ihre Umkehr nicht
        // selbst — tut es der Neubau auch nicht, ist er still verloren.
        val fk = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val orders = TableDefinition(columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)))
        val current = schema("users" to users(NeutralType.Integer), "orders" to orders.copy(constraints = listOf(fk)))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "orders" to orders)
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged),
                TableDiff(name = "orders", constraintsRemoved = listOf(fk)),
            ),
        )
        val sqls = down(diff, current, desired).statements.map { it.sql }
        sqls.count { it.contains("ADD CONSTRAINT [fk_orders_user]") } shouldBe 1
    }

    test("a child carrying the same foreign key in both model forms yields one statement, not two") {
        // `references` an der Spalte UND ein gleichnamiger Eintrag in
        // `constraints` beschreiben dasselbe Objekt — zweimal angelegt waere
        // es Msg 2714.
        val orders = TableDefinition(
            columns = linkedMapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_orders_user_id",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
            ),
        )
        val current = schema("users" to users(NeutralType.Integer), "orders" to orders)
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "orders" to orders)
        val sqls = up(identityAddedDiff(), current, desired).statements.map { it.sql }
        sqls.count { it.contains("ADD CONSTRAINT [fk_orders_user_id]") } shouldBe 1
        sqls.count { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user_id]") } shouldBe 1
    }

    test("an operation that was BLOCKED does not count as having created anything") {
        // Die Kindtabelle ist partitioniert, ihr CREATE TABLE blockt (Slice 7).
        // Wer sie trotzdem als angelegt zaehlt, schickt ein ADD CONSTRAINT
        // gegen eine Tabelle, die es nicht gibt.
        val fk = ConstraintDefinition(
            name = "fk_aorders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val aorders = TableDefinition(
            columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(fk),
            partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("user_id")),
        )
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)), "aorders" to aorders)
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("aorders", aorders)),
            tablesChanged = identityAddedDiff().tablesChanged,
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.none { it.contains("[fk_aorders_user]") } shouldBe true
    }

    test("a foreign key an EARLIER rebuild created is visible to the next one") {
        // Zwei Neubauten: `aorders` bekommt seinen Fremdschluessel auf `users`
        // von seinem eigenen Eimer. Sieht der Neubau von `users` ihn nicht,
        // steht er beim DROP TABLE noch — Msg 3726.
        //
        // Ein Eimer laeuft an der Stelle seiner LETZTEN Operation. Der Index
        // auf `users` (Phase INDEXES) schiebt dessen Eimer hinter den von
        // `aorders` (letzte Operation in CONSTRAINTS) — genau die Reihenfolge,
        // um die es hier geht.
        val fk = ConstraintDefinition(
            name = "fk_aorders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val aordersBefore = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "user_id" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
        )
        val aordersAfter = aordersBefore.copy(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
                "user_id" to ColumnDefinition(NeutralType.Integer),
            ),
            constraints = listOf(fk),
        )
        val idx = IndexDefinition(name = "ix_users_id", columns = listOf(IndexColumn("id")), type = IndexType.BTREE)
        val current = schema("users" to users(NeutralType.Integer), "aorders" to aordersBefore)
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true)).copy(indices = listOf(idx)),
            "aorders" to aordersAfter,
        )
        val idChange = identityAddedDiff().tablesChanged.single().columnsChanged
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "aorders", columnsChanged = idChange, constraintsAdded = listOf(fk)),
                TableDiff(name = "users", columnsChanged = idChange, indicesAdded = listOf(idx)),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        val dropUsers = sqls.indexOfFirst { it == "DROP TABLE [users];" }
        val dropFk = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_aorders_user]") }
        (dropFk in 0 until dropUsers) shouldBe true
    }

    test("an unrenderable object leaves no absorbed operation out of the bookkeeping") {
        val idx = IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val exclude = ConstraintDefinition(name = "ex_users", type = ConstraintType.EXCLUDE, expression = "x WITH =")
        val current = schema("users" to users(NeutralType.Integer, "nick" to ColumnDefinition(NeutralType.Text(50))))
        val desired = schema(
            "users" to users(NeutralType.Identifier(autoIncrement = true), "nick" to ColumnDefinition(NeutralType.Text(50)))
                .copy(indices = listOf(idx), constraints = listOf(exclude)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged,
                    indicesAdded = listOf(idx),
                    constraintsAdded = listOf(exclude),
                ),
            ),
        )
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        // Weder gerendert noch uebersprungen waere ein spurloses Verschwinden.
        (r.operationsRendered + r.operationsSkipped) shouldBe plan.operations.map { it.id }.toSet()
    }

    test("a nullability change on a `generation`-declared identity column is blocked, not rendered") {
        // Ohne diese Wache liefe `ALTER COLUMN [id] INT NULL` gegen Msg 4928 —
        // die Identity steht hier in `generation`, nicht im Typ.
        val identity = ColumnDefinition(NeutralType.Integer, required = true, generation = ColumnGeneration.Identity())
        val current = schema("users" to TableDefinition(columns = linkedMapOf("id" to identity)))
        val desired = schema(
            "users" to TableDefinition(columns = linkedMapOf("id" to identity.copy(required = false))),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "id", required = ValueChange(true, false))),
                ),
            ),
        )
        val r = up(diff, current, desired)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_IDENTITY_COLUMN_NOT_NULLABLE"
    }

    test("identity declared through `generation` triggers the rebuild just as the type does") {
        // Sonst liefe ein ALTER COLUMN gegen Msg 156 — die Identity steht hier
        // nicht im Typ, sondern in `generation`.
        val before = ColumnDefinition(NeutralType.Integer, required = true, generation = ColumnGeneration.Identity())
        val after = ColumnDefinition(NeutralType.BigInteger, required = true, generation = ColumnGeneration.Identity())
        val current = schema("users" to TableDefinition(columns = linkedMapOf("id" to before)))
        val desired = schema("users" to TableDefinition(columns = linkedMapOf("id" to after)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "id", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger)),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.none { it.contains("ALTER COLUMN") } shouldBe true
        sqls.any { it.contains("BIGINT IDENTITY(1,1)") } shouldBe true
    }

    test("the rebuild is destructive, rewrites the table and counts every absorbed operation") {
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)))
        val plan = planner.plan(current, desired, identityAddedDiff())
        val r = gen.generateUp(plan, DdlGenerationOptions())

        r.operationsRendered shouldBe plan.operations.map { it.id }.toSet()
        r.operationsSkipped.shouldBeEmpty()
        r.requiresConfirmation shouldBe true
        r.statements.all { it.risk.requiresTableRewrite && it.risk.destructive } shouldBe true
        // Der Migrate-Report zaehlt `manualActions`, nicht das Risiko der
        // Statements — sonst meldete ein Neubau null manuelle Schritte.
        r.manualActions shouldBe plan.operations.map { it.id }.toSet()
    }

    test("--strict-gap-operations blocks the rebuild: the table is briefly absent") {
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)))
        val r = up(identityAddedDiff(), current, desired, DdlGenerationOptions(strictGapOperations = true))
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "OPERATION_HAS_GAP_STRICT_BLOCKED"
    }

    test("down rebuilds back to the state without IDENTITY") {
        val current = schema("users" to users(NeutralType.Integer))
        val desired = schema("users" to users(NeutralType.Identifier(autoIncrement = true)))
        val sqls = down(identityAddedDiff(), current, desired).statements.map { it.sql }
        val create = sqls.single { it.startsWith("CREATE TABLE [users__dmg_rebuild_") }
        create.contains("IDENTITY") shouldBe false
        // Ohne IDENTITY-Zielspalte braucht die Kopie keinen Schalter.
        sqls.none { it.contains("SET IDENTITY_INSERT") } shouldBe true
    }

    test("the intermediate table name stays inside SQL Server's 128-character limit") {
        val long = "t".repeat(200)
        val current = schema(long to users(NeutralType.Integer))
        val desired = schema(long to users(NeutralType.Identifier(autoIncrement = true)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = long, columnsChanged = identityAddedDiff().tablesChanged.single().columnsChanged),
            ),
        )
        val create = up(diff, current, desired).statements.map { it.sql }.first { it.startsWith("CREATE TABLE") }
        val name = create.substringAfter("CREATE TABLE [").substringBefore("]")
        name.length shouldBeLessThanOrEqual 128
        name shouldContainStr "__dmg_rebuild_"
    }
})
