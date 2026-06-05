package dev.dmigrate.format.parquet.spike

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema.Types
import java.nio.file.Path

/**
 * AP3-Spike — minimaler Parquet-Round-Trip ohne Hadoop-Cluster-Runtime.
 *
 * Beweist die `parquet-libraries.md` §5-Vorentscheidung (parquet-java
 * 1.17.1 + GZIP-Codec ohne SNAPPY/ZSTD-Native-Libs) als lauffaehig in
 * unserer Classpath-Konfiguration (siehe build.gradle.kts:
 * `parquet-hadoop` + `parquet-column` + `hadoop-common:3.4.1` ohne
 * log4j/slf4j-log4j12/servlet/jetty, plus Constraints gegen
 * `parquet-avro`/`parquet-protobuf` und Exclusions fuer Snappy/ZSTD).
 *
 * **Hadoop-API-Befund** (AP3-Verifikation gegen
 * `parquet-libraries.md` §5/§7-Annahme „kein Hadoop"): die in 1.17.1
 * publizierten `ParquetWriter.Builder.withConf`- und
 * `ParquetReader.builder`-Methoden akzeptieren nur Hadoop-`Configuration`
 * bzw. Hadoop-`Path` — die `PlainParquetConfiguration`/`InputFile`-
 * Overloads, die im Vorentscheidungs-Sub-Doc als Implementierungspfad
 * skizziert sind, sind in 1.17.1 schlicht noch nicht da (in `master`
 * vorhanden, kommen mit 1.18). Hadoop-`LocalFileSystem` greift fuer
 * `file://`-URIs aber rein ueber NIO, ohne HDFS oder Cluster-Code;
 * Hadoop-`Path` ist hier nur ein URI-Wrapper. Der Spike entspricht
 * damit dem **Geist** der Vorentscheidung (kein Cluster, kein HDFS),
 * nicht ihrem **Wortlaut**. Der `ChunkWriter`-Entwurf in AP4+ muss das
 * praezisieren: entweder Wechsel auf parquet-java 1.18.x (sobald
 * verfuegbar + CVE-clean), oder explizit Hadoop-API-via-LocalFS als
 * Demo-Pfad behalten.
 *
 * Nicht-Scope: Schema-Discovery, NeutralType-Mapping, ChunkSchema-Vertrag,
 * Streaming-Pages, Decimal-/Temporal-Typen, Footer-Metadaten. Das ist
 * AP-Folge-Arbeit aus `parquet-export-import-evaluation.md` §8.
 */
object ParquetSpike {

    /** Fixes Spike-Schema: int + UTF-8-string + boolean. */
    val SCHEMA: MessageType = MessageType(
        "d_migrate_spike",
        PrimitiveType(Repetition.REQUIRED, PrimitiveTypeName.INT32, "id"),
        Types.required(PrimitiveTypeName.BINARY)
            .`as`(LogicalTypeAnnotation.stringType())
            .named("name"),
        PrimitiveType(Repetition.REQUIRED, PrimitiveTypeName.BOOLEAN, "active"),
    )

    data class SpikeRow(val id: Int, val name: String, val active: Boolean)

    fun write(file: Path, rows: List<SpikeRow>) {
        // Hadoop-Configuration ohne Default-Resources (kein core-site.xml etc.).
        val conf = Configuration(false)
        GroupWriteSupport.setSchema(SCHEMA, conf)
        // Hadoop-Path-Konstruktion aus nio-URI — Hadoop-FS waehlt
        // LocalFileSystem fuer file:// (reines NIO, kein Cluster).
        ExampleParquetWriter.builder(HadoopPath(file.toUri()))
            .withConf(conf)
            .withCompressionCodec(CompressionCodecName.GZIP)
            .withType(SCHEMA)
            .build()
            .use { writer ->
                val factory = SimpleGroupFactory(SCHEMA)
                for (row in rows) {
                    val group = factory.newGroup()
                        .append("id", row.id)
                        .append("name", row.name)
                        .append("active", row.active)
                    writer.write(group)
                }
            }
    }

    fun read(file: Path): List<SpikeRow> {
        val rows = mutableListOf<SpikeRow>()
        ParquetReader.builder(GroupReadSupport(), HadoopPath(file.toUri()))
            .build()
            .use { reader ->
                while (true) {
                    val group = reader.read() ?: break
                    rows += SpikeRow(
                        id = group.getInteger("id", 0),
                        name = group.getString("name", 0),
                        active = group.getBoolean("active", 0),
                    )
                }
            }
        return rows
    }

