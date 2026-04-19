plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Interfaces
                    "dev.dmigrate.driver.data.DataWriter",
                    "dev.dmigrate.driver.data.SchemaSync",
                    "dev.dmigrate.driver.data.TableImportSession",
                    "dev.dmigrate.format.data.DataChunkWriterFactory",
                    "dev.dmigrate.format.data.DataChunkWriter",
                    "dev.dmigrate.migration.ToolMigrationExporter",
                    "dev.dmigrate.streaming.checkpoint.CheckpointStore",
                    "dev.dmigrate.streaming.ProgressReporter",
                    // Pure data containers without logic
                    "dev.dmigrate.driver.data.ImportOptions",
                    "dev.dmigrate.driver.data.TargetColumn",
                    "dev.dmigrate.driver.data.WriteResult",
                    "dev.dmigrate.driver.data.SequenceAdjustment",
                    "dev.dmigrate.driver.data.UnsupportedTriggerModeException",
                    "dev.dmigrate.format.data.ExportOptions",
                    "dev.dmigrate.migration.MigrationIdentity",
                    "dev.dmigrate.migration.ArtifactRelativePath",
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
