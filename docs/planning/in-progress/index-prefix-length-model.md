# Index-Präfixlänge als Modellfeld (`IndexColumn.prefixLength`)

**Status**: In Arbeit (2026-06-17 — nach `in-progress/` verschoben, Phase 1
aktiv). Scope + Blast-Radius kartiert, Phasenschnitt und Akzeptanzkriterien
ausgearbeitet; Review + Tiefenprüfung + Code-Verifikation eingearbeitet
(D-1…D-6).

**Trigger**: P2-Pilot-Blocker **I-08** (MySQL: Index auf unbounded `TEXT`/`BLOB`
ohne Präfixlänge → `ERROR 1170`). Beim Fix-Entwurf fiel auf, dass das Modell
`IndexColumn` keine Präfixlänge tragen kann. Eine gültige MySQL-Präfix-Index-
Ausgabe (`col(255)`) ist damit nicht round-trip-fähig: selbst ein MySQL→MySQL-
Durchlauf verlöre die Präfixlänge. Entscheidung in der Sitzung 2026-06-17:
**Option C** — Präfixlänge als erstklassiges Modellfeld einführen statt sie im
MySQL-Generator zu raten oder den Index zu skippen.

**Aktivierungsbedingung** (Move nach `in-progress/`): erster Implementierungs-
Commit gemäß [`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md).

> **Status-Update 2026-06-17 (Review):** D-1, D-2, D-3 entschieden (siehe
> Abschnitt 6). Sequenz festgelegt: **P2-Rest (I-07, I-09, I-08-PG) zuerst**,
> diese Modellscheibe danach als eigener Slice (Abschnitt 8, Variante 2).
>
> **Status-Update 2026-06-17 (Review-Tiefenprüfung):** D-2 um einen
> Kompatibilitätsteil erweitert — die Fingerprint-Formatänderung invalidiert
> auch feldseitige Rollback-Artefakte, nicht nur Test-Goldens (`ALGORITHM`-Bump
> nötig). Neu: **D-4** (PRIMARY-KEY-/Constraint-Präfixlängen explizit out of
> scope) und **D-5** (`IndexColumn.toString()` nimmt Präfix auf). Scope-
> Abschnitte, Blast-Radius, Phasen und Akzeptanzkriterien entsprechend
> nachgezogen.
>
> **Status-Update 2026-06-17 (Review-Pass 3, Code-Verifikation):** Faktencheck
> gegen den Code ergab: der Migration-Fingerprint trägt die Index-Richtung
> **bereits** (via `IndexColumn.toString()` in `MigrationFingerprint.kt:136`/
> `:156`), `CanonicalPayload` dagegen nicht (eigener name-only-Selector). Daraus:
> D-2-Begründung korrigiert (kein „auch ohne Präfix"-Hash-Shift; der Bruch ist
> der `SUB_PART`-lesende Reverse-Reader), D-5 um die toString-Propagation auf
> Fingerprint + `indexKey` erweitert, neu **D-6** (`CanonicalPayload`/
> Operation-IDs bewusst unangetastet — kein Round-Trip-Bedarf, kein Versions-Tag
> für Operation-IDs). `FINGERPRINT_ALGORITHM` ist Alias von
> `MigrationFingerprint.ALGORITHM` → ein Bump, nicht zwei. Variante (a) (toString
> trägt den Präfix) gewählt. Phasen, Blast-Radius und Akzeptanzkriterien
> nachgezogen.

---

## 1. Ziel

`IndexColumn` trägt eine optionale Präfixlänge. Damit kann d-migrate:

- **MySQL-Präfix-Indizes verlustfrei round-trippen** (Reverse liest `SUB_PART`,
  Generate emittiert `col(n)`).
- Bei **Cross-Dialect**-Migrationen die Präfix-Semantik **explizit und korrekt**
  behandeln statt invaliden Output oder stille Längen zu erzeugen:
  - MySQL-Ziel mit bekannter Präfixlänge → gültiger Präfix-Index.
  - MySQL-Ziel, `TEXT`/`BLOB` **ohne** Präfixlänge (z. B. aus PG) → Skip + Note
    (`ERROR 1170` vermeiden; das ist der eigentliche I-08-Kern).
  - PG-/SQLite-Ziel mit gesetzter Präfixlänge → Voll-Spalten-Index (gültig) +
    Note „Präfixlänge verworfen" (kein Präfix-Syntax in PG/SQLite).

## 2. Hintergrund

MySQL kann `TEXT`/`BLOB` nur als **Präfix-Index** indizieren
(`CREATE INDEX i ON t (body(255))`). Fehlt die Schlüssellänge, bricht MySQL mit
`ERROR 1170` ab. Die Präfixlänge ist in `information_schema.statistics.SUB_PART`
verfügbar (NULL bei Voll-Index). PostgreSQL und SQLite kennen **kein**
Präfix-Index-Konzept (sie indizieren die volle Spalte; PG nutzt stattdessen
Expression-/Operator-Class-Indizes).

Heutiges Modell ([`IndexDefinition.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/IndexDefinition.kt)):

