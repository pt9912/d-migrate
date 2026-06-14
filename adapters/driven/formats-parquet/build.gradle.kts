// AP3-Spike — Parquet-Adapter (siehe Plan-Doc
// docs/planning/done/parquet-export-import-evaluation.md
// + parquet-libraries.md §8).
//
// Dependency-Skizze 1:1 aus parquet-libraries.md §8 uebernommen:
// - parquet-hadoop + parquet-column als Core-Pfad
// - hadoop-common 3.4.1 als Compile-Zeit-Bedarf
//   (org.apache.hadoop.fs.Path, Configuration etc.); Schwergewichte
//   (log4j 1.x, slf4j-log4j12, javax.servlet, Jetty) bewusst gezogen
// - parquet-avro/parquet-protobuf werden via Constraints aus dem
//   Klassenpfad gehalten (CVE-2025-30065/46762 vermieden, kein
//   Reflection-Pfad)
// - SNAPPY/ZSTD Native-Codecs ausgeschlossen; Default-Codec im
//   Adapter ist GZIP (rein JVM, java.util.zip)

plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
    implementation(project(":hexagon:core"))

    // S3b (Parquet Cut A 2026-06-06): ParquetBundleClosure
    // referenziert BundleClosureContext aus dem Streaming-Modul.
    // implementation (kein api), weil das Wiring vom CLI ausgeht
    // und formats-parquet keine Streaming-Typen weiter exponiert.
    implementation(project(":adapters:driven:streaming"))

    implementation("org.apache.parquet:parquet-hadoop:${rootProject.properties["parquetVersion"]}")
    implementation("org.apache.parquet:parquet-column:${rootProject.properties["parquetVersion"]}")

    // S3b (Parquet Cut A 2026-06-06): snakeyaml-engine fuer
    // manifest.yaml-Serialisierung (AP7 §5; parquet-libraries.md §3.2).
    // Bewusst dieselbe Bibliothek wie im JSON/YAML/CSV-Modul, damit
    // wir keine zweite YAML-Toolchain ins Bundle ziehen.
    implementation("org.snakeyaml:snakeyaml-engine:${rootProject.properties["snakeyamlEngineVersion"]}")

    implementation("org.apache.hadoop:hadoop-common:${rootProject.properties["hadoopVersion"]}") {
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
        exclude(group = "org.eclipse.jetty.websocket")
        // S10a (2026-06-06, Pfad A): Avro-Datenklassen aus dem
        // runtimeClasspath verbannen. dependencyInsight zeigt
        // org.apache.avro:avro:1.9.2 transitiv ueber hadoop-common.
        // d-migrate konsumiert keinen Hadoop-Avro-Schreibpfad
        // (AP3-Spike-Test als Beleg gegen geschaerften Classpath
        // erbracht). Symmetrisch zum parquet-avro/protobuf-Reject
        // unten — kein Avro-/Protobuf-Reflection-Pfad im
        // runtimeClasspath.
        exclude(group = "org.apache.avro")
    }
    // AP3-Befund (5ca1497f, in parquet-libraries.md §8 nachgezogen):
    // parquet-hadoop ParquetReader.builder triggert das
    // ParquetInputFormat-Klassenladen (extends MapReduce FileInputFormat).
    // Ohne hadoop-mapreduce-client-core wirft das einen ClassNotFoundError.
    // Cut-B-Folge-Aufgabe (parquet-libraries.md §8): pruefen, ob ein
    // dedizierter Reader-Pfad ohne MapReduce-Abhaengigkeit existiert,
    // oder den Block dauerhaft pinnen (z.B. via 1.18.x-Wechsel).
    implementation("org.apache.hadoop:hadoop-mapreduce-client-core:${rootProject.properties["hadoopVersion"]}") {
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
        // S10a (2026-06-06, Pfad A): siehe hadoop-common-Block.
        // Avro kommt ueber hadoop-mapreduce-client-core ein zweites
        // Mal transitiv rein; auch hier exclude.
        exclude(group = "org.apache.avro")
    }
}

