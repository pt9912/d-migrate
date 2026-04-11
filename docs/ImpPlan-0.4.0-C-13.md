# Implementierungsplan: Phase C – Schritt 13 – SchemaSync Interface + SequenceAdjustment

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritt**: 13
> **Status**: Geplant
> **Referenz**: `implementation-plan-0.4.0.md` §3.1.1, §4 Phase C Schritt 13, §6.6, §6.7

---

## 1. Ziel

Den in Schritt 12 nur minimal angelegten Vertrag für `SchemaSync` und
`SequenceAdjustment` finalisieren:

- vollständige KDoc am Port-Interface `SchemaSync`
- präzise Dokumentation von `SequenceAdjustment`
- dedizierte Unit-Tests für `SequenceAdjustment`
- Schärfung der verbindlichen Port-Semantik im Dokument und in der KDoc, damit
  die dialektspezifischen Implementierungen in Schritt 15–17 gegen einen
  eindeutigen Vertrag gebaut werden

Schritt 13 liefert bewusst **noch keine** JDBC-Dialekt-Implementierungen
(`PostgresSchemaSync`, `MysqlSchemaSync`, `SqliteSchemaSync`). Es geht um die
Port-Schärfung und das Absichern der Regeln, gegen die diese Implementierungen
später gebaut werden.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataWriter`, `TableImportSession`, `FinishTableResult` | Phase C Schritt 12 | ✅ Vorhanden |
| Minimal-`SchemaSync` | Phase C Schritt 12 | ✅ Vorhanden |
| `SequenceAdjustment` | Phase C Schritt 12 | ✅ Vorhanden |
| `ColumnDescriptor`, `DataChunk` | 0.3.0 core | ✅ Vorhanden |
| `design-import-sequences-triggers.md` | Architektur-/Designbasis | ✅ Referenziert im Masterplan |

---

## 3. Betroffene Dateien

### 3.1 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SchemaSync.kt` | KDoc vervollständigen, Vertragsdetails aus §6.6/§6.7 übernehmen |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SequenceAdjustment.kt` | KDoc und Feldsemantik schärfen |

### 3.2 Neue Dateien

| Datei | Zweck |
|---|---|
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/SequenceAdjustmentTest.kt` | Data-Class-/Semantik-Tests |

---

## 4. Design

### 4.1 Rolle von `SchemaSync`

`SchemaSync` ist der dialektspezifische Nebenkanal des Writer-Pfads für
Operationen, die **nicht** im eigentlichen Chunk-Insert liegen:

- Generator-/Sequence-Reseeding nach erfolgreichem Tabellenabschluss
- Trigger-Disable/Enable bei `triggerMode = disable`
- Trigger-Pre-Flight bei `triggerMode = strict`

Der `StreamingImporter` kennt diesen Port nur indirekt über
`DataWriter.schemaSync()`. Die konkrete Dialektlogik bleibt deshalb aus dem
Importer herausgezogen.

### 4.2 Vertrag `reseedGenerators(...)`

`reseedGenerators(...)` ist ein Erfolgsabschluss-Hook und hat für 0.4.0 die
folgenden verbindlichen Regeln:

1. Der Aufruf erfolgt **nur nach erfolgreichem Import aller Chunks** einer
   Tabelle, nie aus dem Fehlerpfad `close()`.
2. Der Implementierer prüft auf Basis von `importedColumns`, welche Spalten im
   Ziel Generator-Spalten sind.
3. Der höchste importierte bzw. im Ziel vorhandene Wert wird auf derselben
   Session-Connection über `MAX(col)` bestimmt.
4. Ist `MAX(col)` `NULL`, ist der Standardpfad ein **No-op** ohne
   `SequenceAdjustment`.
5. Ausnahme: nach `markTruncatePerformed()` und leer gebliebener Tabelle muss
   MySQL/SQLite den Folge-Zähler aktiv auf den Startwert zurücksetzen.
