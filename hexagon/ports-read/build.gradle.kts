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
                    // Interfaces (including $DefaultImpls inner classes)
                    "dev.dmigrate.driver.data.ChunkSequence",
                    "dev.dmigrate.driver.data.DataReader",
                    "dev.dmigrate.driver.data.DataReader\$DefaultImpls",
                    "dev.dmigrate.driver.data.TableLister",
                    "dev.dmigrate.driver.DdlGenerator",
                    "dev.dmigrate.driver.DdlGenerator\$DefaultImpls",
                    "dev.dmigrate.driver.SchemaReader",
                    "dev.dmigrate.driver.SchemaReader\$DefaultImpls",
                    "dev.dmigrate.format.data.DataChunkReaderFactory",
                    "dev.dmigrate.format.data.DataChunkReaderFactory\$DefaultImpls",
                    "dev.dmigrate.format.data.DataChunkReader",
                    // Pure data containers without logic
                    "dev.dmigrate.driver.SchemaReadOptions",
                    "dev.dmigrate.driver.SchemaReadResult",
                    "dev.dmigrate.driver.SchemaReadNote",
                    "dev.dmigrate.driver.SchemaReadReportInput",
                    "dev.dmigrate.driver.DdlGenerationOptions",
                    "dev.dmigrate.driver.MysqlNamedSequenceMode",
                    "dev.dmigrate.driver.TransformationNote",
                    "dev.dmigrate.driver.SkippedObject",
                    "dev.dmigrate.driver.ManualActionRequired",
                    // DdlResult/DdlStatement: data classes with render helpers,
                    // thoroughly tested via AbstractDdlGeneratorTest and DdlModelTest
                    "dev.dmigrate.driver.DdlResult",
                    "dev.dmigrate.driver.DdlStatement",
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
