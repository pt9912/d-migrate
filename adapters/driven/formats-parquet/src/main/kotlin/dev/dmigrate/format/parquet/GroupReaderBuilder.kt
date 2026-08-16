package dev.dmigrate.format.parquet

import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.io.InputFile

/**
 * Liest Parquet-`Group`s aus einem [InputFile] statt ueber einen
 * Hadoop-`Path`.
 *
 * Der Unterschied ist nicht kosmetisch: Die pfadbasierte Fabrikmethode
 * `ParquetReader.builder(ReadSupport, Path)` geht ueber Hadoops
 * `FileSystem` und initialisiert dabei `UserGroupInformation` — die im
 * nativen Binary an `Subject.getSubject()` scheitert (auf dem JDK des
 * GraalVM-Builders entfernt). Ueber ein [InputFile] wird das
 * Hadoop-`FileSystem` nie betreten; die Hadoop-Klassen bleiben nur als
 * Compile-/Laufzeit-Bibliothek von parquet-java auf dem Klassenpfad
 * (siehe ADR 0046 — ganz entfernen laesst sich Hadoop nicht).
 *
 * Die [InputFile]-Konstruktoren von `ParquetReader.Builder` sind
 * `protected` — genau dafuer gedacht, abgeleitet zu werden;
 * `getReadSupport()` ist der vorgesehene Erweiterungspunkt.
 */
internal class GroupReaderBuilder(
    inputFile: InputFile,
) : ParquetReader.Builder<Group>(inputFile) {

    override fun getReadSupport(): ReadSupport<Group> = GroupReadSupport()
}
