package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteNamedSequenceMode

/**
 * Shared fixtures for the SQLite-Sequence Phase B.3 test suites.
 *
 * Kept package-internal because the individual test files are split
 * across multiple [io.kotest.core.spec.style.FunSpec] classes to stay
 * under detekt's `LargeClass` threshold; redefining the same
 * fixtures in each file would duplicate ~30 lines and drift over
 * time.
 */
internal object SqliteSequenceTestFixtures {

    val helperTableOptions: DdlGenerationOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Sqlite(
            namedSequenceMode = SqliteNamedSequenceMode.HELPER_TABLE,
        ),
    )

    val actionRequiredOptions: DdlGenerationOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Sqlite(
            namedSequenceMode = SqliteNamedSequenceMode.ACTION_REQUIRED,
        ),
    )

    fun seqColumn(seqName: String = "order_seq", required: Boolean = false): ColumnDefinition =
        ColumnDefinition(
            type = NeutralType.BigInteger,
            required = required,
            default = DefaultValue.SequenceNextVal(seqName),
        )

    fun textColumn(required: Boolean = false): ColumnDefinition =
        ColumnDefinition(type = NeutralType.Text(), required = required)

    fun schemaWith(
        tables: Map<String, TableDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ): SchemaDefinition = SchemaDefinition(
        name = "test-schema",
        version = "1.0.0",
        tables = tables,
        sequences = sequences,
        triggers = triggers,
    )
}
