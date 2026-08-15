// AP3-Spike — Parquet-Adapter (siehe Plan-Doc
// docs/planning/done-archive/parquet-export-import-evaluation.md
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
    api(project(":hexagon:ports-common"))
    api(project(":hexagon:ports-read"))
    api(project(":hexagon:ports-write"))
    implementation(project(":hexagon:core"))

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

        // Verteilte Hadoop-Infrastruktur raus. d-migrate liest lokale
        // Parquet-Dateien ueber org.apache.hadoop.fs.Path/Configuration; ein
        // HDFS-Cluster, dessen Hochverfuegbarkeit oder dessen
        // SPNEGO/Kerberos-Pfad kommen nie vor. Mitgeliefert wurden sie
        // trotzdem, samt ihrer Angriffsflaeche.
        //
        // Ein Versionszwang waere hier die schlechtere Haelfte: die
        // Fix-Versionen dieser Baeume streuen ueber viele Patch-Staende, und
        // das Nachziehen betraefe Code, der nie ausgefuehrt wird. Was nicht
        // ausgeliefert wird, muss auch nicht gepflegt werden.
        exclude(group = "org.apache.zookeeper")
        exclude(group = "org.apache.curator")
        exclude(group = "org.bouncycastle")
        exclude(group = "io.netty")
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

        // Hier sitzt der groesste Brocken: dieser Block zieht
        // `io.netty:netty-all` — das Sammelartefakt, das JEDES Netty-Modul
        // mitbringt. Daher lagen Codecs fuer Redis, SMTP, STOMP, MQTT, XML und
        // HAProxy im Auslieferungsartefakt. d-migrate spricht keines dieser
        // Protokolle; Netty dient hier dem HDFS-/YARN-RPC.
        exclude(group = "io.netty")

        // YARN wird nur fuer die Job-Submission gebraucht, die hier nie
        // stattfindet — benoetigt wird allein FileInputFormat fuer den
        // ParquetInputFormat-Klassenladepfad (Begruendung des Blocks oben).
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-client")
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-common")
        exclude(group = "org.apache.hadoop", module = "hadoop-yarn-api")
        // Eigener Eintrag, obwohl auch der YARN-Zweig darauf zeigt: dieser Block
        // haengt zusaetzlich DIREKT an hadoop-hdfs-client, der YARN-Ausschluss
        // allein liess es also im Artefakt. Gelesen wird ueber file://.
        exclude(group = "org.apache.hadoop", module = "hadoop-hdfs-client")
        exclude(group = "org.apache.zookeeper")
        exclude(group = "org.apache.curator")
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

        // Anders als die Baeume oben werden diese beiden wirklich gebraucht —
        // commons-beanutils von hadoop-common (Configuration), aircompressor von
        // parquet-hadoop als reiner JVM-Codec (die JNI-Varianten snappy-java und
        // zstd-jni sind weiter unten ausgeschlossen). Also heben statt entfernen.
        // Ohne diese beiden Zeilen zieht die transitive Aufloesung wieder die
        // verwundbaren Staende.
        implementation("commons-beanutils:commons-beanutils") {
            version { require("1.11.0") }
            because("CVE-2025-48734 — hadoop-common 3.4.1 loest sonst auf 1.9.4 auf.")
        }
        implementation("io.airlift:aircompressor") {
            version { require("2.0.3") }
            because("CVE-2025-67721 — parquet-hadoop 1.17.1 loest sonst auf 2.0.2 auf.")
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
