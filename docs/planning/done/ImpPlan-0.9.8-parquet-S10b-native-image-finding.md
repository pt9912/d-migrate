# S10b — Native-Image-Befund (Sondierung)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S10b / §4.2).
>
> Status: Closed (2026-06-06). Reine Befund-Erhebung **kein
> gruenes CI-Gate**, kein Native-Image-Lauf — Native-Image-Cut
> ist 1.0.0-Aufgabe per AP13 §8.3.

---

## 1. Scope

Per Umbrella §4.2 + Tabellen-Eintrag S10b:

> Sondierungslauf gegen die produktiven Klassen aus S3 +
> S10a-Constraints. **Kein gruenes Gate fuer 0.9.8**, nur
> Befund-Erhebung als 1.0.0-Input.

Drei Aufgaben:

1. Reflection-/Service-Loader-/JNI-Lasten der in S3
   eingefuehrten Klassen sondieren.
2. S10a-Delta einschaetzen (was wird durch Avro-Hygiene
   besser).
3. Befund in
   [`parquet-libraries.md`](parquet-libraries.md) §12 als
   1.0.0-Input zurueckspielen.

## 2. Methodische Einschraenkung

GraalVM/`native-image` ist im Repo nicht verdrahtet:

- Kein Gradle-Plugin
  (`org.graalvm.buildtools.native` oder aequivalent).
- Kein Dockerfile-Stage mit GraalVM-Toolchain.
- Kein `nativeBuild`/`nativeRun`-Make-Target.

Das Aufsetzen ist 1.0.0-Aufgabe und gehoert nicht in 0.9.8
(AP13 §8.3). S10b hat deshalb **kein** echtes `native-image`-
Build durchgefuehrt — der Befund ist statisch erhoben:

- `grep`-basierte Code-Sondierung der S3-Klassen
  (`Class.forName`, `ClassLoader`-Reflection,
  `ServiceLoader`, `System.load*`, `native fun`).
- Bekannte Reachability-Schmerzpunkte der
  Bibliothekspfade (`parquet-java`, `hadoop-common`,
  `hadoop-mapreduce-client-core`, Jersey-1, Curator,
  Zookeeper, Kerby), klassifiziert nach
  Loesungs-Schwierigkeit.

Diese Methodik ist ausreichend, um die in
[`parquet-libraries.md`](parquet-libraries.md) §12.4
genannte Reihenfolge im 1.0.0-Planning zu schaerfen.
Sie ist explizit **nicht** ausreichend, um zu beweisen,
dass das Image baut — das bleibt 1.0.0-Aufgabe.

## 3. Befunde (Zusammenfassung)

Detailliert in
[`parquet-libraries.md`](parquet-libraries.md) §12. Hier
nur die fuenf wichtigsten Punkte:

1. **Eigener S3-Code ist Native-Image-clean** — kein
   `Class.forName`, kein `ServiceLoader`, kein JNI.
2. **`parquet-java` selbst ist manageable** —
   Reachability-Metadaten fuer `PageReader`/`GzipCodec`
   reichen; Community-Beispiele existieren.
3. **`Hadoop FileSystem`-Service-Loader ist moderate** —
   12+ Implementierungen via Service-Loader; Allowlist
   auf `LocalFileSystem`/`RawLocalFileSystem` reduziert
   das Image deutlich.
4. **Jersey-1/Curator/Zookeeper/Kerby sind potentielle
   Show-Stopper** — tief reflection-getrieben, aber
   nicht im d-migrate-Datenpfad konsumiert. Loesungspfad
   ist Footprint-Minimierung (1.0.0-Cut), nicht
   Reachability-Konfig.
5. **S10a-Delta ist positiv, aber klein** — Avro-Wegfall
   spart eine Reachability-Metadaten-Datei.

## 4. S10a-Delta (Avro-Hygiene)

Avro raus = ein reflection-heavy Codepfad weniger:

- Avro-`Schema.parse` nutzt Reflection fuer
  `GenericRecord`-Klassenallocation.
- `Schema.GenericDatumReader` / `Writer` haben
  Annotation-getriebene Method-Dispatch.

Vor S10a haette der 1.0.0-Native-Image-Cut diese Klassen
in einem `reflect-config.json`-Eintrag pflegen oder per
`--initialize-at-build-time` blacklisten muessen. Nach
S10a entfaellt der Eintrag komplett.

**Quantitativ** ist das ein kleiner Vorteil (1 von ~10
geschaetzten reflect-config-Bloecken fuer den 1.0.0-Cut);
**qualitativ** sind Avro-Reachability-Konfigurationen
notoriously instabil ueber Versionen, weil Avro die
Class-Schema-Maps an jedem Major-Release umbaut. Der
Wegfall macht die 1.0.0-Konfiguration also nicht nur
kleiner, sondern auch stabiler.

## 5. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Eigener S3-Code reflection-clean | `grep -rnE "Class\.forName\|ServiceLoader\|System\.load" adapters/driven/formats-parquet/src/main` | leer |
| Befund in `parquet-libraries.md` §12 dokumentiert | `grep -n "Native-Image-Befund" docs/planning/done/parquet-libraries.md` | Treffer in §12 |
| Klassifikation manageable / moderate / hard vorhanden | §12.2 | drei Kategorien mit Mitigation-Pfaden |
| S10a-Delta dokumentiert | §12.3 + dieses Doc §4 | dokumentiert |
| Kein Native-Image-Lauf gefordert | Umbrella §4.2 | konsistent |

## 6. Bewusst NICHT in S10b

- **Kein `native-image`-Build**. Aufsetzen + Lauf gehoert
  in 1.0.0 (AP13 §8.3 / Umbrella §4.2 ausdruecklich).
- **Keine `reflect-config.json`/`resource-config.json`-
  Skeleton-Dateien**. Werden im 1.0.0-Cut zusammen mit
  dem ersten `native-image`-Build erstellt.
- **Keine `org.graalvm.buildtools.native`-Plugin-
  Integration**. 1.0.0-Aufgabe.

## 7. Folgeaufgaben

- **1.0.0 Native-Image-Cut**: das in
  [`parquet-libraries.md`](parquet-libraries.md) §12.4
  benannte Vorgehen umsetzen:
  1. Footprint-Minimierung (HDFS/YARN/Jersey/Kerby/
     Zookeeper-Excludes).
  2. `org.graalvm.buildtools.native`-Plugin verdrahten.
  3. Reachability-Metadaten fuer den verbliebenen
     parquet-java + hadoop-common-Stack.
  4. Smoketest gegen ParquetChunkRoundTripTest (S3) im
     Native-Image-Build.
- **Optionaler Hadoop-API-Shim**: wuerde Hadoop-
  Configuration-/FileSystem-Reachability komplett
  aufloesen. Variante neben Footprint-Excludes
  bewerten.
