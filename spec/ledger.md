# Code-Ledger-System

Dieses Dokument beschreibt die Konventionen des Error-/Warning-Code-
Ledger-Systems. Fuer die maschinenlesbare Struktur siehe das JSON-Schema
unter `ledger/code-ledger-<version>.schema.json`.

---

## Zweck

Jeder nutzersichtbare Error- oder Warning-Code in d-migrate ist im
Ledger registriert. Das Ledger stellt sicher, dass:

- kein Code ohne Test und Evidence existiert
- Codes versioniert und nachvollziehbar eingefuehrt werden
- Code-Luecken und Statuswechsel explizit dokumentiert sind
- `CodeLedgerValidationTest` die Einhaltung automatisch prueft

---

## Dateikonvention

Alle Ledger-Dateien liegen unter `ledger/`:

| Datei | Zweck |
|-------|-------|
| `error-code-ledger-<version>.yaml` | Alle Error-Codes (E-Serie) fuer eine Version |
| `warn-code-ledger-<version>.yaml` | Alle Warning-Codes (W-Serie) fuer eine Version |
| `code-ledger-<version>.schema.json` | JSON-Schema fuer die YAML-Dateien |
| `ddl-single-exceptions-<version>.yaml` | Dokumentierte Golden-Master-Abweichungen |

Pro Minor-Version existiert ein eigener Satz
Ledger-Dateien. Die Dateien einer aelteren Version werden nicht
veraendert — neue Codes oder Statuswechsel kommen in die Dateien der
neuen Version.

---

## Code-Nummernbereiche

| Bereich | Zuordnung |
|---------|-----------|
| E001 - E020 | Schema-Validierung (SchemaValidator) |
| E052 - E056 | Dialekt-Inkompatibilitaeten (Composite, Routine, Sequence) |
| E057 | Multi-Dialekt action_required: MySQL Partial-Index-Predicate, SQLite-helper_table `WITHOUT ROWID` + `SequenceNextVal` |
| E058 | SQLite-helper_table: externe Objekte referenzieren `dmg_sequences`; Rollback-Preflight bricht ab |
| E059 | SQLite-helper_table: Sequence-backed column im PRIMARY KEY |
| E060 | Split-Diagnostik (Phasenkonflikt); SQLite-helper_table-Rollback zusätzlich: ATTACHed Datenbanken detektiert |
| E061 - E065 | Cross-Dialect-Partitionierung PG↔MySQL: Non-UTC-Grenze (Shift), nicht abbildbarer Schlüsseltyp, LIST-DEFAULT-Verwurf (Transfer-Verlust), nicht hebbarer UNIQUE-Partition-Index, FK auf partitionierter Tabelle |
| E120 - E121 | Erweiterte Validierung (Trigger, Views) |
| E122 - E123 | Sequence-Default-Validierung |
| E124 | Support-Namenskollision |
| E125 | Sequence-internal-Validierung (Inkrement/Bereich) |
| W001 | Float-fuer-Geldbetraege-Warnung |
| W100 - W112 | Dialekt-Warnungen (Typ-Mapping, Index-Konvertierung) |
| W113 | Circular-FK-Warnung |
| W114 - W117 | Sequence-Emulation-Warnungen (MySQL, SQLite) |
| W119 | SQLite-helper_table: NOT-NULL- und CHECK-`IS NOT NULL`-Suppression auf sequence-getragener Spalte |
| W120 | Multi-Dialekt: MySQL SRID-Hinweis, SQLite-helper_table-Reverse Trigger-Body-modifiziert |
| W121 | SQLite-helper_table: Conflict-Gap-INFO |
| W122 | SQLite-helper_table: AFTER-INSERT-Sequence-Trigger feuert UPDATE auf Zieltabelle |
| W123 | SQLite-helper_table-Rollback: ATTACHed Datenbanken detektiert |
| W124 | SQLite-helper_table-Reverse: User-BEFORE-INSERT-Trigger maskiert kanonisches `_bi`-Paar |
| W125 - W126 | Index-Präfixlängen: MySQL-Index auf TEXT/BLOB ohne Präfix übersprungen; PG/SQLite verwerfen eine MySQL-Präfixlänge (Voll-Spalten-Index) |
| W129 - W131 | Cross-Dialect-Partitionierung PG↔MySQL: timestamptz→UTC-Normalisierung, HASH-Platzierungsdivergenz, gehobener Partition-Index umbenannt |
| W132 | Cross-Dialect: `fulltext`-Spalte zu TEXT degradiert (Volltext-Suche nicht erhalten; manueller `FULLTEXT`-Index in MySQL bzw. FTS5-Virtual-Table in SQLite nötig) |
| W133 | PostgreSQL-Generate: `FULLTEXT`-Index ohne `tsvector`-Spalte nicht expandierbar (keine GiST-Volltext-Spalte vorhanden; manuell `tsvector`-Spalte + `tsvector_update_trigger` ergänzen oder MySQL/SQLite als Ziel wählen) |
| W134 | Migrate: `enum`-Spalte als bloßes `TEXT` migriert, Werte nicht durchgesetzt (PostgreSQL inline-`values`-Enum ohne `refType` sowie alle SQLite-Enums; nativ round-trippen nur MySQL `ENUM` und PG-`refType`-Typreferenz — für inline-Fidelity Custom-Type/`refType` verwenden) |

