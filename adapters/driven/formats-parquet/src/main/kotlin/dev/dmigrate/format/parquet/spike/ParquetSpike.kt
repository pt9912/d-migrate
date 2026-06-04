package dev.dmigrate.format.parquet.spike

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.hadoop.metadata.CompressionCodecName
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
}
