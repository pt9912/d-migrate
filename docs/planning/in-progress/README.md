# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Drei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen (dauerhaft
   aktiv, wandern nicht):
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht.
   - [`carveout.md`](carveout.md) — Living Tracker für bewusste
     Scope-Cut-Entscheidungen (Permanent / Provisional / Promoted /
     Resolved) mit Verweis aufs Quelldokument.
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Workstream
   tragen, bei dem mindestens ein Commit den Plan referenziert.
3. **Aktive Per-Slice-ImpPlans und Skeletons**, die einen laufenden
   Slice konkretisieren oder Hand-off-Anker aus aktiven Slices
   festhalten. Sie bleiben hier bis zur Slice-Closure; Skeletons sind
   erlaubt, wenn sie explizit als `Pending <Voraussetzung>` markiert
   sind.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Status-Header pflegen: `> Status: In Progress (<refresh-datum>)`
  plus pro abgeschlossene Phase einen Commit-Ref-Hinweis.
- Per-Slice-ImpPlans wandern beim Abschluss eines Slice nach
  `../done/ImpPlan-<version>-<slice>.md`. Die Umbrella selbst bleibt
  in `in-progress/`, bis alle Phasen geliefert sind — dann wandert
  sie als Ganzes nach `../done/` mit einer `## Closure`-Sektion am
  Ende, die den finalen Stand zusammenfasst.
- Skeletons tragen im Status klar `Skeleton`/`Pending` plus die
  blockierende Voraussetzung und werden beim Start des Slice zu einem
  vollstaendigen ImpPlan erweitert.
- `roadmap.md` und `carveout.md` sind die dauerhaften Top-Level-
  Aggregatoren und wandern nicht. Versions-spezifische Workstream-
  Aggregatoren (z. B.
  der 0.9.7-Aggregator
  [`diffresult-migration-plan-2.md`](../done-archive/diffresult-migration-plan-2.md),
  geschlossen 2026-06-02) wandern beim Milestone-Abschluss nach
  `../done/` und tragen dort eine `## Closure`-Sektion.

## Wann **nicht** hierher

- Scope steht, aber kein Implementierungs-Commit existiert und es gibt
  keinen aktiven Hand-off-Anker aus einem laufenden Slice → `../next/`.