```kotlin
data class IndexColumn(val name: String, val direction: IndexSortDirection? = null)
```

Kein Feld für die Schlüssellänge → die Information geht beim Reverse verloren und
kann beim Generate nicht erzeugt werden.

## 3. Scope

### 3.1 In Scope

- Modellfeld `IndexColumn.prefixLength: Int? = null`.
- Reverse-Pfad MySQL: `SUB_PART` lesen und durchreichen.
- Generate-Pfade aller drei Dialekte (MySQL/PG/SQLite) gemäß Ziel-Matrix (1.).
- Serialisierung (JSON-Schema-Format) + `spec/schema.json`-Vertrag.
- Round-Trip-Stabilität: Migration-Fingerprint + Comparator (Canonical-Payload
  bewusst unverändert, D-6).
- Regressionstests je betroffenem Modul + Live-MySQL-Round-Trip.
- **UNIQUE-Indizes** automatisch abgedeckt: MySQL-Reverse mappt sie auf
  `IndexDefinition(unique=true)` (nicht auf `ConstraintDefinition`), sie laufen
  also durch `IndexColumn` und erben `prefixLength`.

### 3.2 Out of Scope

- Präfix-Indizes über **mehrere** Spalten mit gemischten Längen jenseits dessen,
  was `SUB_PART` pro Spalte liefert (wird automatisch abgedeckt, da pro
  `IndexColumn` getragen — aber keine darüber hinausgehende Heuristik).
- Funktions-/Expression-Indizes (PG) — separater Gegenstand.
- Automatische **Wahl** einer Präfixlänge, wenn keine vorliegt (bleibt Skip+Note;
  kein Raten — vgl. Sitzungsentscheid gegen Option B).
- **PRIMARY-KEY-Präfixlängen** (`PRIMARY KEY (col(100))`) und **UNIQUE als
  generischer Constraint** (`ConstraintDefinition`). PK liegt als
  `TableDefinition.primaryKey: List<String>`, Constraints als
  `ConstraintDefinition.columns: List<String>` — beide können keine Länge tragen.
  Bewusst draußen, eigener Slice (Begründung → **D-4**). UNIQUE als *Index* ist
  dagegen abgedeckt (3.1) — in MySQL laufen auch präfigierte `UNIQUE KEY`s über
  den Index-Pfad und sind damit erfasst; nur PG-artige Constraint-Modellierung
  bleibt offen.

## 4. Blast-Radius (kartiert 2026-06-17)

