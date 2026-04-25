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
                    "dev.dmigrate.format.SchemaCodec",
                    "dev.dmigrate.format.SchemaCodec\$DefaultImpls",
                    // Pure data containers without logic
                    "dev.dmigrate.driver.connection.ConnectionConfig",
                    "dev.dmigrate.driver.connection.PoolSettings",
                    "dev.dmigrate.driver.data.ResumeMarker",
                    "dev.dmigrate.driver.data.ResumeMarker\$Position",
                    // Server-side store ports (0.9.6 phase A AP 6.2) — interfaces and DTOs
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
                    "dev.dmigrate.server.ports.quota.QuotaStore",
                    "dev.dmigrate.server.ports.SchemaIndexEntry",
                    "dev.dmigrate.server.ports.ProfileIndexEntry",
                    "dev.dmigrate.server.ports.DiffIndexEntry",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$Stored",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$SizeMismatch",
                    "dev.dmigrate.server.ports.WriteArtifactOutcome\$AlreadyExists",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$Stored",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$AlreadyStored",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$Conflict",
                    "dev.dmigrate.server.ports.WriteSegmentOutcome\$SizeMismatch",
                    "dev.dmigrate.server.ports.TransitionOutcome",
                    "dev.dmigrate.server.ports.TransitionOutcome\$Applied",
                    "dev.dmigrate.server.ports.TransitionOutcome\$IllegalTransition",
                    "dev.dmigrate.server.ports.TransitionOutcome\$NotFound",
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
