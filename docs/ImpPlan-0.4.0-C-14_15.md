# Implementierungsplan: Phase C – Schritt 14 – Writer-Lookup über die bestehende Driver-Registry

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritt**: 14
> **Status**: Geplant
> **Referenz**: `implementation-plan-0.4.0.md` §3.1.2, §4 Phase C Schritt 14, §6.10

---

## 1. Ziel

Schritt 14 muss den Lookup-Pfad für `DataWriter` schaffen, damit CLI und
`StreamingImporter` später pro `DatabaseDialect` den passenden Writer
auflösen können.

Wichtige Besonderheit des aktuellen Repository-Stands: Die Codebasis verwendet
bereits **nicht mehr** die im Masterplan erwähnten Einzel-Registries wie
`DataReaderRegistry`, sondern ein zentrales Aggregat
`DatabaseDriverRegistry` + `DatabaseDriver`.

Deshalb wird Schritt 14 in der aktuellen Codebasis **nicht** als neue
eigenständige `DataWriterRegistry` umgesetzt. Stattdessen wird der bestehende
`DatabaseDriver` um `dataWriter()` erweitert, und der Lookup läuft weiter über
`DatabaseDriverRegistry.get(dialect)`.

Das erfüllt das fachliche Ziel von Schritt 14, ohne eine zweite Registry neben
dem bereits etablierten Aggregat einzuführen.

Wichtig für die Umsetzbarkeit: Dieser Schritt ist in der aktuellen Codebasis
**kein isoliert kompilierbarer Zwischenstand**. Sobald `DatabaseDriver` um
`dataWriter()` erweitert wird, müssen mindestens ein echter Writer und die
betroffenen Driver-Implementierungen im selben Change mitgezogen werden.
Schritt 14 ist deshalb als **atomarer Aggregat-/Bootstrap-Teil von Schritt 15**
zu lesen, nicht als eigenständiger Zwischencommit.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataWriter` | Phase C Schritt 12 | ✅ Vorhanden |
| `SchemaSync`, `SequenceAdjustment` | Phase C Schritt 13 | ✅ Vorhanden |
| `DatabaseDriver` | Bestand | ✅ Vorhanden |
| `DatabaseDriverRegistry` | Bestand | ✅ Vorhanden |
| `PostgresDriver`, `MysqlDriver`, `SqliteDriver` | Bestand | ✅ Vorhanden |

---

## 3. Problemstellung

### 3.1 Plan-Stand vs. Codebasis

Der Masterplan 0.4.0 sagt:

- Schritt 14: `DataWriterRegistry` als eigenes `object` mit `clear()`
- Schritt 18: Treiber-Bootstrap-Objects um `registerDataWriter` ergänzen

Die reale Codebasis ist aber bereits weiter konsolidiert:

- `DatabaseDriver` bündelt `ddlGenerator()`, `dataReader()`, `tableLister()`,
  `urlBuilder()`
- `DatabaseDriverRegistry` ist die zentrale Registry
- `Main.kt` registriert pro Dialekt genau **ein** Driver-Aggregat

Eine neue `DataWriterRegistry` würde deshalb eine zweite parallele
Lookup-Struktur einführen, obwohl die aktuelle Architektur gerade auf ein
zentrales Driver-Aggregat vereinheitlicht wurde.

### 3.2 Architekturentscheidung für die aktuelle Codebasis

Für das Repository gilt daher abweichend vom älteren Text in
`implementation-plan-0.4.0.md`:

1. `DatabaseDriver` bleibt das zentrale Aggregat pro Dialekt.
2. Writer-Fähigkeiten werden **am Aggregat ergänzt**, nicht über eine zweite
   Registry modelliert.
3. `DatabaseDriverRegistry.clear()` bleibt der einzige Test-Reset-Punkt.

Diese Abweichung ist bewusst und reduziert Komplexität:

- kein doppelter Bootstrap-Pfad
- keine Inkonsistenz zwischen Reader- und Writer-Lookup
- keine zusätzliche globale Registry mit eigenem Test-Lifecycle

---

## 4. Betroffene Dateien

### 4.1 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt` | um `fun dataWriter(): DataWriter` erweitern |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriverRegistry.kt` | keine API-Änderung; KDoc auf Writer-Lookup erweitern |
| `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDriver.kt` | `dataWriter()` ergänzen |
| `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDriver.kt` | `dataWriter()` ergänzen |
| `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDriver.kt` | `dataWriter()` ergänzen |

### 4.2 Neue Dateien

| Datei | Zweck |
|---|---|
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/DatabaseDriverRegistryWriterLookupTest.kt` | Verifiziert den Writer-Lookup über das bestehende Aggregat |

