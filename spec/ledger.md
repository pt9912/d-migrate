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
| E060 | Split-Diagnostik (Phasenkonflikt) |
| E120 - E121 | Erweiterte Validierung (Trigger, Views) |
| E122 - E123 | Sequence-Default-Validierung (ab 0.9.3) |
| E124 | Support-Namenskollision (ab 0.9.3) |
| W001 | Float-fuer-Geldbetraege-Warnung |
| W100 - W112 | Dialekt-Warnungen (Typ-Mapping, Index-Konvertierung) |
| W113 | Circular-FK-Warnung |
| W114 - W117 | Sequence-Emulation-Warnungen (ab 0.9.3) |
| W120 | SRID-Kommentar-Hinweis (MySQL) |

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