6. Das Ergebnis wird als `List<SequenceAdjustment>` zurückgegeben und später in
   den Import-Report übernommen.

### 4.3 Vertrag `disableTriggers(...)`, `assertNoUserTriggers(...)`, `enableTriggers(...)`

Die drei Methoden bilden zusammen die Trigger-Politik aus §6.7:

| Methode | Modus | Pflicht |
|---|---|---|
| `disableTriggers(...)` | `disable` | Führt die Deaktivierung aus oder wirft `UnsupportedTriggerModeException`, wenn der Dialekt keinen sicheren Pfad hat |
| `assertNoUserTriggers(...)` | `strict` | Führt einen reinen Pre-Flight aus und bricht bei vorhandenen Triggern mit klarer Exception ab |
| `enableTriggers(...)` | `disable` | Führt die zuvor deaktivierten Trigger wieder zusammen |

Zusätzliche Regeln:

- `enableTriggers(...)` muss **idempotent** sein
- Fehler in `enableTriggers(...)` sind harte Fehler und dürfen nicht still
  geschluckt werden
- auf PostgreSQL laufen `disableTriggers(...)` und `enableTriggers(...)`
  außerhalb der Chunk-Transaktionen in eigenen Mini-Transaktionen
- MySQL und SQLite unterstützen `disable` in 0.4.0 bewusst nicht generisch

### 4.4 `SequenceAdjustment`

`SequenceAdjustment` ist kein Command-Objekt, sondern ein reiner Report-Eintrag
für eine bereits ausgeführte Nachführung.

Bedeutung der Felder:

| Feld | Bedeutung |
|---|---|
| `table` | logischer Tabellenname des Imports |
| `column` | Generator-/Identity-Spalte |
| `sequenceName` | PostgreSQL: expliziter Sequence-Name; MySQL/SQLite: `null` |
| `newValue` | neuer Zustand des Generators nach der Anpassung |

`newValue` beschreibt **den nächsten von der Datenbank ohne expliziten Wert
auszugebenden Generatorwert**. Er beschreibt also bewusst nicht den internen
DB-Zustand einer konkreten Implementierung (`setval(..., is_called)`-Semantik,
`sqlite_sequence.seq` etc.), sondern die fachlich relevante Report-Frage:
"Welchen Wert bekommt der nächste Insert ohne explizite Generator-Spalte?"

Beispiele:

- PostgreSQL `setval(..., maxValue, true)` → `newValue = maxValue + 1`
- MySQL `AUTO_INCREMENT = 1` nach leerem Truncate-Pfad → `newValue = 1`
- SQLite `DELETE FROM sqlite_sequence ...` nach leerem Truncate-Pfad →
  `newValue = 1`

### 4.5 Testfokus in Schritt 13

Da Schritt 13 noch keine echten Dialekt-Implementierungen baut, testen wir
keine SQL-Statements und auch keine künstlichen Interface-Fakes, die nur ihren
eigenen Stub beweisen würden. Stattdessen sichern wir in Schritt 13:

- dass die Port-Semantik klar und stabil dokumentiert ist
- dass `SequenceAdjustment` die im Report erwartete Form trägt
- dass die späteren Implementierungen in Schritt 15–17 gegen eine eindeutige
  KDoc-/Plan-Spezifikation gebaut werden

---

## 5. Implementierung

### 5.1 `SchemaSync.kt`

`SchemaSync.kt` wird von der Minimal-Fassung aus Schritt 12 zur finalen
Port-Dokumentation erweitert. Die Signatur bleibt unverändert:

```kotlin
interface SchemaSync {
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    fun disableTriggers(conn: Connection, table: String)
    fun assertNoUserTriggers(conn: Connection, table: String)
    fun enableTriggers(conn: Connection, table: String)
}
```

Ergänzt werden KDoc-Punkte zu:

