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

    test("PK-implied required is suppressed in target-aware mode, non-PK required stays strict") {
        fun pkTable(required: Boolean) = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = required)),
            primaryKey = listOf("id"),
        ))
        SchemaComparator(sqliteLike).compare(pkTable(true), pkTable(false)).isEmpty() shouldBe true
        SchemaComparator().compare(pkTable(true), pkTable(false)).isEmpty() shouldBe false
        // Nicht-PK-Spalte: required bleibt auch target-aware ein Unterschied.
        SchemaComparator(sqliteLike)
            .compare(typed(NeutralType.Integer, required = true), typed(NeutralType.Integer, required = false))
            .isEmpty() shouldBe false
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
})
