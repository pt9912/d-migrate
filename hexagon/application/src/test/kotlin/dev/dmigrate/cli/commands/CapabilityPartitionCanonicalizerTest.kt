package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Der Fingerabdruck-Pfad sieht die Partitionierung durch die Brille des
 * Ziel-Dialekts. Was ein Server nicht ablegt, kann sein Reverse nicht
 * zurueckmelden — ohne die Projektion meldete der Post-Compare nach jedem
 * `migrate --execute` Drift fuer eine Migration, die genau das getan hat, was
 * verlangt war.
 */
class CapabilityPartitionCanonicalizerTest : FunSpec({

    val ranged = PartitionConfig(
        type = PartitionType.RANGE,
        key = listOf("d"),
        partitions = listOf(
            PartitionDefinition(
                name = "p1",
                from = listOf(PartitionBound.MinValue),
                to = listOf(PartitionBound.Value("'2024-01-01'")),
            ),
        ),
    )

    val hashed = PartitionConfig(
        type = PartitionType.HASH,
        key = listOf("id"),
        partitions = listOf(PartitionDefinition(name = "h0", modulus = 2, remainder = 0)),
    )

    test("PostgreSQL keeps both — it stores FROM..TO and modulus/remainder") {
        val projected = capabilityPartitionCanonicalizer(DatabaseDialect.POSTGRESQL)
        projected(ranged).partitions.single().from shouldBe listOf(PartitionBound.MinValue)
        projected(hashed).partitions.single().modulus shouldBe 2
    }

    test("Oracle keeps neither — VALUES LESS THAN has no lower bound, HASH no modulus") {
        val projected = capabilityPartitionCanonicalizer(DatabaseDialect.ORACLE)
        projected(ranged).partitions.single().from.shouldBeNull()
        projected(hashed).partitions.single().modulus.shouldBeNull()
        projected(hashed).partitions.single().remainder.shouldBeNull()
    }

    test("the projection leaves everything else about the partitioning alone") {
        val projected = capabilityPartitionCanonicalizer(DatabaseDialect.ORACLE)(ranged)
        // Sonst verdeckte die Projektion echte Drift statt nur die
        // unbeobachtbaren Felder auszublenden.
        projected.type shouldBe ranged.type
        projected.key shouldBe ranged.key
        projected.partitions.single().name shouldBe "p1"
        projected.partitions.single().to shouldBe listOf(PartitionBound.Value("'2024-01-01'"))
    }

    test("a desired schema and what Oracle can report back hash identically") {
        fun schema(partitioning: PartitionConfig) = SchemaDefinition(
            name = "S", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf("d" to ColumnDefinition(NeutralType.Date)),
                    partitioning = partitioning,
                ),
            ),
        )
        // Das Soll traegt die untere Grenze (so liefert sie PostgreSQL);
        // Oracles Reverse kann sie nie zurueckgeben.
        val desired = schema(ranged)
        val observed = schema(ranged.copy(partitions = listOf(ranged.partitions.single().copy(from = null))))
        val oracle = capabilityPartitionCanonicalizer(DatabaseDialect.ORACLE)

        withClue("ohne die Projektion driftet jeder partitionierte Oracle-Round-Trip") {
            MigrationFingerprint.compute(desired, canonicalizePartitioning = oracle) shouldBe
                MigrationFingerprint.compute(observed, canonicalizePartitioning = oracle)
        }
        // Gegenprobe: ungefiltert sind es zwei verschiedene Schemata -- die
        // Projektion blendet also wirklich etwas aus und ist nicht wirkungslos.
        MigrationFingerprint.compute(desired) shouldNotBe MigrationFingerprint.compute(observed)
    }

    test("Oracle folds a midnight timestamp onto the plain date — it cannot tell them apart") {
        // Oracles `DATE` traegt immer eine Uhrzeit. Eine als '2024-01-01'
        // geschriebene Grenze kommt als '2024-01-01 00:00:00' zurueck --
        // derselbe Wert, andere Schreibweise. Der Leser laesst sie stehen
        // (dort waere es geraten), hier ist es eine Dialekt-Eigenschaft.
        val oracle = capabilityPartitionCanonicalizer(DatabaseDialect.ORACLE)
        fun bound(literal: String) = PartitionConfig(
            type = PartitionType.RANGE, key = listOf("d"),
            partitions = listOf(
                PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value(literal))),
            ),
        )
        val folded = listOf("'2024-01-01'", "'2024-01-01 00:00:00'").map {
            oracle(bound(it)).partitions.single().to
        }
        folded[0] shouldBe folded[1]
        folded[0] shouldBe listOf(PartitionBound.Value("'2024-01-01'"))

        // Eine echte Uhrzeit bleibt unangetastet -- sonst verdeckte die
        // Faltung eine tatsaechliche Verschiebung der Grenze.
        oracle(bound("'2024-01-01 12:30:00'")).partitions.single().to shouldBe
            listOf(PartitionBound.Value("'2024-01-01 12:30:00'"))
        // PostgreSQL unterscheidet beides und faltet deshalb nicht.
        capabilityPartitionCanonicalizer(DatabaseDialect.POSTGRESQL)(bound("'2024-01-01 00:00:00'"))
            .partitions.single().to shouldBe listOf(PartitionBound.Value("'2024-01-01 00:00:00'"))
    }

    test("a date-only desired bound hashes the same as what Oracle reports back") {
        fun schema(literal: String) = SchemaDefinition(
            name = "S", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf("d" to ColumnDefinition(NeutralType.Date)),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE, key = listOf("d"),
                        partitions = listOf(
                            PartitionDefinition(name = "p1", to = listOf(PartitionBound.Value(literal))),
                        ),
                    ),
                ),
            ),
        )
        val oracle = capabilityPartitionCanonicalizer(DatabaseDialect.ORACLE)
        withClue("sonst meldet der Post-Compare nach jedem migrate --execute Drift") {
            MigrationFingerprint.compute(schema("'2024-01-01'"), canonicalizePartitioning = oracle) shouldBe
                MigrationFingerprint.compute(
                    schema("'2024-01-01 00:00:00'"), canonicalizePartitioning = oracle,
                )
        }
    }
})
