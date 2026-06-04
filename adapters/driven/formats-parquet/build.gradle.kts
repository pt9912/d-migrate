// AP3-Spike — Parquet-Adapter (siehe Plan-Doc
// docs/planning/in-progress/parquet-export-import-evaluation.md
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

    implementation("org.apache.parquet:parquet-hadoop:${rootProject.properties["parquetVersion"]}")
    implementation("org.apache.parquet:parquet-column:${rootProject.properties["parquetVersion"]}")

    implementation("org.apache.hadoop:hadoop-common:${rootProject.properties["hadoopVersion"]}") {
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
        exclude(group = "org.eclipse.jetty.websocket")
    }
    // AP3-Befund: parquet-hadoop ParquetReader.builder triggert
    // ParquetInputFormat-Klassenladen (extends MapReduce FileInputFormat).
    // Ohne hadoop-mapreduce-client-core wirft das einen ClassNotFoundError.
    // parquet-libraries.md §8 nennt das noch nicht; AP4+ muss klaeren ob
    // ein dedizierter Reader-Pfad ohne MapReduce-Abhaengigkeit existiert.
    implementation("org.apache.hadoop:hadoop-mapreduce-client-core:${rootProject.properties["hadoopVersion"]}") {
        exclude(group = "log4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "javax.servlet")
        exclude(group = "org.eclipse.jetty")
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
    }
}

configurations.all {
    // Native-Compression-Codecs raus: JNI-Bibliotheken aus dem JAR
    // extrahieren widerspricht Distributions- und Native-Image-Ziel.
    exclude(group = "org.xerial.snappy", module = "snappy-java")
    exclude(group = "com.github.luben", module = "zstd-jni")
}
