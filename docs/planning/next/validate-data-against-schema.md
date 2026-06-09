# `validate data` — Datendatei gegen Schema-Definition validieren

**Status**: Entwurf (2026-06-09 — Semantik geklärt, vorhandene Bausteine kartiert, Scope + offene Designfragen ausgearbeitet; bereit für Review).

**Trigger**: `validate data` ist in [cli-spec.md](../../../spec/cli-spec.md) (Abschnitt
`validate`) als bloßes „Geplant." spezifiziert — als einziges Kommando **ohne
jeden Anker**: kein `LF`/`LN`, keine Roadmap, kein normativer Spec-Abschnitt
(nur Verwendungsbeispiele in `cli-spec.md` und `spec/design.md`), kein Code.
Aufgefallen bei der Referenz-/Provenienz-Durchsicht der „Geplant"-Marker am
2026-06-09. Statt es als „nicht eingeplant" zu markieren oder zu entfernen,
wird der Scope hier ausgearbeitet.

**Aktivierungsbedingung** (Move nach `in-progress/`): Lastenheft-Backfill
(neue `LF`-Kennung, siehe Vorbedingungen) **und** Klärung der offenen
Designfrage Tabellen-Zuordnung.

---

## 1. Bedeutung

DB-freie **Daten-gegen-Schema-Konformitätsprüfung**: Eine Datendatei
(JSON/YAML/CSV) wird gegen eine neutrale Schema-Definition geprüft und ein
Konformitäts-Report ausgegeben. Kein Datenbank-Zugriff, kein Import.

Geprüft werden pro Datensatz gegen die Spaltendefinition der Zieltabelle:
- Spalten-Präsenz (Pflichtspalten vorhanden, keine unbekannten Spalten)
- Typ-Konformität (Wert passt zum Spaltentyp)
- Nullability (NOT-NULL nicht verletzt)
- Länge/Präzision (z. B. `VARCHAR(n)`, `DECIMAL(p,s)`)
- CHECK-Constraints und Enum-/Custom-Type-Zugehörigkeit

## 2. Abgrenzung

- **`schema validate`** (implementiert, [cli-spec.md](../../../spec/cli-spec.md))
  prüft die *Schema-Datei selbst* auf Korrektheit — keine Daten.
- **`data import`-Preflight** (`ImportPreflightValidator`,
  `schemaTargetValidator` in `hexagon/application/.../cli/commands/`) prüft
  Spalten-Matching gegen ein Schema, aber im Kontext eines Live-DB-Imports.
  `validate data` ist die eigenständige, DB-freie Trockenvariante.

## 3. Vorhandene Bausteine (wiederverwenden, nicht duplizieren)

- **Schema laden**: `SchemaDefinition` (`hexagon/core/.../model/SchemaDefinition.kt`)
  + `JsonSchemaCodec` / `YamlSchemaCodec` (`adapters/driven/formats`).
- **Daten lesen**: `JsonChunkReader`, `YamlEventCursor`, CSV-Reader und
  `FormatReadOptions` aus `adapters/driven/formats/.../format/data`.
- **Spalten-/Constraint-Logik**: `TargetColumn` + `schemaTargetValidator`
  aus dem Import-Preflight; CHECK-Constraint-Auswertung aus dem
  Migrate-Preflight (`CheckPreflightProbeRunner`-Umfeld) als Vorlage für die
  Constraint-Engine.

Es fehlt: das `validate`-Command-Group-Wiring (heute registriert `Main.kt`
nur `schema`/`data`/`export`/`mcp`) und eine **DB-freie** Row-Validierungs-
Engine, die Datensätze gegen Spalten-Constraints prüft.

## 4. Scope-Skizze (Sub-Slices)

1. **`validate`-Command-Group + Wiring** — `ValidateCommand` als Top-Level in
   `Main.kt` registrieren, mit `ValidateDataCommand` (und später dem schon
   geplanten `validate procedure`, `LN-034`). Drei-Schicht-Muster (Command →
   Runner → Wiring, Runner/Wiring `internal`), wie
   [[cli-command-refactor-pattern]].
2. **Schema + Daten einlesen** — Schema-Codec nach Endung/Format wählen,
   Datendatei über die Format-Reader streamen (JSON/YAML/CSV), Encoding über
   `EncodingDetector`.
3. **Konformitäts-Engine (DB-frei)** — Präsenz, Typ, Nullability,
   Länge/Präzision, CHECK, Custom-Type/Enum. Streaming, damit große Dateien
   ohne Vollmaterialisierung prüfbar sind.
4. **Report + Exit-Codes** — Report (Text; optional `--json` für Skripting)
   mit Fundstellen (Zeile/Datensatz, Spalte, Regel). Exit `0` Erfolg, `3`
   Validierungsfehler, `2` ungültige Flags, `7` Parse-/IO-Fehler (deckungs-
   gleich mit dem cli-spec-Vertrag).
5. **Doku-/Spec-Hygiene** — „Geplant."-Marker in cli-spec entfernen bzw. auf
   die `LF`-Kennung umstellen (Markdown-Link/ID, keine §), Spec um die
   geklärte Tabellen-Zuordnung (§-frei) ergänzen.

## 5. Offene Designfragen
1. **Tabellen-Zuordnung (blockierend)**: Der Spec-Aufruf
   `validate data --source data.json --schema schema.yaml` nennt **keine
   Zieltabelle**, ein `SchemaDefinition` hat aber viele Tabellen. Optionen:
   neues `--table <name>`-Flag; Ableitung aus dem Dateinamen; oder die
   Datendatei trägt eine Tabellen-Struktur (Top-Level-Keys = Tabellennamen).
   Muss vor Slice 3 entschieden und in der Spec fixiert werden.
2. **Constraint-Tiefe in v1**: Spalten/Typ/Nullability/Länge sicher; CHECK
   und FK fraglich. FK-Referenzintegrität braucht den vollen Datensatz (und
   ggf. mehrere Tabellen) — Kandidat für „out of scope v1".
3. **CSV-Typisierung**: CSV liefert nur Strings — Coercion-Regeln (wann gilt
   `"42"` als gültiges `INTEGER`?) müssen definiert werden, konsistent zum
   Import-Pfad.
4. **Output-Format**: nur Text vs. zusätzlich `--json`.

## 6. Vorbedingungen
- **Lastenheft-Backfill**: eigene `LF`-Kennung „Datenvalidierung gegen
  Schema" — `validate data` hat heute keine Provenienz (genau der Befund,
  der diesen Plan ausgelöst hat). Ohne Kennung bleibt der cli-spec-Marker
  ankerlos.
- **Designentscheidung Tabellen-Zuordnung** ([Offene Designfragen](#5-offene-designfragen), Frage 1) fixiert.
- Referenz-Stil beachten: keine §, nur Markdown-Links/`LF`-`LN`-Kennungen
  ([[feedback-reference-style]]).

## 7. Empfohlener Schnitt
Klein halten: v1 auf Spalten/Typ/Nullability/Länge gegen **eine** Tabelle
(nach Klärung der [Tabellen-Zuordnung](#5-offene-designfragen)) begrenzen,
CHECK/FK als spätere Erweiterung. Liefert
schnell sichtbaren Nutzen und nutzt durchgängig vorhandene Bausteine.