- Trigger ohne Scope → `../open/`.
- Alle Phasen geliefert (für Umbrella-Pläne) → `../done/`.
- Einzelne Sub-Slice-Closure-Notiz → direkt nach
  `../done/ImpPlan-<version>-<slice>.md`, der Umbrella bleibt
  hier.

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`roadmap.md`](roadmap.md) | Top-Level-Aggregator | Gesamt-Milestone-Sicht. |
| [`carveout.md`](carveout.md) | Top-Level-Aggregator | Living Tracker fuer bewusste Scope-Cut-Entscheidungen mit Verweis aufs Quelldokument. |
| [`cli-data-seed.md`](cli-data-seed.md) | Per-Feature-Umbrella | `d-migrate data seed`: Testdaten generieren + importieren. Vier Phasen P1-P4; P1 (deterministischer Generator-Kern) und P2 (`--rules`) geliefert (siehe [`ImpPlan-1.3.0-cli-data-seed-p1.md`](../done/ImpPlan-1.3.0-cli-data-seed-p1.md)/[`ImpPlan-1.3.0-cli-data-seed-p2.md`](../done/ImpPlan-1.3.0-cli-data-seed-p2.md) in `done/`); P3 (`--ai-backend`) bleibt geplant. |
| [`action-required-bestimmt-den-ausgang.md`](action-required-bestimmt-den-ausgang.md) | Next-Plan (Weg beta) | `action_required` bekommt eine Bedeutung im Exit-Code-Vertrag: die Ausgangsregel haengt an `SkippedObject`, nicht an der Notiz-Stufe. P0 (sechs echte Luecken geschlossen) und P1 (Ausgangsregel + `--allow-incomplete` gebaut, live belegt) abgeschlossen — `schema generate` bricht jetzt mit Exit 8 ab, wenn ein Objekt fehlt, ausser mit `--allow-incomplete` (Report/stderr vermerken die Unterdrueckung mit W160). Dabei nebenbei gefunden und behoben: `exit_code` im JSON-Output war fest auf 0 verdrahtet. P2 (MCP), P3 (weitere Kommandos), P4 (Doku) offen. |
| [`capability-tables-driver-interface.md`](capability-tables-driver-interface.md) | Per-Feature-Umbrella | Alle fuenf Capability-Tabellen tragen keine `when (dialect)`-Zweige mehr: die Werte liegen je Dialekt im Treibermodul. Zwei Ports statt einem, weil die Werttypen auf zwei Modulebenen wohnen — `DialectCapabilityProvider` in `ports-common`, `DialectReadCapabilityProvider` (erbt davon) in `ports-read`; nicht ueber `DatabaseDriverRegistry`, weil `ports` von `ports-read` abhaengt und das den Modulgraphen umgedreht haette. P0-P5 gebaut, alle 49 Module gruen, je Treibermodul eine Verdrahtungs-Spec. |
| [`reader-treue.md`](reader-treue.md) | Per-Feature-Umbrella (vier Plaene) | Stille Verluste und Fremdes im Reverse und am Generator. Traegt, was die vier Plaene teilen: gemeinsamer Nenner, Belegart, Verbleib jedes Postens, Regeln der Matrix-Abnahme (Neu-Pins einzeln, betroffene Zellen), Nulllinie der Integrationsmodule, Doku-Pflichten, Code-Tabelle (`R370`, `R371`, `R402`-`R405`, `R221`, `W162`-`W164`), entschiedene Eigner-Fragen (E1 = (b), E2, E3, Fingerabdruck-Anhebung). Reihenfolge 1 -> 2 -> 3 -> 4; die Plaene 1 bis 3 sind geliefert und graduiert ([`../done/reader-treue-1-matrix-abnahme.md`](../done/reader-treue-1-matrix-abnahme.md), [`../done/reader-treue-2-meldungen.md`](../done/reader-treue-2-meldungen.md), [`../done/reader-treue-3-spatial.md`](../done/reader-treue-3-spatial.md)), Plan 4 steht in `../next/`. |
| [`sqlite-enum-domain-check-reverse.md`](sqlite-enum-domain-check-reverse.md) | Per-Feature-Plan | Wertevorrat von `ref_type`-Enums auf SQLite gegen eine echte Datenbank erkennen und umsetzen (Issue #48): Soll-Seiten-Aufloesung aus `custom_types`, Reader-Option, Preflight, `schema-fingerprint-v18`; Phasen P0-P5. Entscheidung: ADR 0060. |
| [`sqlite-neubau-abhaengige-sichten.md`](sqlite-neubau-abhaengige-sichten.md) | Per-Feature-Plan | Tabellen-Neubau auf SQLite mit abhaengigen Sichten (Issue #50): Tabellenbezuege aus dem Sichttext ableiten (Richtung A'), Sichten-Leseweg `extractViewQuery`; Phasen P0-P3. |
| [`custom-type-fields-leer-gegen-null.md`](custom-type-fields-leer-gegen-null.md) | Per-Feature-Plan | `schema migrate` gegen PostgreSQL plant `AlterCustomType` fuer einen unveraenderten Typ (Issue #51): `compareCustomType` vergleicht Datei-Form gegen Reader-Form (`fields` null gegen leer, Composite-`ordinal`, `description`); Integrationstest gegen PostgreSQL, Handbuch-Hinweis. |
| [`postgres-domain-autorenform-gegen-katalog.md`](postgres-domain-autorenform-gegen-katalog.md) | Per-Feature-Plan | `schema migrate` gegen PostgreSQL plant `AlterCustomType` fuer eine unveraenderte Domain (Issue #52): Reader liefert `precision`/`scale` und die Katalogform des `CHECK`, die Datei die Autorenform; Reader-Normalform, Basistyp-Neutralform, `schema-fingerprint-v19`. |
| [`postgres-domain-mehrfach-check.md`](postgres-domain-mehrfach-check.md) | Per-Slice-Plan | Folge von #52: der PostgreSQL-Reader verliert bei einer Domain alle CHECKs bis auf den letzten (`LEFT JOIN` ohne Aggregation); dazu `NOT VALID` und Domain ueber Domain. |
| [`domain-default-not-null-modell.md`](domain-default-not-null-modell.md) | Per-Feature-Plan | Issue #59 (Folge von #52): `DEFAULT`, `NOT NULL` und Kollation einer Domain im Modell (Option A-PG: nur PostgreSQL wertet aus); behebt `required` der Domain-Spalte, `schema-fingerprint-v24`. |
| [`sqlite-eindeutiger-index-als-constraint.md`](sqlite-eindeutiger-index-als-constraint.md) | Per-Feature-Plan | `schema migrate` gegen SQLite plant fuer einen unveraenderten eindeutigen Index `DropConstraint` + `AddIndex` (Issue #53); `--execute` blockiert mit Exit 8. Entscheidung A: der Reader unterscheidet `CREATE UNIQUE INDEX` (Index) von `UNIQUE`-Constraint; `schema-fingerprint-v20`, ADR 0062. |
| [`sqlite-domain-spalte-basistyp-vergleich.md`](sqlite-domain-spalte-basistyp-vergleich.md) | Per-Feature-Plan | Folge von #54: eine Domain-Spalte ueber `integer`, `boolean`, `decimal`, `float`, `binary` u. a. (nicht `uuid`, `text`, `json`: Reverse-Form `Text`) plant auf SQLite bei jedem Lauf `AlterColumnType`; der Vergleich projiziert die Domain jetzt ueber ihren Basistyp. |
| [`sqlite-neubau-cast-gleiche-speicherklasse.md`](sqlite-neubau-cast-gleiche-speicherklasse.md) | Per-Feature-Plan | Der Tabellenneubau auf SQLite blockiert nicht mehr mit `SQLITE_CAST_NOT_WHITELISTED` an einer unveraenderten Spalte, die SQLite enger ablegt (Issue #54, Ursache 2): der Planer uebernimmt die Typentscheidung des Vergleichs (`AlterColumnType` im Eimer, Richtung b); Phasen P0-P3. |
| [`sqlite-default-vergleich.md`](sqlite-default-vergleich.md) | Per-Feature-Plan | `schema migrate` plant fuer gleiche `integer`-/`boolean`-/`decimal`-Defaults `AlterColumnDefault` (Issue #54, Ursache 1). Auf PostgreSQL, MySQL und SQLite gleich gemessen; Entscheidung B: der Vergleich gleicht Zahlentypen numerisch an und faltet `false`/`true` auf `0`/`1`, wo das Ziel `boolean` auf `integer` faltet; `schema-fingerprint-v21`, ADR 0063. |
| [`sqlite-neubau-allein-execute-bestandsdatei.md`](sqlite-neubau-allein-execute-bestandsdatei.md) | Per-Feature-Plan | Folge von #53 (Issue #60): `unique: true` an einer Spalte gegen einen `CREATE UNIQUE INDEX` plant `AddConstraint` + `DropIndex`; `--execute` endet mit Exit 8 (`TRANSACTION_SCOPE_UNSUPPORTED`) auch allein, und die Anweisungsfolge scheitert auch ohne Guard. Richtung: Index-Operationen in den Neubau-Eimer absorbieren; Phasen P0-P3. |
| [`postgres-rollback-inline-enum-werteliste.md`](postgres-rollback-inline-enum-werteliste.md) | Per-Feature-Plan | `schema rollback` auf PostgreSQL stellt nach geänderter Werteliste einer Inline-Enum-Spalte den `CHECK` wieder her oder blockiert den Down ausdrücklich (`ROLLBACK_NOT_POSSIBLE`, Exit 8), statt mit Exit 0 nichts zurückzunehmen (Issue [#61](https://github.com/pt9912/d-migrate/issues/61)). P0 Messung, P1 Entscheidung (a) mit (b) als Rückfall, P2 Umsetzung, P3 Doku. |
| [`sqlite-ausdrucks-index-textform.md`](sqlite-ausdrucks-index-textform.md) | Per-Feature-Plan | `schema migrate` gegen SQLite plant fuer einen unveraenderten Ausdrucks-Index `DropIndex` + `AddIndex` (Issue #55, Folge von #53). Entscheidung A: der Reader entfernt die eine Klammer, die der Generator um jeden Ausdruck setzt; `schema-fingerprint-v22`, ADR 0064. |
| [`postgres-domain-laenge-array.md`](postgres-domain-laenge-array.md) | Per-Feature-Plan | Folge von #52 (Issue #58): der PostgreSQL-Reader verliert bei einer Domain die Laenge (`varchar(n)`) und den Array-Basistyp (`_int4`); Quelle `format_type`, Neutralform mit Laenge und `[]`, `schema-fingerprint-v23`, ADR 0065. |

Graduierte/geschlossene Slices stehen unter `../done/` bzw. `../done-archive/`
(mit eigener `## Closure`-Sektion), nicht als Verweis hier.