| Bereich | Datei(en) | Änderung |
| --- | --- | --- |
| Modell | `.../hexagon/core/.../model/IndexDefinition.kt` | Feld `prefixLength` + `toString` mit Präfix (D-5) |
| Reverse-Projektion | `.../adapters/driven/driver-common/.../metadata/MetadataProjections.kt` | `IndexProjection.prefixLengths` (zu `indexColumns` index-parallel — Ausrichtung wahren); `indexColumns`-Getter |
| Reverse MySQL | `.../driver-mysql/.../MysqlMetadataQueries.kt` | `SUB_PART` in `listIndices`-SELECT + Mapping |
| Reverse PG/SQLite | `.../driver-postgresql/...`, `.../driver-sqlite/...` | keine Quelle → `null` (kein Präfix-Konzept) |
| Generate MySQL | `.../driver-mysql/.../MysqlIndexPartitionDdlHelper.kt` (`renderIndexColumn`) + `MysqlDiffSqlBuilders.kt` | `col(n)` rendern; ohne Länge bei TEXT/BLOB → `SkippedObject` + `TransformationNote` (WARNING) (I-08) |
| Generate PG | `.../driver-postgresql/.../PostgresDdlGenerator.kt` (`renderIndexColumn`) + `PostgresDiffSqlBuilders.kt` | Länge verwerfen + `TransformationNote` (INFO) |
| Generate SQLite | `.../driver-sqlite/.../SqliteTableDdlSupport.kt` + `SqliteDiffSqlBuilders.kt` | Länge verwerfen + `TransformationNote` (INFO) |
| Serialisierung | `.../adapters/driven/formats/.../SchemaNodeStructureBuilders.kt` + `SchemaNodeStructureParsers.kt` | `prefix_length` schreiben/lesen (erzwingt Objektform) |
| Vertrag | `spec/schema.json` (`$defs/indexColumn`) | `prefix_length` (integer, `minimum: 1`) |
| Round-Trip | `.../hexagon/core/.../diff/migration/MigrationFingerprint.kt` (`:136`/`:156`) | übernimmt den Präfix automatisch via `IndexColumn.toString()` — **kein** Renderer-Edit; nur Golden-Fingerprints neu ziehen. `CanonicalPayload` bleibt unverändert (D-6) |
| Fingerprint-Kompat | `.../hexagon/core/.../diff/migration/MigrationFingerprint.kt` (`ALGORITHM`) | `ALGORITHM`-Tag bumpen — **ein** Edit; `RollbackArtefactBuilder.FINGERPRINT_ALGORITHM` ist dessen Alias und folgt automatisch, `ARTIFACT_HASH_ALGORITHM` bleibt unberührt. Begründung Reverse-Reader/`SUB_PART` (D-2) |
| Diff | `.../hexagon/core/.../diff/TableComparator.kt` (`indexKey` `:267`) | benannte Indizes: „changed" via Data-Class-Gleichheit (`prefixLength`) automatisch. `indexKey` zieht den Präfix bei **unbenannten** Indizes via `toString()` → remove+add (Bestandsverhalten, **kein** Edit) |
| Validierung | `.../hexagon/core/.../validation/SchemaStructureValidationRules.kt` | optional: `prefixLength >= 1` |
| CLI-Anzeige | `.../adapters/driving/cli/.../SchemaCompareHelpers.kt` | optional: Präfix in Diff-Ausgabe zeigen |

## 5. Phasen

> Vorgehen je Phase wie im P2-Block: Ursache/Setup verifizieren → umsetzen →
> Regressionstest → `make docker-check` grün → committen.

- **Phase 1 — Modell + Serialisierung.** Feld einführen, JSON-Builder/-Parser +
  `spec/schema.json` erweitern. Tests: Schema-Round-Trip (JSON ↔ Modell),
  `SchemaJsonContractTest`. Kein Verhaltenswechsel an Generatoren (Feld noch
  `null` in der Praxis).
- **Phase 2 — Reverse MySQL.** `SUB_PART` lesen, `IndexProjection.prefixLengths`
  füllen. Tests: Reader-Unit + Live-MySQL (`integration-mysql`): Tabelle mit
  `INDEX (body(100))` reversen → `prefixLength == 100`.
- **Phase 3 — Generate MySQL.** `col(n)` rendern (Create- und Diff-Pfad). Ohne
  Länge bei TEXT/BLOB → `SkippedObject` + `TransformationNote` (WARNING) (**das
  ist der I-08-MySQL-Kern**). Tests: Generator-Unit + Live-MySQL-Round-Trip
  (Reverse→Generate→akzeptiert).
- **Phase 4 — Generate PG + SQLite.** Präfixlänge verwerfen + Note (Voll-Index
  bleibt gültig). Tests: Generator-Unit je Dialekt.