    /**
     * AP6-Spike: Footer als Schema-Quelle. Liest ausschliesslich
     * `ParquetFileReader#getFileMetaData().getSchema()` und mappt die
     * Top-Level-Felder des `MessageType` auf neutrale
     * [ColumnDescriptor]-Tupel.
     *
     * Demonstriert, dass `parquet-java` allein (ohne MapReduce-Pfad)
     * den Schema-Vertrag liefern kann, den `parquet-schema-source.md`
     * §6 als Importpfad-Preflight beschreibt. `sqlTypeName` traegt die
     * Parquet-Originaltypbezeichnung (z.B. `INT32`, `BINARY (STRING)`)
     * als opaken Hint — der NeutralType-Resolver landet erst im
     * spaeteren Produktiv-Adapter.
     */
    fun readSchemaFromFooter(file: Path): List<ColumnDescriptor> {
        val conf = Configuration(false)
        val inputFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), conf)
        val schema: MessageType = ParquetFileReader.open(inputFile).use { reader ->
            reader.fileMetaData.schema
        }
        return schema.fields.map { field ->
            val primitive = field.asPrimitiveType()
            val logical = primitive.logicalTypeAnnotation?.toString()
            val sqlTypeName = if (logical != null) {
                "${primitive.primitiveTypeName} ($logical)"
            } else {
                primitive.primitiveTypeName.toString()
            }
            ColumnDescriptor(
                name = field.name,
                nullable = field.repetition != Repetition.REQUIRED,
                sqlTypeName = sqlTypeName,
            )
        }
    }

    /**
     * AP6-Spike: Importpfad-Round-Trip ueber das neutrale
     * [DataChunk]-Modell. Kombiniert [readSchemaFromFooter] mit
     * [ParquetReader] und liefert die Spike-Rows als
     * `List<Array<Any?>>`, wie der produktive `ParquetChunkReader`
     * sie spaeter an `DataChunkReader`-Konsumenten weiterreichen
     * wuerde. `chunkIndex` ist im Spike fix 0 — Multi-Chunk-Akkumulation
     * ist Sache des produktiven Adapters.
     */
    fun readAsChunk(file: Path, tableName: String): DataChunk {
        val columns = readSchemaFromFooter(file)
        val rows = mutableListOf<Array<Any?>>()
        ParquetReader.builder(GroupReadSupport(), HadoopPath(file.toUri()))
            .build()
            .use { reader ->
                while (true) {
                    val group = reader.read() ?: break
                    rows += arrayOf<Any?>(
                        group.getInteger("id", 0),
                        group.getString("name", 0),
                        group.getBoolean("active", 0),
                    )
                }
            }
        return DataChunk(
            table = tableName,
            columns = columns,
            rows = rows,
            chunkIndex = 0L,
        )
    }

    /**
     * AP6-Spike: Mitigation fuer den AP3-Befund aus
     * `parquet-libraries.md` §7 (`.crc`-Sidecar). Setzt
     * `fs.file.impl=org.apache.hadoop.fs.RawLocalFileSystem` in der
     * Hadoop-Configuration; damit waehlt Hadoop fuer `file://`-URIs
     * den Raw-Pfad statt der Checksum-FS-Variante und schreibt
     * keinen `.<datei>.parquet.crc`-Sidecar.
     *
     * Trade-off: ohne `.crc` verzichtet man auch auf Hadoop's
     * eingebauten Checksum-Schutz beim Lesen. Der produktive Adapter
     * muss bewusst zwischen dieser Variante und einem aktiven
     * Post-`close()`-Cleanup waehlen.
     */
    fun writeWithoutCrc(file: Path, rows: List<SpikeRow>) {
        val conf = Configuration(false)
        // Variante (a) aus parquet-libraries.md §7: RawLocalFileSystem
        // statt der checksum-tragenden LocalFileSystem-Default-Variante
        // fuer `file://`-URIs. In Hadoop 3.4.x ueberstimmt der
        // Service-Loader-Default die `fs.file.impl`-Direktive ohne
        // zusaetzliches `fs.file.impl.disable.cache=true` — daher hier
        // beide Eintraege gemeinsam.
        conf.set("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem")
        conf.set("fs.file.impl.disable.cache", "true")
        GroupWriteSupport.setSchema(SCHEMA, conf)
        ExampleParquetWriter.builder(HadoopPath(file.toUri()))
            .withConf(conf)
            .withCompressionCodec(CompressionCodecName.GZIP)
            .withType(SCHEMA)
            .build()
            .use { writer ->
                val factory = SimpleGroupFactory(SCHEMA)
                for (row in rows) {
                    val group = factory.newGroup()
                        .append("id", row.id)
                        .append("name", row.name)
                        .append("active", row.active)
                    writer.write(group)
                }
            }
    }
}
