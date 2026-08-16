---
status: accepted
date: 2026-08-16
decision-makers: pt9912
consulted: adapters/driven/formats-parquet/build.gradle.kts
informed: docs/planning/open/dependency-cve-exposure-shipped-artifact.md, docs/planning/done/parquet-single-file-import-type-cast.md
---

# Hadoop bleibt im Parquet-Adapter — parquet-java erzwingt es

> **Status: accepted (2026-08-16).** `hadoop-common` und
> `hadoop-mapreduce-client-core` bleiben Abhängigkeiten von
> `adapters/driven/formats-parquet`. Ein vollständiger Ausstieg ist mit
> parquet-java **1.17.1 und 1.18.0** nicht möglich — nachgemessen, nicht
> vermutet. Die Angriffsfläche wird stattdessen über gezielte `exclude`-Regeln
> klein gehalten.

## Kontext und Problemstellung

Der Hadoop-Baum unter `formats-parquet` war die Quelle fast aller
Abhängigkeits-CVEs im Auslieferungsartefakt
([dependency-cve-exposure-shipped-artifact.md](../planning/open/dependency-cve-exposure-shipped-artifact.md)).
Nach den Ausschlüssen von Netty, ZooKeeper, BouncyCastle, YARN und HDFS blieben
noch vier Befunde (drei mittel, einer niedrig) sowie rund 16 MB — alle am
Hadoop-Kern.

Der naheliegende Schluss war, Hadoop ganz herauszuschneiden: Der Adapter
benutzte nur **zwei** Hadoop-Typen (`Configuration`, `Path`) in vier Dateien,
und parquet-java bringt mit `LocalInputFile`, `LocalOutputFile` und
`PlainParquetConfiguration` scheinbar Hadoop-freie Gegenstücke mit.

## Was gemessen wurde (2026-08-16)

Der Umbau wurde vollständig durchgeführt: Reader, Writer, Preflight und Spike
liefen auf `LocalInputFile`/`LocalOutputFile`/`PlainParquetConfiguration`, der
Produktivcode enthielt **null** Hadoop-Referenzen und kompilierte. Danach fielen
der Reihe nach drei Laufzeit-Bindungen auf:

| Bindung | Befund | umgehbar? |
| --- | --- | --- |
| `CodecFactory.getCodec` | `CompressionCodecFactory` ist in `parquet-common` nur ein Interface; alle Implementierungen liegen in `parquet-hadoop` und lösen Codecs über Hadoop auf | **ja** — eigene GZIP-Factory über `java.util.zip`, Tests liefen damit grün |
| `ParquetReadOptions$Builder` | instanziiert intern `HadoopParquetConfiguration`, deren `<init>` eine Hadoop-`Configuration` erzeugt | **nein** |
| `ParquetReader$Builder` | **jeder** Konstruktor — auch die `InputFile`-Variante — verlangt `org.apache.hadoop.mapreduce.lib.input.FileInputFormat` | **nein** |

Die beiden letzten wurden per `javap` gegen die jeweiligen Jars geprüft und
gelten **in 1.17.1 wie in 1.18.0 gleichermaßen**. Ein Versionssprung löst das
Problem also nicht.

**Der verführerische Teilbefund:** Die `InputFile`- und
`ParquetConfiguration`-Überladungen *existieren* in 1.17.1 — entgegen einer
älteren Notiz im Code, die sie erst für 1.18 erwartete. Aus „die Signaturen sind
Hadoop-frei" folgt aber nicht „Hadoop ist entbehrlich": Die Implementierungen
dahinter sind es nicht. Wer diesen Weg erneut erwägt, sollte bei
`ParquetReader$Builder` anfangen und nicht bei den Signaturen.

## Betrachtete Optionen

1. **Vollständiger Ausstieg.** Gemessen blockiert, siehe oben.
2. **Nur `hadoop-mapreduce-client-core` entfernen.** Ebenfalls blockiert —
   `ParquetReader$Builder` braucht `FileInputFormat`. Bliebe nur, die
   Leseschleife auf die Low-Level-API (`ParquetFileReader` + `ColumnIO` +
   `GroupRecordConverter`) umzuschreiben. Gewinn wären ~1,6 MB und **null**
   CVEs — die vier verbliebenen hängen an `hadoop-common`, nicht an MapReduce.
   Aufwand und Risiko stehen dazu in keinem Verhältnis.
3. **Andere Parquet-Bibliothek.** Nicht untersucht; wäre ein eigenes Vorhaben
   mit Format-Risiko.
4. **Hadoop behalten, Angriffsfläche per `exclude` klein halten** (gewählt).

## Entscheidung

Gewählt: **Option 4.** Die bestehenden `exclude`-Regeln in
[`formats-parquet/build.gradle.kts`](../../adapters/driven/formats-parquet/build.gradle.kts)
haben Netty (35 Jars), ZooKeeper, Curator, BouncyCastle, YARN und HDFS-Client
bereits entfernt und das Artefakt von 240 auf 187 Jars gebracht. Das ist der
wirksame Hebel; der Rest ist Hadoop-Kern, der bibliotheksbedingt bleibt.

## Konsequenzen

- **Bleibend:** ~16 MB Hadoop-Kern und vier Abhängigkeits-Befunde (drei mittel,
  einer niedrig): `guava 27.1`, `commons-configuration2`, `commons-lang3`.
  Keiner davon kritisch oder hoch.
- **`guava` 27 → 32** bleibt damit die einzige verbleibende Stellschraube für
  zwei dieser Befunde — ein Fünf-Major-Sprung an einer Bibliothek, auf der
  Hadoop intern aufbaut, und deshalb bewusst nicht nebenbei mitgenommen.
- **Unberührt** bleibt das geshadete Jackson in `parquet-jackson`: Es kommt von
  `parquet-hadoop` (der Bibliothek selbst, nicht von Hadoop) und wäre auch bei
  einem Ausstieg geblieben. Es ist als begründete Ausnahme mit Ablaufdatum in
  `.trivyignore.yaml` hinterlegt.

## Wann neu bewerten

Wenn parquet-java `ParquetReader`/`ParquetReadOptions` von Hadoop entkoppelt —
also wenn `ParquetReader$Builder` ohne `FileInputFormat` und
`ParquetReadOptions$Builder` ohne `HadoopParquetConfiguration` auskommt. Beides
ist mit zwei `javap`-Aufrufen gegen ein neues Jar prüfbar, bevor irgendein Code
angefasst wird.