- **Phase 5 — Round-Trip-Härtung.** (a) `IndexColumn.toString()` um den Präfix
  erweitern (D-5); der Fingerprint (`MigrationFingerprint.kt:136`/`:156`)
  übernimmt ihn dadurch **automatisch** — kein expliziter Projektions-Edit,
  `CanonicalPayload`/Operation-IDs bleiben unangetastet (D-6). (b)
  `MigrationFingerprint.ALGORITHM` bumpen — `FINGERPRINT_ALGORITHM` ist dessen
  Alias (ein Edit, kein zweiter) — + Release-Notes-Eintrag (breaking change).
  (c) Golden-Fingerprints neu ziehen (dokumentierter Rebaseline-Schritt). Tests:
  Fingerprint deterministisch und stabil unter dem neuen Algorithmus; Diff
  erkennt eine Präfixänderung an einem **benannten** Index als „changed"
  (unbenannt → remove+add, s. D-5); Operation-ID-Goldens bleiben grün (D-6);
  Rollback-Artefakt mit altem Algorithmus-Tag wird klar abgelehnt (kein stiller
  Drift-Fehlalarm).

## 6. Designentscheidungen (entschieden 2026-06-17)

- **D-1 — PG/SQLite mit gesetzter Präfixlänge → ENTSCHIEDEN: Voll-Index + Note.**
  PG/SQLite verwerfen die Präfixlänge und emittieren einen gültigen
  Voll-Spalten-Index plus Note „Präfixlänge verworfen (kein Präfix-Index in
  PG/SQLite)". Begründung: Der Index bleibt semantisch erhalten; PG/SQLite
  indizieren den vollen Wert ohnehin. Skip wäre unnötiger Funktionsverlust.
- **D-2 — Fingerprint trägt den Präfix über `toString()`, ALGORITHM-Bump →
  ENTSCHIEDEN.** Korrektur gegenüber der Tiefenprüfung: Der Fingerprint rendert
  Index-Spalten **bereits** via `IndexColumn.toString()`
  (`MigrationFingerprint.kt:136` Projektion, `:156` Sortierung — beide
  `columns.joinToString(",")` **ohne** Selector) und trägt damit heute schon die
  **Richtung**. Es gibt also keine „Richtungslücke" im Fingerprint und keinen
  separaten Renderer-Edit: sobald D-5 den Präfix in `toString()` aufnimmt, fließt
  er automatisch in Hash und Sortierung. (`CanonicalPayload.index()` rendert
  dagegen name-only über eigenen Selector und ist nicht betroffen → D-6.)
  Bestehende Golden-Fingerprints werden in Phase 5 bewusst neu gezogen
  (dokumentierter Rebaseline-Schritt, nicht stillschweigend).

  **Kompatibilität (wichtig):** Der Migration-Fingerprint ist kein reines
  Test-Artefakt. `MigrationFingerprint.compute` speist `currentFingerprint`/
  `desiredFingerprint` (Migrationsreports), die `postUpFingerprint`/
  `allowedPostUpFingerprints`-Metadaten in `--rollback-output`-Artefakten
  (`RollbackArtefactBuilder`) sowie den Post-`--execute`-Compare und die
  `schema rollback`-Drift-Checks (`SchemaMigrateRunner`). Der eigentliche
  Versionsbruch ist **nicht** das Projektionsformat (Richtung ist schon drin),
  sondern der **Reverse-Reader**: dieselbe Live-MySQL liefert nach dem Upgrade
  einen anderen Hash, weil `listIndices` jetzt `SUB_PART` einliest und der Präfix
  in den Fingerprint einfließt. Ein altes Rollback-Artefakt trägt seinen
  `postUpFingerprint`/`allowedPostUpFingerprints` nach präfixlosem Reader; die
  neue Version rechnet mit Präfix neu → ohne Gegenmaßnahme meldet der Drift-Check
  fälschlich „Schema-Drift". Daher in Phase 5 `MigrationFingerprint.ALGORITHM`
  bumpen (z. B. `schema-fingerprint-v2`); der Algorithmus-String steht in der
  Projektion (`MigrationFingerprint.kt:88`), der Bump invalidiert also **alle**
  alten Hashes bewusst, und die neue Version erkennt am abweichenden
  `fingerprintAlgorithm`-Tag das inkompatible Artefakt und lehnt klar ab, statt
  stillen Drift zu melden. `FINGERPRINT_ALGORITHM` (`RollbackArtefactBuilder.kt:34`)
  ist ein **Alias** von `MigrationFingerprint.ALGORITHM` → ein Edit, kein
  zweiter; `ARTIFACT_HASH_ALGORITHM` bleibt unberührt. Eintrag in den
  Release-Notes als breaking change.
