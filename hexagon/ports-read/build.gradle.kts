plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-common"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // All Kotlin-generated $DefaultImpls inner classes
                    "*\$DefaultImpls",
                    // Interfaces
                    "dev.dmigrate.driver.data.ChunkSequence",
                    "dev.dmigrate.driver.data.DataReader",
                    "dev.dmigrate.driver.data.TableLister",
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.SchemaReader",
                    "dev.dmigrate.format.data.DataChunkReaderFactory",
                    "dev.dmigrate.format.data.DataChunkReader",
                    // Pure data containers / enums without testable logic
                    "dev.dmigrate.driver.SchemaReadOptions",
                    "dev.dmigrate.driver.SchemaReadResult",
                    "dev.dmigrate.driver.SchemaReadNote",
                    "dev.dmigrate.driver.SchemaReadReportInput",
                    "dev.dmigrate.driver.DdlGenerationOptions",
                    "dev.dmigrate.driver.MysqlNamedSequenceMode",
                    "dev.dmigrate.driver.TransformationNote",
                    "dev.dmigrate.driver.SkippedObject",
                    "dev.dmigrate.driver.ManualActionRequired",
                    "dev.dmigrate.driver.DdlResult",
                    "dev.dmigrate.driver.DdlStatement",
                    "dev.dmigrate.driver.DdlPhase",
                    "dev.dmigrate.driver.NoteType",
                    "dev.dmigrate.driver.ReverseSourceKind",
                    "dev.dmigrate.driver.ReverseSourceRef",
                    "dev.dmigrate.driver.SchemaReadSeverity",
                    "dev.dmigrate.format.data.FormatReadOptions",
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