---

## 5. Design

### 5.1 Erweiterung von `DatabaseDriver`

Das bestehende Aggregat wird um genau einen zusätzlichen Port ergänzt:

```kotlin
interface DatabaseDriver {
    val dialect: DatabaseDialect

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader
    fun tableLister(): TableLister
    fun dataWriter(): DataWriter
    fun urlBuilder(): JdbcUrlBuilder
}
```

Damit bleibt der Lookup-Pfad für alle datenbankbezogenen Fähigkeiten
konsistent:

- Export: `DatabaseDriverRegistry.get(dialect).dataReader()`
- Import: `DatabaseDriverRegistry.get(dialect).dataWriter()`
- Schema: `DatabaseDriverRegistry.get(dialect).ddlGenerator()`

**Umsetzungsregel**: Diese Interface-Änderung darf nicht separat landen. Sie
ist nur zusammen mit mindestens einer konkreten `dataWriter()`-Implementierung
und den angepassten Driver-Klassen zulässig, weil das Repository sonst nicht
mehr kompiliert.

### 5.2 Warum keine neue `DataWriterRegistry`

Eine separate `DataWriterRegistry` wäre im aktuellen Repo ein Rückschritt:

- sie dupliziert die bereits existierende Dialekt-zu-Driver-Zuordnung
- sie erzeugt eine zweite globale Mutable-State-Stelle
- sie zwingt Tests zu zwei Reset-Pfaden (`DatabaseDriverRegistry.clear()` plus
  `DataWriterRegistry.clear()`)
- sie schwächt das bereits etablierte Driver-Aggregat statt es zu vervollständigen

### 5.3 Test-Reset

Der Masterplan fordert bei Schritt 14 explizit ein `clear()` für Tests. Diese
Anforderung ist in der aktuellen Codebasis bereits erfüllt:

- `DatabaseDriverRegistry.clear()` existiert
- Tests für Reader/CLI verwenden diesen Reset schon heute

Für Writer-Lookup-Tests wird deshalb **kein neuer Reset-Mechanismus** gebaut.

### 5.4 Auswirkungen auf Schritt 18

Schritt 18 wird dadurch ebenfalls angepasst:

- statt „Bootstrap-Objects um `registerDataWriter` ergänzen“
- gilt im aktuellen Repo: Driver-Implementierungen liefern ab dann
  `dataWriter()`, und der bestehende `DatabaseDriverRegistry.register(driver)`
  reicht unverändert aus

---

## 6. Implementierung

### 6.1 `DatabaseDriver.kt`

`DatabaseDriver` wird um `fun dataWriter(): DataWriter` erweitert.

Wichtig:

- keine Default-Implementierung
- kein `UnsupportedOperationException`-Fallback
- alle Dialekt-Driver müssen den Port explizit implementieren, sobald ihre
  Writer in Schritt 15–17 existieren

### 6.2 `DatabaseDriverRegistry.kt`

Die Registry selbst bleibt funktional unverändert. Optional wird nur die KDoc
geschärft, damit klar ist, dass sie jetzt auch Writer-seitige Fähigkeiten
bereitstellt.

### 6.3 Driver-Klassen