dependencies {
    constraints {
        implementation("org.apache.parquet:parquet-avro") {
            version { rejectAll() }
            because(
                "parquet-avro wird in d-migrate nicht benoetigt; " +
                    "Avro-Reflection-Pfade und CVE-2025-30065-Klasse aus dem " +
                    "Klassenpfad heraushalten.",
            )
        }
        implementation("org.apache.parquet:parquet-protobuf") {
            version { rejectAll() }
            because(
                "parquet-protobuf wird in d-migrate nicht benoetigt; " +
                    "Protobuf-Reflection und zusaetzliche Native-Image-Last vermeiden.",
            )
        }
        // S10a (2026-06-06, Pfad A): zweigleisige Absicherung gegen
        // org.apache.avro:avro. Excludes auf den Hadoop-Deps oben
        // verbannen die heute beobachteten transitiven Pfade; der
        // Constraint hier verhindert, dass ein spaeterer
        // Dependency-Update versehentlich einen vierten Pfad
        // einzieht (z.B. ueber hadoop-mapreduce-client-jobclient).
        implementation("org.apache.avro:avro") {
            version { rejectAll() }
            because(
                "S10a/Pfad A — kein Avro-Reflection-Pfad im runtimeClasspath. " +
                    "d-migrate konsumiert keinen Hadoop-Avro-Code; AP3-Spike-Tests " +
                    "bleiben gruen ohne diese Klasse (parquet-libraries.md §6 AP1.b).",
            )
        }
    }
}

configurations.all {
    // Native-Compression-Codecs raus: JNI-Bibliotheken aus dem JAR
    // extrahieren widerspricht Distributions- und Native-Image-Ziel.
    exclude(group = "org.xerial.snappy", module = "snappy-java")
    exclude(group = "com.github.luben", module = "zstd-jni")
}

// AP4 — DuckDB-Akzeptanzlauf gegen den Spike-Output. DuckDB JDBC
// liest die Parquet-Datei via `SELECT * FROM read_parquet(?)` und
// dient ausschliesslich als Inspektions-/Akzeptanztestwerkzeug
// (parquet-libraries.md §3.5: kein produktiver Writer/Reader).
// Bewusst nur testImplementation: das DuckDB-JNI darf nicht ins
// Distributions-Artefakt wandern.
dependencies {
    testImplementation("org.duckdb:duckdb_jdbc:${rootProject.properties["duckdbJdbcVersion"]}")
}

// S3 (Parquet Cut A 2026-06-06): Default-Factory-Contract-Branch-
// Tests greifen auf DefaultDataChunkReader/WriterFactory aus
// :adapters:driven:formats zu. Bewusst nur testImplementation —
// kein produktiver Pfad von formats-parquet nach formats.
dependencies {
    testImplementation(project(":adapters:driven:formats"))
    testImplementation("io.kotest:kotest-runner-junit5:${rootProject.properties["kotestVersion"]}")
    testImplementation("io.kotest:kotest-assertions-core:${rootProject.properties["kotestVersion"]}")
}

// AP5 — Arrow-Metadateninspektion des Spike-Outputs. parquet-arrow
// liefert den `SchemaConverter` (Parquet `MessageType` -> Arrow
// `Schema`) und zieht transitiv `arrow-vector` (reines JVM-POJO
// fuer die Schema-Klasse — kein JNI, vgl. parquet-libraries.md
// §3.4 zur Abgrenzung gegen `arrow-dataset`). Versionslinie ist
// an parquet-java gekoppelt, deshalb dieselbe parquetVersion.
// Bewusst testImplementation: kein produktiver Arrow-Pfad in
// d-migrate (parquet-libraries.md §3.4).
dependencies {
    testImplementation("org.apache.parquet:parquet-arrow:${rootProject.properties["parquetVersion"]}")
}
