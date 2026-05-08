// d-migrate-core: Pure domain model and validation
// ZERO external dependencies — only Kotlin stdlib (test fixtures may add
// kotest for shared test helpers; see LF-012 / LN-011 / LN-017 / LN-027 cancel-contract fixture).

plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    testFixturesApi("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}

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
                    "dev.dmigrate.core.model.TableMetadata",
                    "dev.dmigrate.core.model.TriggerDefinition",
                    "dev.dmigrate.core.model.ViewDefinition",
                    // Diff DTOs (pure data, no logic beyond generated equals/hashCode)
                    "dev.dmigrate.core.diff.ValueChange",
                    "dev.dmigrate.core.diff.NamedTable",
                    "dev.dmigrate.core.diff.NamedView",
                    "dev.dmigrate.core.diff.NamedCustomType",
                    "dev.dmigrate.core.diff.NamedSequence",
                    "dev.dmigrate.core.diff.NamedFunction",
                    "dev.dmigrate.core.diff.NamedProcedure",
                    "dev.dmigrate.core.diff.NamedTrigger",
                    // Validation result DTOs (data carriers)
                    "dev.dmigrate.core.validation.ValidationWarning",
                    // Server-core (LF-012 / LN-011 / LN-017 / LN-027) — pure data carriers
                    "dev.dmigrate.server.core.principal.PrincipalContext",
                    "dev.dmigrate.server.core.principal.TenantId",
                    "dev.dmigrate.server.core.principal.PrincipalId",
                    "dev.dmigrate.server.core.resource.ServerResourceUri",
                    "dev.dmigrate.server.core.job.ManagedJob",
                    "dev.dmigrate.server.core.job.JobError",
                    "dev.dmigrate.server.core.job.JobProgress",
                    "dev.dmigrate.server.core.job.JobCancelRequest",
                    "dev.dmigrate.server.core.artifact.ManagedArtifact",
                    "dev.dmigrate.server.core.upload.UploadSession",
                    "dev.dmigrate.server.core.upload.UploadSegment",
                    "dev.dmigrate.server.core.connection.ConnectionReference",
                    "dev.dmigrate.server.core.error.ToolErrorEnvelope",
                    "dev.dmigrate.server.core.error.ToolErrorDetail",
                    "dev.dmigrate.server.core.pagination.PageRequest",
                    "dev.dmigrate.server.core.pagination.PageResult",
                    "dev.dmigrate.server.core.execution.ExecutionMeta",
                    "dev.dmigrate.server.core.approval.ApprovalGrant",
                    "dev.dmigrate.server.core.approval.ApprovalCorrelationKind",
                    // Server-core idempotency outcomes (LF-012 / LN-011 / LN-017 / LN-027).
                    // Wildcards cover the outer sealed-interface marker plus
                    // every nested data-class subtype in one shot.
                    "dev.dmigrate.server.core.idempotency.IdempotencyKey",
                    "dev.dmigrate.server.core.idempotency.IdempotencyState",
                    "dev.dmigrate.server.core.idempotency.IdempotencyScope",
                    "dev.dmigrate.server.core.idempotency.SyncEffectScope",
                    "dev.dmigrate.server.core.idempotency.InitResumeScope",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$*",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$*",
                    "dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome",
                    "dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome\$*",
                    "dev.dmigrate.server.core.idempotency.InitResumeOutcome",
                    "dev.dmigrate.server.core.idempotency.InitResumeOutcome\$*",
                    // Server-core audit (AP 6.2 minimal seed; expanded in AP 6.8)
                    "dev.dmigrate.server.core.audit.AuditEvent",
                    "dev.dmigrate.server.core.audit.AuditOutcome",
                    // LF-017 / LF-024 / LN-030 / LN-031: Server-core AI types —
                    // Datentraeger fuer Outcome-Lifecycle und KI-Artefakt-
                    // Provenance. Init-Blocks pruefen Form-Invarianten;
                    // semantische Pfade sind in adapters/driving/mcp und
                    // hexagon/application abgedeckt. Wildcards umfassen
                    // den sealed-Marker plus jeden Sub-Typ.
                    "dev.dmigrate.server.core.ai.AiToolScope",
                    "dev.dmigrate.server.core.ai.AiToolClaimId",
                    "dev.dmigrate.server.core.ai.AiToolOutcome",
                    "dev.dmigrate.server.core.ai.AiToolOutcome\$*",
                    "dev.dmigrate.server.core.ai.AiToolAcquireOutcome",
                    "dev.dmigrate.server.core.ai.AiToolAcquireOutcome\$*",
                    "dev.dmigrate.server.core.ai.AiArtifactMetadata",
                    "dev.dmigrate.server.core.ai.AiArtifactProvenance",
                    "dev.dmigrate.server.core.ai.AiArtifactProvenance\$*",
                    "dev.dmigrate.server.core.ai.AiWireArtifactKind",
                    "dev.dmigrate.server.core.ai.AiIntent",
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
