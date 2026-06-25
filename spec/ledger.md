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

Pro Minor-Version (z.B. 0.9.2, 0.9.3) existiert ein eigener Satz
Ledger-Dateien. Die Dateien einer aelteren Version werden nicht
veraendert — neue Codes oder Statuswechsel kommen in die Dateien der
neuen Version.

---

## Code-Nummernbereiche

| Bereich | Zuordnung |
|---------|-----------|
| E001 - E020 | Schema-Validierung (SchemaValidator) |
| E052 - E056 | Dialekt-Inkompatibilitaeten (Composite, Routine, Sequence) |
| E057 | Multi-Dialekt action_required: MySQL Partial-Index-Predicate, SQLite-helper_table `WITHOUT ROWID` + `SequenceNextVal` (ab 0.9.7) |
| E058 | SQLite-helper_table: externe Objekte referenzieren `dmg_sequences`; Rollback-Preflight bricht ab (ab 0.9.7) |
| E059 | SQLite-helper_table: Sequence-backed column im PRIMARY KEY (ab 0.9.7) |
| E060 | Split-Diagnostik (Phasenkonflikt); SQLite-helper_table-Rollback zusätzlich: ATTACHed Datenbanken detektiert (ab 0.9.7) |
| E061 - E065 | Cross-Dialect-Partitionierung PG↔MySQL (ADR 0020): Non-UTC-Grenze (Shift), nicht abbildbarer Schlüsseltyp, LIST-DEFAULT-Verwurf (Transfer-Verlust), nicht hebbarer UNIQUE-Partition-Index, FK auf partitionierter Tabelle (ab 0.9.9) |
| E120 - E121 | Erweiterte Validierung (Trigger, Views) |
| E122 - E123 | Sequence-Default-Validierung (ab 0.9.3) |
| E124 | Support-Namenskollision (ab 0.9.3) |
| E125 | Sequence-internal-Validierung (Inkrement/Bereich, ab 0.9.7) |
| W001 | Float-fuer-Geldbetraege-Warnung |
| W100 - W112 | Dialekt-Warnungen (Typ-Mapping, Index-Konvertierung) |
| W113 | Circular-FK-Warnung |
| W114 - W117 | Sequence-Emulation-Warnungen (MySQL ab 0.9.3, SQLite ab 0.9.7) |
| W119 | SQLite-helper_table: NOT-NULL- und CHECK-`IS NOT NULL`-Suppression auf sequence-getragener Spalte (ab 0.9.7) |
| W120 | Multi-Dialekt: MySQL SRID-Hinweis, SQLite-helper_table-Reverse Trigger-Body-modifiziert (ab 0.9.7) |
| W121 | SQLite-helper_table: Conflict-Gap-INFO (ab 0.9.7) |
| W122 | SQLite-helper_table: AFTER-INSERT-Sequence-Trigger feuert UPDATE auf Zieltabelle (ab 0.9.7) |
| W123 | SQLite-helper_table-Rollback: ATTACHed Datenbanken detektiert (ab 0.9.7) |
| W124 | SQLite-helper_table-Reverse: User-BEFORE-INSERT-Trigger maskiert kanonisches `_bi`-Paar (ab 0.9.7) |
| W125 - W126 | Index-Präfixlängen: MySQL-Index auf TEXT/BLOB ohne Präfix übersprungen; PG/SQLite verwerfen eine MySQL-Präfixlänge (Voll-Spalten-Index, ab 0.9.9) |
| W129 - W131 | Cross-Dialect-Partitionierung PG↔MySQL (ADR 0020): timestamptz→UTC-Normalisierung, HASH-Platzierungsdivergenz, gehobener Partition-Index umbenannt (ab 0.9.9) |

Neue Codes werden am Ende des jeweiligen Bereichs angefuegt.
Luecken (z.B. E021-E051) sind reservierte Bereiche fuer kuenftige
Erweiterungen.

---

## Erlaubte Statuse

| Status | Bedeutung | Pflichtfelder |
|--------|-----------|---------------|
| `active` | Code wird im Produktionscode emittiert | `test_path`, `evidence_paths` |
| `not_applicable` | Code existiert nicht (Luecke) oder ist in dieser Version nicht anwendbar | `note` (empfohlen) |
| `reserved` | Code ist definiert und dokumentiert, aber noch nicht emittiert (ab 0.9.3) | `note` mit Verweis auf das Arbeitspaket, das ihn aktiviert |

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
