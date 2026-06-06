# S10a — Dependency-Hygiene + Footprint-Inventar

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S10a / §4.1).
>
> Status: Closed (2026-06-06). Reine Build-Skript-/
> Plan-Doc-Arbeit; kein Produktivcode angefasst.

---

## 1. Scope

Zwei Befunde aus dem Umbrella §1.1 adressieren:

- **Befund 3** (Avro transitiv ueber Hadoop) — schliessen
  per Pfad A (Reject + Exclude). Alternativen-Entscheidung
  begruendet in §3.
- **Befund 4** (Hadoop-Footprint) — **nur inventarisieren**
  und in
  [`parquet-libraries.md`](parquet-libraries.md) §11 als
  1.0.0-Input zurueckspielen. Excludes selbst sind
  bewusst 1.0.0-Arbeit (AP13 §8.3, Umbrella §4
  Eingangsabsatz).

## 2. Lieferumfang

### 2.1 Build-Aenderung

`adapters/driven/formats-parquet/build.gradle.kts`:

- `org.apache.hadoop:hadoop-common`-Block plus
  `org.apache.hadoop:hadoop-mapreduce-client-core`-Block
  bekommen je einen `exclude(group = "org.apache.avro")`
  mit S10a-Kommentar.
- Neuer Constraint-Eintrag `org.apache.avro:avro` mit
  `version { rejectAll() }` plus `because(...)`. Zweck:
  Belt-and-Suspenders gegen kuenftige Dependency-Updates,
  die einen dritten/vierten transitiven Avro-Pfad einziehen
  koennten.

### 2.2 Plan-Doc-Aenderungen

- `docs/planning/done/parquet-libraries.md` §6 AP1.b:
  S10a-Befund-Rueckspiel-Block ergaenzt — dokumentiert den
  `dependencyInsight`-Snapshot vor/nach, begruendet die
  Erweiterung gegenueber dem urspruenglichen
  AP1.b-Constraint und nennt den AP3-Spike-Test als
  Verifikation.
- `docs/planning/done/parquet-libraries.md` §11
  (Footprint-Inventar) neu — gesamtzahl (142
  Runtime-Deps), Hadoop-Footprint-Schwergewichte und die
  drei erwarteten 1.0.0-Massnahmen.

## 3. Pfad A vs. Pfad B — Entscheidung

Umbrella §4.1 verlangt eine eindeutige Wahl. Vorgehensweise
und Beleg fuer **Pfad A**:

1. `dependencyInsight --dependency org.apache.avro:avro
   --configuration runtimeClasspath` auf
   `:adapters:driven:formats-parquet` aufgenommen
   (siehe §4 Snapshot). Ergebnis: `org.apache.avro:avro:1.9.2`
   transitiv ueber `hadoop-common` **und**
   `hadoop-mapreduce-client-core`.
2. Probeweise Pfad A im Build-Skript verdrahtet (Exclude
   auf beide Hadoop-Deps + zusaetzlicher Constraint).
3. `make docker-test MODULES=":adapters:driven:formats-parquet"`
   gegen den geschaerften Classpath: **gruen** (49s Build
   + 25s Test). Damit ist die Bedingung aus Umbrella §4.1
   ("AP3-Spike-Tests bleiben gruen ohne Avro") belegt.
4. Pfad A bleibt drin; Pfad B (akzeptierte Rest-Dependency
   mit `because(...)`) wird nicht gebraucht.

**Konsequenz fuer die AP1.b-Garantie:** die urspruengliche
Aussage in
[`parquet-libraries.md`](parquet-libraries.md) §6 AP1.b
("kein Avro-/Protobuf-Reflection-Pfad im Klassenpfad") war
vor S10a zu stark formuliert — die `parquet-avro`-
Constraint allein verhinderte den `parquet-avro`-Jar,
liess aber den `org.apache.avro:avro`-Jar transitiv ueber
Hadoop drin. Nach S10a ist die Aussage **tatsaechlich**
erfuellt; `dependencyInsight` liefert "No dependencies
matching given input were found".

## 4. Belege

### 4.1 `dependencyInsight` vor S10a

```
org.apache.avro:avro:1.9.2
+--- org.apache.hadoop:hadoop-common:3.4.1
|    \--- runtimeClasspath
\--- org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1
     \--- runtimeClasspath
```

(Befehl: `docker build --target build --build-arg
GRADLE_TASKS=":adapters:driven:formats-parquet:dependencyInsight
--dependency org.apache.avro:avro --configuration
runtimeClasspath"`.)

### 4.2 `dependencyInsight` nach S10a

```
No dependencies matching given input were found in
configuration ':adapters:driven:formats-parquet:runtimeClasspath'
```

### 4.3 AP3-Spike-Test-Lauf nach S10a

`make docker-test MODULES=":adapters:driven:formats-parquet"`:

- Build stage: BUILD SUCCESSFUL in 49s.
- Test stage: BUILD SUCCESSFUL in 25s.
- AP3/AP4/AP5/AP6-Spike-Tests laufen alle gruen ohne
  `org.apache.avro:avro` im Classpath.

### 4.4 Footprint-Snapshot

- Vor S10a: 147 unique Runtime-Dependency-Koordinaten.
- Nach S10a: **142** Koordinaten (5 Avro-Artefakte raus).
- Hadoop-Footprint-Block: ~65 Eintraege ueber 14 Gruppen
  (siehe
  [`parquet-libraries.md`](parquet-libraries.md) §11.2).

## 5. Bewusst NICHT in S10a

- Keine `exclude`/`reject`-Eintraege fuer
  Hadoop-Footprint-Transitive
  (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty). Sind
  1.0.0-Arbeit (AP13 §8.3 / Umbrella §4 / §6).
- Keine Distributions-Cut-Entscheidung (Default-JAR vs.
  `--parquet`-Variante). Liegt in 1.0.0 (AP13 §6.2 i.V.m.
  §8.4 — 0.9.8 bleibt Default-JAR-Modell).
- Keine Native-Image-Probe (S10b).

## 6. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Avro-Klemme geschlossen | `dependencyInsight … avro` | "No dependencies matching given input were found" |
| AP3-Spike-Tests gruen | `make docker-test MODULES=":adapters:driven:formats-parquet"` | BUILD SUCCESSFUL |
| Bestandsformate reagieren nicht | `make docker-test MODULES=":adapters:driven:formats"` | BUILD SUCCESSFUL (implizit ueber `make docker-check`) |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Footprint-Snapshot als 1.0.0-Input | `parquet-libraries.md` §11 | dokumentiert |
| AP1.b-Befund-Rueckspiel | `parquet-libraries.md` §6 AP1.b | erweitert |

## 7. Folgeaufgaben

- **S3** (naechster Slice): `ParquetChunkReader`/`Writer`
  produktiv, `ParquetSeekableDataChunkReaderFactory` als
  Default-Impl des in S2 angelegten Ports,
  `DataExportFormat.PARQUET`-Erweiterung plus
  Contract-Branches in den Default-Factories.
- **S10b** (nach S3): Native-Image-Sondierung gegen die
  in S3 erstellten produktiven Klassen + S10a-
  Constraints; reine Befund-Erhebung, kein gruenes
  CI-Gate (Umbrella §4.2).
- **1.0.0** (AP13 §8.3): Hadoop-Footprint-Minimierung
  basierend auf
  [`parquet-libraries.md`](parquet-libraries.md) §11.2
  Schwergewichte; Distributions-Cut entscheiden.
