plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":hexagon:core"))

    testFixturesApi(project(":hexagon:core"))
    testFixturesApi("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
    testFixturesApi("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Interfaces (including $DefaultImpls inner classes)
                    "dev.dmigrate.driver.connection.ConnectionPool",
                    "dev.dmigrate.driver.connection.JdbcUrlBuilder",
                    "dev.dmigrate.driver.connection.JdbcUrlBuilder\$DefaultImpls",
                    "dev.dmigrate.driver.TypeMapper",
                    // LN-009: Verify-Kanonik-Port (Interface) + reine Exception ohne Logik
                    "dev.dmigrate.verify.ValueCanonicalizer",
                    "dev.dmigrate.verify.ValueCanonicalizationException",
                    "dev.dmigrate.format.SchemaCodec",
                    "dev.dmigrate.format.SchemaCodec\$DefaultImpls",
                    // Pure data containers without logic
                    "dev.dmigrate.driver.connection.ConnectionConfig",
                    "dev.dmigrate.driver.connection.PoolSettings",
                    "dev.dmigrate.driver.data.ResumeMarker",
                    "dev.dmigrate.driver.data.ResumeMarker\$Position",
                    // Server-side store ports (LF-012 / LN-011 / LN-017 / LN-027) — interfaces and DTOs
                    "dev.dmigrate.server.ports.JobStore",
                    "dev.dmigrate.server.ports.ArtifactStore",
                    "dev.dmigrate.server.ports.ArtifactContentStore",
                    "dev.dmigrate.server.ports.SchemaStore",
                    "dev.dmigrate.server.ports.ProfileStore",
                    "dev.dmigrate.server.ports.DiffStore",
                    "dev.dmigrate.server.ports.UploadSessionStore",
                    "dev.dmigrate.server.ports.UploadSegmentStore",
                    "dev.dmigrate.server.ports.ConnectionReferenceStore",
                    "dev.dmigrate.server.ports.IdempotencyStore",
                    "dev.dmigrate.server.ports.SyncEffectIdempotencyStore",
                    "dev.dmigrate.server.ports.ApprovalGrantStore",
                    "dev.dmigrate.server.ports.AuditSink",
                    "dev.dmigrate.server.ports.StdioTokenStore",
                    "dev.dmigrate.server.ports.StdioTokenGrant",
                    "dev.dmigrate.server.ports.quota.QuotaStore",
                    "dev.dmigrate.server.ports.SchemaIndexEntry",
                    "dev.dmigrate.server.ports.ProfileIndexEntry",
                    "dev.dmigrate.server.ports.DiffIndexEntry",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$Stored",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$SizeMismatch",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$AlreadyExists",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$Conflict",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$Stored",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$AlreadyStored",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$Conflict",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$SizeMismatch",
                    "dev.dmigrate.server.ports.TransitionOutcome",
                    "dev.dmigrate.server.ports.TransitionOutcome\$Applied",
                    "dev.dmigrate.server.ports.TransitionOutcome\$IllegalTransition",
                    "dev.dmigrate.server.ports.TransitionOutcome\$NotFound",
                    "dev.dmigrate.server.ports.JobTransitionOutcome",
                    "dev.dmigrate.server.ports.JobTransitionOutcome\$Applied",
                    "dev.dmigrate.server.ports.JobTransitionOutcome\$IllegalTransition",
                    "dev.dmigrate.server.ports.JobTransitionOutcome\$NotFound",
                    "dev.dmigrate.server.ports.JobStartTransaction",
                    "dev.dmigrate.server.ports.JobStartTransactionOutcome",
                    "dev.dmigrate.server.ports.JobStartTransactionOutcome\$Committed",
                    "dev.dmigrate.server.ports.JobStartTransactionOutcome\$IdempotencyNotEligible",
                    "dev.dmigrate.server.ports.WorkerHandleRegistry",
                    "dev.dmigrate.server.ports.SignalOutcome",
                    "dev.dmigrate.server.ports.SignalOutcome\$Signaled",
                    "dev.dmigrate.server.ports.SignalOutcome\$NotFound",
                    "dev.dmigrate.server.ports.quota.QuotaKey",
                    "dev.dmigrate.server.ports.quota.QuotaCounter",
                    "dev.dmigrate.server.ports.quota.QuotaDimension",
                    "dev.dmigrate.server.ports.quota.QuotaOutcome",
                    "dev.dmigrate.server.ports.quota.QuotaOutcome\$Granted",
                    "dev.dmigrate.server.ports.quota.QuotaOutcome\$RateLimited",
                    // Default-impl synthetic helpers for interfaces with default parameters
                    "dev.dmigrate.server.ports.JobStore\$DefaultImpls",
                    "dev.dmigrate.server.ports.ArtifactStore\$DefaultImpls",
                    "dev.dmigrate.server.ports.UploadSessionStore\$DefaultImpls",
                    // Verbindungs-Konfig-DTOs (LN-026) — gleiche Klasse wie
                    // ConnectionConfig/PoolSettings oben; die SSL-Parse-Logik
                    // (SslSettingsParser/JdbcUrlBuilder) wird in den Treiber-Adaptern getestet.
                    "dev.dmigrate.driver.connection.SslMode",
                    "dev.dmigrate.driver.connection.SslSettings",
                )
                // Befund 17: `server.ports` (Port-Interfaces + Daten-Vertraege) und
                // `format.data` (Chunk-/Bundle-Schema-DTOs + Fixtures) sind Contract-
                // Definitionen — ihre Verhaltenslogik lebt und wird getestet in den
                // implementierenden Adaptern (persistence-memory/-jdbc, formats, streaming)
                // via der geteilten Contract-Suiten. ports-commons eigene Coverage bezieht
                // sich auf seine Utility-Logik (driver/connection/credential). EIN
                // `packages()`-Aufruf (Subpakete inklusive → deckt `…ports.contract` mit ab).
                packages("dev.dmigrate.server.ports", "dev.dmigrate.format.data")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
