// d-migrate-core: Pure domain model and validation
// ZERO external dependencies — only Kotlin stdlib

kover {
    reports {
        filters {
            excludes {
                // Exclude pure data classes (no logic, only generated methods)
                classes(
                    "dev.dmigrate.core.model.ColumnDefinition",
                    "dev.dmigrate.core.model.ConstraintDefinition",
                    "dev.dmigrate.core.model.ConstraintReferenceDefinition",
                    "dev.dmigrate.core.model.CustomTypeDefinition",
                    "dev.dmigrate.core.model.DependencyInfo",
                    "dev.dmigrate.core.model.FunctionDefinition",
                    "dev.dmigrate.core.model.IndexDefinition",
                    "dev.dmigrate.core.model.ParameterDefinition",
                    "dev.dmigrate.core.model.PartitionConfig",
                    "dev.dmigrate.core.model.PartitionDefinition",
                    "dev.dmigrate.core.model.ProcedureDefinition",
                    "dev.dmigrate.core.model.ReferenceDefinition",
                    "dev.dmigrate.core.model.ReturnType",
                    "dev.dmigrate.core.model.SchemaDefinition",
                    "dev.dmigrate.core.model.SequenceDefinition",
                    "dev.dmigrate.core.model.TableDefinition",
                    "dev.dmigrate.core.model.TriggerDefinition",
                    "dev.dmigrate.core.model.ViewDefinition",
                    // Diff DTOs (pure data, no logic beyond generated equals/hashCode)
                    "dev.dmigrate.core.diff.ChangeType",
                    "dev.dmigrate.core.diff.ValueChange",
                    "dev.dmigrate.core.diff.ColumnDiff",
                    "dev.dmigrate.core.diff.EnumTypeDiff",
                    "dev.dmigrate.core.diff.ViewDiff",
                    "dev.dmigrate.core.diff.TableDiff",
                    "dev.dmigrate.core.diff.SchemaDiff",
                    "dev.dmigrate.core.diff.SchemaMetadataDiff",
                    "dev.dmigrate.core.diff.NamedTable",
                    "dev.dmigrate.core.diff.NamedEnumType",
                    "dev.dmigrate.core.diff.NamedView",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
