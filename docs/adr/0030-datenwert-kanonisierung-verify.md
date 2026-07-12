---
status: accepted
date: 2026-07-12
decision-makers: pt9912
consulted: spec/lastenheft-d-migrate.md, docs/planning/done/ImpPlan-1.0.0-RC-ln009-sha256-verify.md
informed: hexagon/ports-common, adapters/driven/formats, hexagon/application, docs/planning/in-progress/roadmap.md
---

# Datenwert-Kanonisierung für `data transfer --verify` (LN-009)

> **Status: accepted (2026-07-12).** Die SHA-256-Quelle↔Ziel-Reconciliation von
> `data transfer --verify` vergleicht **dialekt-neutrale kanonische Byte-Formen**
> je Zeile, kombiniert reihenfolge-unabhängig zu einer Tabellen-Prüfsumme und
> schließt repräsentations-transformierende Cross-Dialekt-Spalten ehrlich aus.

## Kontext und Problemstellung

[`LN-009`](../../spec/lastenheft-d-migrate.md#ln-009) verlangt, Datenverlust bei
Import/Export auszuschließen; LF 8.5 nennt dafür den „Byte-für-Byte-Vergleich
(SHA-256 Hash)". Als nutzerseitiges Feature (`data transfer --verify`) muss die
Quelle gegen das Ziel abgeglichen werden — auch **cross-dialect** (PostgreSQL →
MySQL → SQLite).

Ein roher Byte-Vergleich der JDBC-Ergebnismengen ist cross-dialect unbrauchbar:
dasselbe logische Datum kommt je Dialekt unterschiedlich zurück (`boolean` `t/f`
vs. `tinyint` `1/0`, `numeric(10,2)` `1.50` vs. `1.5`, `timestamptz` mit Offset
vs. UTC, NULL vs. Leerstring, Binär-Encoding). Verify braucht also eine
**kanonische, dialekt-neutrale Repräsentation** je Wert, unabhängig auf Quelle
und Ziel berechnet und dann verglichen.

Dies ist das **Wertebene-Analog** zu [ADR 0026](0026-fingerprint-kanonisierung-post-compare.md)
(Schema-Fingerprint-Kanonik): dort werden Neutraltypen auf die Speicher-Realität
des Ziel-Dialekts projiziert, damit ein verlustfreier Round-Trip keine Drift
meldet. Hier gilt dasselbe Prinzip für die **Daten**.

## Entscheidung

### D1 — Kanonische Form je NeutralType

Ein `ValueCanonicalizer` (Port in `hexagon:ports-common`, Impl `CanonicalValueCodec`
im `formats`-Adapter) bildet einen Nicht-Null-Rohwert + seinen `NeutralType` auf
kanonische Bytes ab:

| NeutralType | Kanonische Byte-Form |
|---|---|
| `Text`/`Xml`/`Email`/`Enum`/`FullText` | UTF-8 des Strings |
| `Char(n)` | UTF-8, trailing-Space normalisiert (PG-Pad vs MySQL-Trim) |
| `Uuid` | UTF-8 des Lowercase-Hyphen-Strings |
| `Integer`/`SmallInt`/`BigInteger`/`Identifier` | Dezimalstring ohne Leading-Zeros; Boolean→`1`/`0` |
| `Decimal` | `stripTrailingZeros().toPlainString()` (1.50 == 1.5) |
| `Float` | kürzeste round-trip-Dezimale (gleich-breite Floats kollidieren) |
| `BooleanType` | `1`/`0` (kollidiert mit Integer) |
| `Date` / `Time` | ISO-8601 (Time: fractional normalisiert) |
| `DateTime(tz=false)` | ISO local datetime |
| `DateTime(tz=true)` | auf **UTC-Instant** normalisiert |
| `Json` | Jackson-Reparse, Object-Keys rekursiv sortiert, Zahlen normalisiert |
| `Array(elem)` | rekursiv je Element, längen-gerahmt |
| `Binary` | rohe Bytes |
| `Geometry` | WKB (little-endian, ISO-Typ), SRID + Byte-Order normalisiert |

Die Formen sind so entworfen, dass **flattening-äquivalente** Werte kollidieren
(Boolean unter numerischem Ziel → `1`/`0`; UUID unter Text-Ziel → Lowercase-String).

### D2 — Reihenfolge-unabhängige Tabellen-Prüfsumme

Pro Zeile: Spalten **namensgeordnet**, jeder Wert **längen-gerahmt**
(`[present-byte][varint-len][bytes]`) in ein Pro-Zeilen-SHA-256; NULL ist ein
eigener Frame-Tag (≠ Leerstring). Die Tabellen-Prüfsumme ist die **additive
Summe der 256-bit-Zeilendigests mod 2²⁵⁶** — reihenfolge-unabhängig (kein
`ORDER BY`, [`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)-freundlich) und **multiset-korrekt** (anders als XOR, wo sich
identische Zeilenpaare auslöschen). Vertrag: Schutz gegen **versehentliche**
Korruption/Datenverlust, **nicht** gegen adversariell konstruierte Kollisionen.

### D3 — Kanonisierung je eigenem Typ, nicht Wert-Projektion

Jede Seite kanonisiert gegen ihren **eigenen** reverse-engineerten Spaltentyp.
Eine Projektion der *Werte* auf den Zieltyp (analog zur Schema-Projektion in ADR
0026) funktioniert **nicht**: der Quell-Treiber liefert weiterhin den
quell-typisierten Wert (z. B. `java.sql.Array`), egal welchen Typ das Ziel
speichert. Innerhalb einer Kanonik-Familie kollidieren die Formen ohnehin
(bool↔int, uuid↔text, decimal-Weite), sodass keine Projektion nötig ist.

### D4 — Familien-basierter, ehrlicher Ausschluss

Byte-Kanonik kann eine **repräsentations-transformierende** Konversion nicht
bestätigen (`text[]`→`json`, `tsvector`→`text`, tz-behaftet→lokal, `datetime`→
`text` bei SQLite). Solche Spalten — deren Quell- und Zieltyp in **verschiedenen
Kanonik-Familien** liegen (`text`, `numeric`, `float`, `date`, `time`,
`datetime-tz`, `datetime-local`, `json`, `array`, `binary`, `geometry`,
`fulltext`) — werden mit einem **W-Code ausgeschlossen** und im Verify-Report
gelistet. Kein False-Positive, kein stiller Pass. Ein Wert, der gar nicht
kanonisierbar ist (unbekanntes Treiber-Objekt, nicht-parsebares JSON, SpatiaLite-
BLOB), macht die Tabelle **inkonklusiv** (Fehler, Exit 3).

### D5 — Exit-Code

Verify-Divergenz (oder inkonklusive Tabelle) → CLI-Exit **3** („Validation
failed", [`job-contract.md`](../../spec/job-contract.md) 8.1). Kein neuer Code —
die Divergenz ist eine fehlgeschlagene Validierung (REST 422 / gRPC
INVALID_ARGUMENT passen).

## Konsequenzen

- **Same-dialect** (PG→PG): jede Spalte byte-exakt verifiziert.
- **Cross-dialect**: alle Spalten gleicher Kanonik-Familie werden verifiziert;
  repräsentations-transformierende Spalten werden mit W-Code ausgeschlossen (der
  Nutzer sieht genau, was byte-verifiziert wurde und was nicht).
- **Scope-Grenze**: Verify prüft einen **sauberen Load** (leeres/getrunctes Ziel).
  Inkrementell/`--on-conflict merge` in ein nicht-leeres Ziel ist Nicht-Ziel.
- **Nicht adversariell** (D2): additive Set-Kanonik detektiert Zufallskorruption,
  keine konstruierten Kollisionen.

## Referenzen

- [`LN-009`](../../spec/lastenheft-d-migrate.md#ln-009), LF 8.5 (Datenintegritätstests).
- [ADR 0026](0026-fingerprint-kanonisierung-post-compare.md) — Schema-Fingerprint-Kanonik (Vorbild).
- [`job-contract.md`](../../spec/job-contract.md) 8.1 (Exit-Codes).
- ImpPlan: [`ImpPlan-1.0.0-RC-ln009-sha256-verify.md`](../planning/done/ImpPlan-1.0.0-RC-ln009-sha256-verify.md).
