# Index-Präfixlänge als Modellfeld (`IndexColumn.prefixLength`)

**Status**: Entwurf (2026-06-17 — Scope + Blast-Radius kartiert, Phasenschnitt
und Akzeptanzkriterien ausgearbeitet; bereit für Review).

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
- Round-Trip-Stabilität: Canonical-Payload + Migration-Fingerprint + Comparator.
- Regressionstests je betroffenem Modul + Live-MySQL-Round-Trip.

### 3.2 Out of Scope

- Präfix-Indizes über **mehrere** Spalten mit gemischten Längen jenseits dessen,
  was `SUB_PART` pro Spalte liefert (wird automatisch abgedeckt, da pro
  `IndexColumn` getragen — aber keine darüber hinausgehende Heuristik).
- Funktions-/Expression-Indizes (PG) — separater Gegenstand.
- Automatische **Wahl** einer Präfixlänge, wenn keine vorliegt (bleibt Skip+Note;
  kein Raten — vgl. Sitzungsentscheid gegen Option B).

## 4. Blast-Radius (kartiert 2026-06-17)

| Bereich | Datei(en) | Änderung |
| --- | --- | --- |
| Modell | `hexagon/core/.../model/IndexDefinition.kt` | Feld `prefixLength` + ggf. `toString` |
| Reverse-Projektion | `adapters/driven/driver-common/.../metadata/MetadataProjections.kt` | `IndexProjection.prefixLengths` parallele Liste; `indexColumns`-Getter |
| Reverse MySQL | `.../driver-mysql/.../MysqlMetadataQueries.kt` | `SUB_PART` in `listIndices`-SELECT + Mapping |
| Reverse PG/SQLite | `.../driver-postgresql/...`, `.../driver-sqlite/...` | keine Quelle → `null` (kein Präfix-Konzept) |
| Generate MySQL | `.../driver-mysql/.../MysqlIndexPartitionDdlHelper.kt` (`renderIndexColumn`) + `MysqlDiffSqlBuilders.kt` | `col(n)` rendern; ohne Länge bei TEXT/BLOB → Skip+Note (I-08) |
| Generate PG | `.../driver-postgresql/.../PostgresDdlGenerator.kt` (`renderIndexColumn`) + `PostgresDiffSqlBuilders.kt` | Länge verwerfen + Note |
| Generate SQLite | `.../driver-sqlite/.../SqliteTableDdlSupport.kt` + `SqliteDiffSqlBuilders.kt` | Länge verwerfen + Note |
| Serialisierung | `adapters/driven/formats/.../SchemaNodeStructureBuilders.kt` + `SchemaNodeStructureParsers.kt` | `prefix_length` schreiben/lesen (erzwingt Objektform) |
| Vertrag | `spec/schema.json` (`$defs/indexColumn`) | `prefix_length` (integer, `minimum: 1`) |
| Round-Trip | `hexagon/core/.../diff/migration/CanonicalPayload.kt` + `MigrationFingerprint.kt` | Spalten als `name(prefix)` rendern (D-2) |
| Diff | `hexagon/core/.../diff/TableComparator.kt` | greift über Data-Class-Gleichheit automatisch; `indexKey` ggf. ergänzen |
| Validierung | `hexagon/core/.../validation/SchemaStructureValidationRules.kt` | optional: `prefixLength >= 1` |
| CLI-Anzeige | `adapters/driving/cli/.../SchemaCompareHelpers.kt` | optional: Präfix in Diff-Ausgabe zeigen |

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
  Länge bei TEXT/BLOB → Skip+Note (**das ist der I-08-MySQL-Kern**). Tests:
  Generator-Unit + Live-MySQL-Round-Trip (Reverse→Generate→akzeptiert).
- **Phase 4 — Generate PG + SQLite.** Präfixlänge verwerfen + Note (Voll-Index
  bleibt gültig). Tests: Generator-Unit je Dialekt.
- **Phase 5 — Round-Trip-Härtung.** Canonical-Payload + Fingerprint um Präfix
  ergänzen (D-2), Comparator-/`indexKey`-Check. Tests: Fingerprint-Stabilität,
  Diff erkennt Präfixänderung als „changed".

## 6. Designentscheidungen (entschieden 2026-06-17)

- **D-1 — PG/SQLite mit gesetzter Präfixlänge → ENTSCHIEDEN: Voll-Index + Note.**
  PG/SQLite verwerfen die Präfixlänge und emittieren einen gültigen
  Voll-Spalten-Index plus Note „Präfixlänge verworfen (kein Präfix-Index in
  PG/SQLite)". Begründung: Der Index bleibt semantisch erhalten; PG/SQLite
  indizieren den vollen Wert ohnehin. Skip wäre unnötiger Funktionsverlust.
- **D-2 — Canonical-Payload/Fingerprint → ENTSCHIEDEN: Präfix UND Richtung
  aufnehmen.** Spalten werden als `name[:dir][(prefix)]` gerendert. Heute trägt
  `CanonicalPayload.index()` keines von beidem; für Präfix-Round-Trip muss die
  Länge rein, und die Richtung wird in derselben Phase mitgenommen (latente
  Lücke). Konsequenz: bestehende Golden-Fingerprints werden in Phase 5 bewusst
  neu gezogen (dokumentierter Rebaseline-Schritt, nicht stillschweigend).
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
- Eine reine Präfixänderung erscheint im Diff als „changed" und verändert den
  Migration-Fingerprint deterministisch.
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
