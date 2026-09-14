package dev.dmigrate.core.diff

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Der Fall, den ein Konsumentenprojekt fährt: **zwei zurückgelesene Schemata**
 * verschiedener Dialekte gegeneinander vergleichen. Gemessen an seinem
 * Shop-Schema lag die Falsch-Positiv-Quote bei 38 % (PG↔MSSQL) — diese Spec
 * hält die Fälle fest, die dabei **keine** Änderung sein dürfen, und trennt
 * sie von denen, die es sehr wohl sind.
 *
 * Die Unterscheidung ist der Punkt: Ein Vergleich, der zwischen „der Server
 * schreibt es anders" und „das Schema ist anders" nicht trennt, ist für
 * Automatisierung unbrauchbar. Aber nicht jeder Fund ist ein Fehlalarm —
 * wer die mitzählt, überschätzt die Quote.
 *
 * Die Gegenstücke auf Reader-Ebene stehen in `SchemaReaderUtilsTest` (dort
 * wird `NO ACTION` auf `null` gefaltet); diese Spec prüft, was der Vergleich
 * daraus macht.
 */
class SchemaComparatorCrossDialectReverseTest : FunSpec({

    val comparator = SchemaComparator()

    fun fk(child: String, refTable: String, onDelete: ReferentialAction? = null, onUpdate: ReferentialAction? = null) =
        ConstraintDefinition(
            name = "${child}_fk", type = ConstraintType.FOREIGN_KEY, columns = listOf(child),
            references = ConstraintReferenceDefinition(table = refTable, columns = listOf("id"))
                .let { it.copy(onDelete = onDelete, onUpdate = onUpdate) },
        )

    fun table(
        columns: Map<String, ColumnDefinition>,
        pk: List<String> = listOf("id"),
        constraints: List<ConstraintDefinition> = emptyList(),
        metadata: TableMetadata? = null,
    ) = TableDefinition(columns = columns, primaryKey = pk, constraints = constraints, metadata = metadata)

    /**
     * Das Gerüst des gemessenen Schemas: eine Kind-Tabelle mit Fremdschlüssel
     * und eine `id`-Spalte als Identity — die zwei Stellen, an denen die
     * Dialekte am häufigsten auseinanderlaufen.
     */
    fun schema(
        fkOnDelete: ReferentialAction? = null,
        fkOnUpdate: ReferentialAction? = null,
        identityMode: IdentityMode = IdentityMode.BY_DEFAULT,
        engine: String? = null,
        name: String = "app",
    ) = SchemaDefinition(
        name = name, version = "0.0.0-reverse",
        tables = mapOf(
            "order_items" to table(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(
                        NeutralType.BigInteger, required = true,
                        generation = ColumnGeneration.Identity(mode = identityMode),
                    ),
                    "order_id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                ),
                constraints = listOf(fk("order_id", "orders", fkOnDelete, fkOnUpdate)),
                metadata = engine?.let { TableMetadata(engine = it) },
            ),
        ),
    )

    // ── Was KEINE Änderung sein darf ───────────────────────────────

    context("Falsch-Positive, die nicht auftreten dürfen") {

        /**
         * **Warum hier kein `NO ACTION` steht.** SQL Server liest es explizit
         * aus dem Katalog, PostgreSQL und MySQL lassen es weg — die Faltung
         * passiert aber schon beim **Lesen** (`SchemaReaderUtils`, siehe
         * `SchemaReaderUtilsTest`). Ein Reverse traegt danach auf beiden
         * Seiten `null`, und der Vergleich hat nichts zu melden. Setzte man
         * hier `NO_ACTION` von Hand, waere es sehr wohl ein Fund — das ist
         * aber kein Reverse mehr, sondern eine ausdrueckliche Angabe.
         *
         * Der Test haelt damit die **Kette** fest: zwei Reverses desselben
         * Fremdschluessels sind gleich. Faellt die Reader-Faltung weg, kommt
         * der Fund zurueck — nur eben eine Ebene frueher.
         */
        test("two reverses of the same foreign key are equal") {
            val pg = schema()
            val mssql = schema()

            comparator.compare(pg, mssql).tablesChanged.shouldBeEmpty()
        }

        /**
         * Der Grenzfall daneben: Wer die Aktion **ausdruecklich** hinschreibt,
         * bekommt sie auch gemeldet. Die Faltung nimmt nur die Server-
         * Schreibweise weg, nicht eine Nutzerentscheidung.
         */
        test("an explicitly spelled NO ACTION against an omitted one is a change") {
            val explicit = schema(fkOnDelete = ReferentialAction.NO_ACTION)

            comparator.compare(schema(), explicit).tablesChanged shouldHaveSize 1
        }

        test("a real foreign-key action difference is still detected") {
            val plain = schema()
            val cascading = schema(fkOnDelete = ReferentialAction.CASCADE)

            val diff = comparator.compare(plain, cascading)
            diff.tablesChanged shouldHaveSize 1
            diff.tablesChanged.single().constraintsChanged.shouldNotBeEmpty()
        }

        /**
         * Nur MySQL führt einen Storage-Engine; PostgreSQL kann keinen
         * setzen. Der Vergleich fragte dort nach etwas Unmögliches.
         */
        test("an engine on only one side is not a change") {
            comparator.compare(schema(), schema(engine = "InnoDB")).tablesChanged.shouldBeEmpty()
        }

        test("two different engines are still a change") {
            val diff = comparator.compare(schema(engine = "InnoDB"), schema(engine = "MyISAM"))
            diff.tablesChanged shouldHaveSize 1
        }
    }

    // ── Was eine Änderung IST ──────────────────────────────────────

    context("Funde, die korrekt sind und bleiben müssen") {

        /**
         * Der Identity-**Modus** ist beobachtbares Verhalten, nicht bloß
         * Speicher-Implementierung: `BY DEFAULT` erlaubt explizite Werte,
         * SQL Server kann das nur mit `SET IDENTITY_INSERT`. d-migrate
         * rendert ihn dort als `IDENTITY(1,1)` und warnt (`W140`) — der
         * Unterschied ist real und wird deshalb gemeldet.
         *
         * Anders als beim `engine` (reine Ablage) wird hier **nichts**
         * gefaltet: der Fund bleibt, auch wenn er cross-dialekt nicht
         * auflösbar ist.
         */
        test("an identity-mode difference is reported, not folded away") {
            val pg = schema(identityMode = IdentityMode.BY_DEFAULT)
            val mssql = schema(identityMode = IdentityMode.ALWAYS)

            val diff = comparator.compare(pg, mssql)
            diff.tablesChanged shouldHaveSize 1
            diff.tablesChanged.single().columnsChanged.single().generation.shouldNotBeNull()
        }

        /**
         * Der Gegenfall zum FK oben: Wenn die Gegenseite das Objekt wirklich
         * nicht hat — hier fehlt der Fremdschlüssel ganz —, ist der Fund die
         * Wahrheit. Genau diese Klasse hat der Konsument mitgezählt, als er
         * 38 % Falsch-Positive gemessen hat; im gemessenen Schema fehlten
         * Constraint und berechnete Spalte auf der MSSQL-Seite tatsächlich,
         * weil die Generierung sie per E057/E053 übersprungen hatte.
         */
        test("a genuinely missing constraint is reported") {
            val both = schema().let { s ->
                s.copy(tables = mapOf("order_items" to s.tables.getValue("order_items").copy(constraints = emptyList())))
            }
            val withFk = schema()

            val diff = comparator.compare(both, withFk)
            diff.tablesChanged shouldHaveSize 1
            diff.tablesChanged.single().constraintsAdded shouldHaveSize 1
        }
    }

    // ── Die Summe ──────────────────────────────────────────────────

    /**
     * Der Fall in einem Stück: zwei Reverses desselben Schemas aus zwei
     * Dialekten. Übrig bleibt **genau ein** Fund — der Identity-Modus, der
     * wirklich verschieden ist. Der Engine ist dagegen Schreibweise.
     *
     * Dieser Test bricht, wenn die Engine-Faltung zurückgenommen wird: dann
     * kommt der Metadaten-Fund zurück und die Zahl steigt.
     */
    test("two reverses of the same schema differ in exactly one reported way") {
        val pg = schema(identityMode = IdentityMode.BY_DEFAULT)
        // `fkOnDelete` bleibt bewusst weg: nach dem Reader traegt kein
        // Reverse ein `NO ACTION` mehr.
        val mssql = schema(identityMode = IdentityMode.ALWAYS, engine = "InnoDB")

        val diff = comparator.compare(pg, mssql)
        diff.tablesChanged shouldHaveSize 1
        val only = diff.tablesChanged.single()
        only.columnsChanged.single().name shouldBe "id"
        only.columnsChanged.single().generation.shouldNotBeNull()
        only.constraintsChanged.shouldBeEmpty()
        only.metadata shouldBe null
    }
})