- **D-4 — PRIMARY-KEY-/Constraint-Präfixlängen → ENTSCHIEDEN: out of scope.**
  PK liegt als `TableDefinition.primaryKey: List<String>`, UNIQUE-/FK-Constraints
  als `ConstraintDefinition.columns: List<String>` — beide können keine
  Präfixlänge tragen. MySQL erlaubt zwar `PRIMARY KEY (col(100))`, doch das
  verlangt ein eigenes Modell-Refactoring (String-Liste → strukturierte Spalten)
  mit eigenem Serialisierungs-/Fingerprint-Delta und gehört in einen separaten
  Slice. **Wichtig zur Reichweite:** In MySQL gibt es keinen separaten
  Constraint-Pfad für UNIQUE — `UNIQUE KEY`s (auch präfigierte, z. B.
  `UNIQUE KEY uk (col(255))`) erscheinen in `information_schema.statistics` und
  laufen über `listIndices` → `IndexProjection(isUnique=true)` →
  `IndexDefinition(unique=true)`, sind also **abgedeckt**. „UNIQUE als Constraint
  nicht abgedeckt" betrifft daher nur Modelle/Dialekte, die UNIQUE als
  `ConstraintDefinition` führen (z. B. PG), sowie generisch das
  `ConstraintDefinition`-Modell. PRIMARY KEY ist in allen Dialekten ausgenommen
  (in MySQL via `index_name != 'PRIMARY'` aus `listIndices` ausgeschlossen) und
  verliert bis zum Folge-Slice seine Länge (bekannte Einschränkung — als eigener
  Eintrag im Folge-/P2-Tracker zu führen).