- Aufrufzeitpunkt
- Erfolgs-/Fehlerpfad
- Idempotenz-Anforderungen
- erlaubten Unsupported-Fällen
- Abgrenzung gegen Chunk-Transaktionen
- `newValue`-Semantik im Zusammenspiel mit dem Import-Report

### 5.2 `SequenceAdjustment.kt`

Die Data-Class bleibt klein und unverändert. Ergänzt wird nur die
Feldsemantik in der KDoc, insbesondere die Unterscheidung:

- PG: `sequenceName != null`
- MySQL/SQLite: `sequenceName == null`
- `newValue` als nächster generierter Wert für den nächsten impliziten Insert

### 5.3 `SequenceAdjustmentTest.kt`

Geplante Testfälle:

| # | Testname | Prüfung |
|---|---|---|
| 1 | `stores PostgreSQL adjustment with explicit sequence name` | alle Felder werden korrekt gehalten |
| 2 | `stores MySQL adjustment without sequence name` | `sequenceName == null` ist erlaubt |
| 3 | `stores SQLite reset adjustment with next generated value one` | SQLite-Truncate-Reset ist mit Report-Semantik darstellbar |
| 4 | `supports equality and copy semantics` | Data-Class-Verhalten bleibt stabil |

---

## 6. Tests

### 6.1 Teststrategie

Schritt 13 ist ein Port-/Vertrags-Schritt. Deshalb sind die Tests bewusst:

- klein
- ohne Datenbank
- ohne Testcontainers
- auf `SequenceAdjustment` fokussiert
- ergänzt durch Compile-/Build-Verifikation für die geänderten Port-Dateien

### 6.2 Abgrenzung zu späteren Schritten

Nicht Teil von Schritt 13:

- PostgreSQL `pg_get_serial_sequence(...)`-Quoting-Tests
- MySQL `AUTO_INCREMENT`-SQL
- SQLite-`sqlite_sequence`-SQL
- Trigger-Disable/Enable gegen echte Katalogtabellen
- wiederverwendbare Dialekt-Contract-Suiten; diese lohnen erst ab Schritt 15,
  wenn echte `SchemaSync`-Implementierungen existieren

Diese Pfade gehören in die Dialekt-Implementierungen der Schritte 15–17.

---

## 7. Coverage-Ziel

Für Schritt 13 gilt:

- `SequenceAdjustment.kt`: 100 % Line-Coverage
- `SchemaSync.kt`: keine künstliche Coverage-Zielsetzung; das Interface trägt
  kein eigenes Laufzeitverhalten und wird primär über KDoc geschärft
- die eigentliche Laufzeitabdeckung des `SchemaSync`-Vertrags entsteht erst in
  Schritt 15–17 über die konkreten Dialektimplementierungen

Gesamtziel pro Modul bleibt ≥ 90 %.

---

## 8. Build & Verifizierung

```bash
# Ports kompilieren und die neuen Semantik-Tests ausführen
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:ports:compileKotlin :adapters:driven:driver-common:test" \
  -t d-migrate:c13 .

# Optional kompletter Build inkl. aggregierter Verifikation
docker build -t d-migrate:dev .
```

---

## 9. Abnahmekriterien

- [ ] `SchemaSync.kt` enthält vollständige KDoc zu Reseed-/Trigger-Vertrag
- [ ] `SequenceAdjustment.kt` dokumentiert die Feldsemantik präzise
- [ ] `SequenceAdjustmentTest.kt` ist vorhanden und grün
- [ ] Kein JDBC-/SQL-Code in Schritt 13
- [ ] Dialekt-spezifische Implementierungsdetails bleiben sauber für Schritt 15–17 getrennt
- [ ] `newValue` ist im Dokument und in der KDoc eindeutig als nächster generierter Wert definiert

---

## 10. Offene Punkte

Keine. Die noch fehlende Laufzeitverifikation des `SchemaSync`-Vertrags ist
bewusst in Schritt 15–17 eingeplant, weil erst dort echte Implementierungen
vorliegen.
