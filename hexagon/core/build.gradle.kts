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
                    // Server-core (0.9.6 phase A) — pure data carriers
                    "dev.dmigrate.server.core.principal.PrincipalContext",
                    "dev.dmigrate.server.core.principal.TenantId",
                    "dev.dmigrate.server.core.principal.PrincipalId",
                    "dev.dmigrate.server.core.resource.ServerResourceUri",
                    "dev.dmigrate.server.core.job.ManagedJob",
                    "dev.dmigrate.server.core.job.JobError",
                    "dev.dmigrate.server.core.job.JobProgress",
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
                    // Server-core idempotency outcomes (0.9.6 phase A AP 6.2)
                    "dev.dmigrate.server.core.idempotency.IdempotencyKey",
                    "dev.dmigrate.server.core.idempotency.IdempotencyScope",
                    "dev.dmigrate.server.core.idempotency.SyncEffectScope",
                    "dev.dmigrate.server.core.idempotency.InitResumeScope",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$Reserved",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$ExistingPending",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$AwaitingApproval",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$Committed",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$Denied",
                    "dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome\$Conflict",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$Claimed",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$AlreadyClaimed",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$Committed",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$Denied",
                    "dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome\$NotAwaitingApproval",
                    "dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome\$Reserved",
                    "dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome\$Existing",
                    "dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome\$Conflict",
                    "dev.dmigrate.server.core.idempotency.InitResumeOutcome\$Reserved",
                    "dev.dmigrate.server.core.idempotency.InitResumeOutcome\$Existing",
                    "dev.dmigrate.server.core.idempotency.InitResumeOutcome\$Conflict",
                    // Server-core audit (AP 6.2 minimal seed; expanded in AP 6.8)
                    "dev.dmigrate.server.core.audit.AuditEvent",
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