Die drei Driver-Klassen müssen mit der Aggregat-Signatur **im selben Change**
mitgezogen werden, in dem `DatabaseDriver` erweitert wird.

Für die aktuelle Implementierungsreihenfolge gilt deshalb:

- Schritt 14 liefert die Architekturentscheidung und die Lookup-Regeln
- der erste **reale** Code-Change dazu landet atomar mit Schritt 15
  (`PostgresDataWriter` + `PostgresDriver.dataWriter()`)
- `MysqlDriver` und `SqliteDriver` werden in ihren jeweiligen Writer-Schritten
  (16/17) auf dieselbe Aggregat-Signatur gebracht

Es gibt damit bewusst **keinen** Stand, in dem `DatabaseDriver` schon
`dataWriter()` fordert, aber die Driver-Klassen oder Writer-Implementierungen
noch fehlen.

### 6.4 `DatabaseDriverRegistryWriterLookupTest.kt`

Geplante Testfälle:

| # | Testname | Prüfung |
|---|---|---|
| 1 | `returns driver with writer capability for registered dialect` | `get(dialect).dataWriter()` liefert den registrierten Writer |
| 2 | `clear removes registered writer lookup indirectly via driver registry` | nach `clear()` schlägt der Lookup fehl |
| 3 | `missing dialect still reports registered dialects` | Fehlermeldung bleibt aussagekräftig |

Die Tests arbeiten mit einem Stub-`DatabaseDriver`, nicht mit echten
JDBC-Writern. Sie landen zusammen mit dem ersten echten Aggregat-Change
(voraussichtlich in Schritt 15), nicht als isolierter Vorab-Commit.

---

## 7. Tests

### 7.1 Teststrategie

Schritt 14 ist in der aktuellen Codebasis primär ein Aggregat-/Registry-Schritt.
Deshalb reichen:

- kleine Registry-Tests ohne Datenbank
- ein Compile-Check, dass `DatabaseDriver`, alle Driver-Klassen und die
  direkten Consumer sauber verdrahten

### 7.2 Nicht Teil von Schritt 14

- Verhalten der konkreten `PostgresDataWriter`/`MysqlDataWriter`/`SqliteDataWriter`
- Trigger-/Reseed-Logik
- echte JDBC-Integration

Diese Pfade gehören in Schritt 15–17.

---

## 8. Build & Verifizierung

```bash
# Atomarer Aggregat-/Writer-Change:
# Ports, erste Writer-Implementierung und direkte Consumer kompilieren/testen
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports:compileKotlin :adapters:driven:driver-common:test :adapters:driven:driver-postgresql:test :adapters:driving:cli:test" \
  -t d-migrate:c14 .

# Optional kompletter Build
docker build -t d-migrate:dev .
```

---

## 9. Abnahmekriterien

- [ ] `DatabaseDriver` enthält `dataWriter()`
- [ ] es gibt **keine** zusätzliche `DataWriterRegistry`
- [ ] `DatabaseDriverRegistry.clear()` bleibt der einzige Reset-Pfad
- [ ] Lookup für Writer läuft über `DatabaseDriverRegistry.get(dialect).dataWriter()`
- [ ] mindestens ein realer Driver (`PostgresDriver`) implementiert `dataWriter()` im selben Change
- [ ] ein Registry-Test deckt den Writer-Lookup ab
- [ ] der Aggregat-Change kompiliert nicht nur in `hexagon:ports`, sondern auch in Driver- und CLI-Consumern
- [ ] Schritt 18 ist logisch vorbereitet, ohne doppelten Bootstrap-Pfad

---

## 10. Offene Punkte

- Der 0.4.0-Masterplan sollte bei Gelegenheit nachgezogen werden: §3.1.2 und
  Schritt 14/18 sprechen noch von einer separaten `DataWriterRegistry`, obwohl
  die aktuelle Codebasis bereits auf `DatabaseDriverRegistry` konsolidiert ist.
