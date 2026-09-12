package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Nagelt den ziel-bewussten Vergleichsmodus fest: Unterschiede, die der
 * ZIEL-Dialekt nicht ausdruecken und sein Reverse nicht zurueckmelden kann,
 * werden unterdrueckt, damit der Migrationsplan KONVERGIERT — waehrend der
 * strikte Default (`schema compare`) sie weiterhin meldet.
 */
class SchemaComparatorTargetAwareTest : FunSpec({

    val sqliteLike = TargetProjection(
        type = { t -> if (t == NeutralType.SmallInt) NeutralType.Integer else t },
    )

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "App", version = "1", tables = mapOf("t" to table),
    )

    fun typed(t: NeutralType, required: Boolean = false) = schemaWith(
        TableDefinition(columns = mapOf("val" to ColumnDefinition(t, required = required))),
    )

    test("target-folded type difference is suppressed in target-aware mode, kept strict by default") {
        val current = typed(NeutralType.Integer)
        val desired = typed(NeutralType.SmallInt)
        SchemaComparator(sqliteLike).compare(current, desired).isEmpty() shouldBe true
        SchemaComparator().compare(current, desired).isEmpty() shouldBe false
    }

    test("a genuinely different type stays a difference in target-aware mode") {
        val current = typed(NeutralType.Integer)
        val desired = typed(NeutralType.Text())
        SchemaComparator(sqliteLike).compare(current, desired).isEmpty() shouldBe false
    }

    test("PK-implied required is suppressed in BOTH modes, non-PK required stays a difference") {
        fun pkTable(required: Boolean) = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = required)),
            primaryKey = listOf("id"),
        ))
        // Nicht nur ziel-bewusst: eine PK-Spalte ist auf keinem Dialekt
        // nullable, und kein Ziel koennte den Unterschied herstellen oder
        // aufloesen. Strikt zu melden, was es nirgends gibt, war der Befund
        // aus dem Release-Smoke (`required: false -> true` gegen eine aus
        // derselben Fixture erzeugte Datenbank).
        SchemaComparator(sqliteLike).compare(pkTable(true), pkTable(false)).isEmpty() shouldBe true
        SchemaComparator().compare(pkTable(true), pkTable(false)).isEmpty() shouldBe true
        // Nicht-PK-Spalte: required bleibt in beiden Modi ein Unterschied.
        SchemaComparator(sqliteLike)
            .compare(typed(NeutralType.Integer, required = true), typed(NeutralType.Integer, required = false))
            .isEmpty() shouldBe false
        SchemaComparator()
            .compare(typed(NeutralType.Integer, required = true), typed(NeutralType.Integer, required = false))
            .isEmpty() shouldBe false
    }

    test("an identifier column without an explicit PK folds required the same way") {
        // Der effektive PK leitet sich aus genau einer `identifier`-Spalte ab
        // (v3-Regel) — und genau die rendert jeder Dialekt als PRIMARY KEY.
        fun t(required: Boolean) = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = required)),
        ))
        SchemaComparator().compare(t(true), t(false)).isEmpty() shouldBe true
    }

    test("implicit identifier PK equals explicit PK in target-aware mode (effective PK)") {
        val implicit = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
        ))
        val explicit = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
            primaryKey = listOf("id"),
        ))
        SchemaComparator(sqliteLike).compare(implicit, explicit).isEmpty() shouldBe true
        SchemaComparator().compare(implicit, explicit).isEmpty() shouldBe false
    }

    // ── Index- und Erzeugungs-Projektion ──────────────────────────
    //
    // Der Fingerabdruck blendete diese Felder laengst aus, der Vergleich
    // nicht. Die Folge war die schlechtere Haelfte des Fehlers: der
    // Post-Compare meldete KEINE Drift, und der naechste Lauf plante
    // dieselbe Aenderung trotzdem erneut.

    /** Ein Ziel, das die Text-Search-Konfiguration nicht zurueckmelden kann. */
    val dropsTextSearchConfig = TargetProjection(
        type = { it },
        index = { it.copy(textSearchConfig = null) },
    )

    fun withFullText(config: String?) = schemaWith(
        TableDefinition(
            columns = mapOf("body" to ColumnDefinition(NeutralType.Text())),
            indices = listOf(
                IndexDefinition(
                    name = "ft_body",
                    columns = listOf(IndexColumn("body")),
                    type = IndexType.FULLTEXT,
                    textSearchConfig = config,
                ),
            ),
        ),
    )

    test("a text-search config the target cannot return is no difference in target-aware mode") {
        val current = withFullText(null)
        val desired = withFullText("english")
        SchemaComparator(dropsTextSearchConfig).compare(current, desired).isEmpty() shouldBe true
        // `schema compare` zeigt sie weiterhin.
        SchemaComparator().compare(current, desired).isEmpty() shouldBe false
    }

    test("a target that DOES carry the config keeps a changed config a difference") {
        // Sonst verschwiege der ziel-bewusste Modus eine echte Aenderung.
        SchemaComparator(sqliteLike).compare(withFullText("german"), withFullText("english"))
            .isEmpty() shouldBe false
    }

    /** Ein Ziel, das den system-vergebenen Sequenznamen nicht tragen kann. */
    val dropsIdentitySequenceName = TargetProjection(
        type = { it },
        generation = { g -> (g as? ColumnGeneration.Identity)?.copy(sequenceName = null) ?: g },
    )

    fun identityColumn(sequenceName: String?) = schemaWith(
        TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    NeutralType.BigInteger,
                    generation = ColumnGeneration.Identity(
                        mode = IdentityMode.BY_DEFAULT,
                        sequenceName = sequenceName,
                    ),
                ),
            ),
        ),
    )

    test("a system-assigned identity sequence name is no difference in target-aware mode") {
        val current = identityColumn("public.orders_id_seq")
        val desired = identityColumn(null)
        SchemaComparator(dropsIdentitySequenceName).compare(current, desired).isEmpty() shouldBe true
        SchemaComparator().compare(current, desired).isEmpty() shouldBe false
    }

    test("the generation projection may return null without falling back to the raw value") {
        // Ein `?:`-Fallback auf den Eingabewert machte aus der ausgeblendeten
        // Angabe wieder die urspruengliche -- die Projektion waere wirkungslos.
        val blanking = TargetProjection(type = { it }, generation = { null })
        SchemaComparator(blanking)
            .compare(identityColumn("public.orders_id_seq"), identityColumn(null))
            .isEmpty() shouldBe true
    }

    /** Ein Ziel, das Modulus und Remainder einer HASH-Partition nicht fuehrt. */
    val dropsHashModulus = TargetProjection(
        type = { it },
        partitioning = { cfg ->
            cfg.copy(partitions = cfg.partitions.map { it.copy(modulus = null, remainder = null) })
        },
    )

    fun hashPartitioned(modulus: Int?) = schemaWith(
        TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            partitioning = PartitionConfig(
                type = PartitionType.HASH,
                key = listOf("id"),
                partitions = listOf(
                    PartitionDefinition(name = "p0", modulus = modulus, remainder = if (modulus == null) null else 0),
                ),
            ),
        ),
    )

    test("a hash modulus the target does not keep is no difference in target-aware mode") {
        SchemaComparator(dropsHashModulus).compare(hashPartitioned(null), hashPartitioned(4))
            .isEmpty() shouldBe true
        SchemaComparator().compare(hashPartitioned(null), hashPartitioned(4)).isEmpty() shouldBe false
    }

    /** Ein Ziel, das Volltext-Indizes nicht benennt — sein Reverse erfindet den Namen. */
    val synthesizesFullTextName = TargetProjection(
        type = { it },
        index = { if (it.type == IndexType.FULLTEXT) it.copy(name = null) else it },
    )

    fun fullTextNamed(name: String) = schemaWith(
        TableDefinition(
            columns = mapOf("body" to ColumnDefinition(NeutralType.Text())),
            indices = listOf(
                IndexDefinition(name = name, columns = listOf(IndexColumn("body")), type = IndexType.FULLTEXT),
            ),
        ),
    )

    test("a synthesized full-text index name does not become a drop-and-add") {
        // Der Schluessel entscheidet ueber die ZUORDNUNG, vor jedem
        // Feldvergleich. Ungefaltet gebildet waeren die Schluesselmengen
        // disjunkt, und aus einem unveraenderten Index wuerden zwei
        // Operationen -- bei jedem Lauf erneut.
        SchemaComparator(synthesizesFullTextName)
            .compare(fullTextNamed("ft_papers"), fullTextNamed("fx_papers"))
            .isEmpty() shouldBe true
        SchemaComparator().compare(fullTextNamed("ft_papers"), fullTextNamed("fx_papers"))
            .isEmpty() shouldBe false
    }

    test("two indexes the target cannot tell apart both stay in the comparison") {
        // Faellt der zweite auf denselben gefalteten Schluessel, darf er nicht
        // stillschweigend aus dem Vergleich verschwinden.
        val two = schemaWith(
            TableDefinition(
                columns = mapOf("body" to ColumnDefinition(NeutralType.Text())),
                indices = listOf(
                    IndexDefinition(name = "ft_a", columns = listOf(IndexColumn("body")), type = IndexType.FULLTEXT),
                    IndexDefinition(name = "ft_b", columns = listOf(IndexColumn("body")), type = IndexType.FULLTEXT),
                ),
            ),
        )
        val one = fullTextNamed("ft_a")
        SchemaComparator(synthesizesFullTextName).compare(one, two).isEmpty() shouldBe false
    }

    test("a changed identity mode stays a difference even where the sequence name is projected away") {
        fun identityMode(mode: IdentityMode) = schemaWith(
            TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(
                        NeutralType.BigInteger,
                        generation = ColumnGeneration.Identity(mode = mode, sequenceName = "public.s"),
                    ),
                ),
            ),
        )
        SchemaComparator(dropsIdentitySequenceName)
            .compare(identityMode(IdentityMode.ALWAYS), identityMode(IdentityMode.BY_DEFAULT))
            .isEmpty() shouldBe false
    }

    test("a changed range bound stays a difference even where the hash modulus is projected away") {
        fun ranged(upper: String) = schemaWith(
            TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                partitioning = PartitionConfig(
                    type = PartitionType.RANGE,
                    key = listOf("id"),
                    partitions = listOf(
                        PartitionDefinition(name = "p0", to = listOf(PartitionBound.Value(upper))),
                    ),
                ),
            ),
        )
        SchemaComparator(dropsHashModulus).compare(ranged("100"), ranged("200")).isEmpty() shouldBe false
    }

    // ── Zwei Schreibweisen fuer dieselbe Autowert-Spalte ─────────
    //
    // Der Reverse legt eine IDENTITY-Spalte als numerischen Typ plus
    // `generation` ab; ein handgeschriebenes Soll benutzt meist
    // `identifier` + `auto_increment`. Wo der Dialekt beide zum selben DDL
    // rendert, ist das kein Unterschied — sonst plant jeder Lauf dieselbe
    // Aenderung an einer unveraenderten Spalte, und auf Oracle endet sie mit
    // einem Blocker, weil sich eine Spalte dort nicht nachtraeglich zur
    // Identity-Spalte machen laesst.

    fun identityTable(column: ColumnDefinition) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(columns = mapOf("id" to column), primaryKey = listOf("id")),
        ),
    )

    val autoIncrementSpelling = ColumnDefinition(NeutralType.Identifier(autoIncrement = true))
    val generationSpelling = ColumnDefinition(
        NeutralType.Integer,
        generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
    )

    test("where the dialect renders both spellings the same, they compare equal") {
        val projection = TargetProjection(
            type = { if (it is NeutralType.Identifier) NeutralType.Integer else it },
            foldsAutoIncrementOntoIdentity = true,
        )

        SchemaComparator(projection).compare(
            identityTable(autoIncrementSpelling), identityTable(generationSpelling),
        ).isEmpty() shouldBe true
    }

    test("where it renders them differently, the difference stays a difference") {
        // PostgreSQL: `SERIAL` ist eine Sequenz mit Default, `IDENTITY` etwas
        // anderes -- gemessen an den Generatoren.
        val projection = TargetProjection(
            type = { if (it is NeutralType.Identifier) NeutralType.Integer else it },
            foldsAutoIncrementOntoIdentity = false,
        )

        SchemaComparator(projection).compare(
            identityTable(autoIncrementSpelling), identityTable(generationSpelling),
        ).isEmpty() shouldBe false
    }

    test("a real mode change is still planned, in either spelling") {
        val projection = TargetProjection(
            type = { if (it is NeutralType.Identifier) NeutralType.Integer else it },
            foldsAutoIncrementOntoIdentity = true,
        )
        val byDefault = ColumnDefinition(
            NeutralType.Integer,
            generation = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT),
        )

        // Nennen beide Seiten einen Modus, faellt der Faltungszweig nicht an.
        SchemaComparator(projection).compare(
            identityTable(generationSpelling), identityTable(byDefault),
        ).isEmpty() shouldBe false
    }

    test("a column that is neither spelling is untouched by the fold") {
        val projection = TargetProjection(
            type = { it },
            foldsAutoIncrementOntoIdentity = true,
        )
        val plain = ColumnDefinition(NeutralType.Integer)

        SchemaComparator(projection).compare(
            identityTable(plain), identityTable(generationSpelling),
        ).isEmpty() shouldBe false
    }
})