- **D-5 — `IndexColumn.toString()` → ENTSCHIEDEN: Präfix aufnehmen (Variante a).**
  Heute rendert toString `name` bzw. `name DIR`; künftig `name` / `name(255)` /
  `name DIR` / `name(255) DIR`. toString ist **nicht** nur kosmetisch — drei
  Round-Trip-Pfade rendern Index-Spalten via toString (`joinToString` ohne
  Selector) und übernehmen den Präfix damit automatisch:
  `MigrationFingerprint.kt:136` (Projektion) und `:156` (Sortierung) → Präfix in
  Hash + Reihenfolge (erwünscht, D-2); `TableComparator.kt:267` `indexKey` →
  Präfix im Match-Key **nur** für unbenannte Indizes (Konsequenz: deren reine
  Präfixänderung wird remove+add statt „changed" — konsistent mit dem
  Bestandsverhalten, das unbenannte Indizes ohnehin über ihren Volltext matcht;
  benannte Indizes matchen über den Namen und werden korrekt „changed").
  `CanonicalPayload.index()` (`:71`) und `indexOrder` (`:116`) rendern dagegen
  name-only über eigenen Selector und bleiben unberührt (→ D-6). Variante (b) —
  eine explizite Projektions-Umstellung auf ein Sonderformat wie
  `name:dir(prefix)` — wäre redundant (toString ist bereits die kanonische
  Spaltendarstellung) und erzwänge Doppelpflege; **verworfen**.
- **D-6 — `CanonicalPayload`/Operation-IDs → ENTSCHIEDEN: bewusst unangetastet.**
  `CanonicalPayload.index()` (`:71`) und `indexOrder` (`:116`) rendern Spalten
  name-only und sind vom D-5-toString-Change nicht betroffen. Eine Änderung ist
  für den Präfix-Round-Trip auch nicht nötig: die Präfix-Erkennung im Diff hängt
  an `TableComparator`-Data-Class-Gleichheit (`IndexColumn` trägt `prefixLength`)
  und am Fingerprint, **nicht** an der Operation-ID. `CanonicalPayload`
  absichtlich unverändert zu lassen vermeidet zudem einen Operation-ID-Bruch in
  bereits emittierten Artefakten — der Klassen-Doc warnt explizit „*Bumping the
  format requires invalidating Operation IDs in artefacts*"
  (`CanonicalPayload.kt:29`), und anders als beim Fingerprint gibt es **keinen**
  Versions-Tag auf `OperationIdFactory`, der das signalisieren könnte.
  Konsequenz: zwei `ReplaceIndex`-Operationen, die sich nur in der Präfixlänge
  unterscheiden, erhalten dieselbe Operation-ID und werden über die
  `#N`-Disambiguierung getrennt (degenerierter, contractually erlaubter Fall) —
  akzeptabel, da die ID nur Identität/Label ist, keine Inhaltsdetektion. Die
  bestehende, hiervon unabhängige Auslassung der Richtung in `CanonicalPayload`
  bleibt als Vorzustand erhalten (separater Gegenstand, nicht Teil dieser
  Scheibe).
- **D-3 — Reverse-Quelle PG/SQLite → ENTSCHIEDEN: `null` ist korrekt.** Beide
  kennen kein Präfix-Index-Konzept; es gibt keinen verdeckten Pfad. PG-
  Expression-/Operator-Class-Indizes sind ein separater Gegenstand.

## 7. Akzeptanzkriterien

- MySQL-Tabelle mit `INDEX (col(n))` → Reverse → Generate → MySQL akzeptiert
  identisches DDL (Live-Test grün); `prefixLength` bleibt erhalten.
- MySQL-Ziel, `TEXT`/`BLOB`-Spalte ohne Präfixlänge → **kein** `ERROR 1170`:
  Index wird mit Note geskippt (I-08-Akzeptanz).
- PG-/SQLite-Ziel mit Präfixlänge → gültiges Voll-Index-DDL + Note.
- JSON-Schema-Round-Trip erhält `prefix_length`; `spec/schema.json` validiert
  Beispiel-Schemata mit Präfixlänge.
- Eine reine Präfixänderung an einem **benannten** Index erscheint im Diff als
  „changed" und verändert den Migration-Fingerprint deterministisch; bei einem
  **unbenannten** Index erscheint sie als remove+add (Bestandsverhalten, D-5)
  und verändert den Fingerprint ebenfalls deterministisch.
- `MigrationFingerprint.ALGORITHM` ist gebumpt (`FINGERPRINT_ALGORITHM` ist
  dessen Alias und folgt automatisch — ein Edit); ein Rollback-Artefakt mit altem
  Algorithmus-Tag wird von der neuen Version klar abgelehnt (kein stiller
  Drift-Fehlalarm); Release-Notes nennen den Fingerprint-Bruch.
- `CanonicalPayload`/Operation-IDs sind unverändert (D-6); bestehende
  Operation-ID-Goldens bleiben grün.
- Präfigierter PRIMARY KEY ist **nicht** Teil der Akzeptanz (out of scope, D-4).
- `make docker-check` für alle berührten Module grün (inkl. `koverVerify`),
  Live-MySQL-Integration grün.

## 8. Verhältnis zum P2-Block / I-08

Dieser Plan **ersetzt den MySQL-Teil von I-08** durch die saubere Modell-Lösung
(Phase 3 liefert die I-08-Skip+Note-Akzeptanz). Der **PG-Teil von I-08** (GIST
auf `text`-degradierter Spalte ohne Operator-Class) ist davon **unabhängig** und
bleibt im P2-Tracker.

**Sequenz (entschieden 2026-06-17): Variante 2.** Zuerst die kleineren,
abgeschlossenen P2-Blocker (I-07, I-09 und der **PG-Teil von I-08**), danach
diese Präfixlängen-Modellscheibe als eigener Slice. Begründung: Sie ist die
größte und riskanteste Änderung (Serialisierungsvertrag + Fingerprints +
Golden-Files) und verdient einen fokussierten Slice; die kleinen Blocker bringen
den Piloten schneller Richtung RC. Der P2-Tracker führt den **MySQL-Teil von
I-08** ab sofort als „verlagert in [diesen Plan]".