Neue Codes werden am Ende des jeweiligen Bereichs angefuegt.
Luecken (z.B. E021-E051) sind reservierte Bereiche fuer kuenftige
Erweiterungen.

---

## Erlaubte Statuse

| Status | Bedeutung | Pflichtfelder |
|--------|-----------|---------------|
| `active` | Code wird im Produktionscode emittiert | `test_path`, `evidence_paths` |
| `not_applicable` | Code existiert nicht (Luecke) oder ist in dieser Version nicht anwendbar | `note` (empfohlen) |
| `reserved` | Code ist definiert und dokumentiert, aber noch nicht emittiert | `note` mit Verweis auf das Arbeitspaket, das ihn aktiviert |

Die `if/then`-Regel im JSON-Schema erzwingt `test_path` und
`evidence_paths` nur fuer `status: active`.

---

## Entry-Typen

| Typ | Bedeutung |
|-----|-----------|
| `standard` | Regulaerer Code mit Test- und Evidence-Nachweis |
| `rest_path` | Code, dessen Testabdeckung noch nicht automatisiert ist; erfordert zusaetzlich `why_not_automated`, `evidence_owner`, `priority`, `planned_remediation` |

---

## Lebenszyklus eines Codes

```
1. Planung     → Code-Nummer reservieren, im WP-Plan dokumentieren
2. Ledger      → Eintrag mit status: reserved + note: "aktiviert in WP 6.x"
3. Implementierung → Emittierenden Code und Test schreiben
4. Hochstufung → status: active, test_path + evidence_paths ergaenzen
```

Ein Code darf **nicht** als `active` eingetragen werden, solange kein
emittierender Code und kein Test existieren — `CodeLedgerValidationTest`
prueft, dass aktive Eintraege gueltige `test_path`- und
`evidence_paths`-Verweise auf existierende Dateien haben.

---

## Validierung

`CodeLedgerValidationTest` (unter
`hexagon/core/src/test/kotlin/dev/dmigrate/core/validation/`)
prueft pro Version:

- Schema-Datei existiert
- Keine Duplikat-Codes
- Gueltige `level`-, `entry_type`-, `status`-Werte
- `active`-Eintraege haben `test_path` und `evidence_paths`
- Referenzierte Dateien existieren im Repo
- `evidence_paths` haben gueltige `path_type`-Werte
- `rest_path`-Eintraege haben die Pflichtfelder

---

## Evidence-Pfade

Jeder aktive Eintrag verweist ueber `evidence_paths` auf die Dateien,
die den Code belegen:

| `path_type` | Bedeutung |
|-------------|-----------|
| `production` | Produktionscode, der den Code emittiert |
| `test` | Testcode, der den Code ausubt |
| `documentation` | Dokumentation, die den Code beschreibt |
