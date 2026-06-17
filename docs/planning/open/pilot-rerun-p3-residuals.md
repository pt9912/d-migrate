# Pilot-Re-Run-P3-Restbefunde (N7, N8, K2)

> **Status:** Sammlung/Tracker (2026-06-17)
> **Trigger:** Die 0.9.9-Re-Validierungsläufe
> ([`../in-progress/pilot-validation-0.9.9-rerun.md`](../in-progress/pilot-validation-0.9.9-rerun.md),
> [`../in-progress/pilot-validation-0.9.9-rerun3.md`](../in-progress/pilot-validation-0.9.9-rerun3.md))
> deckten nach Behebung der P1/P2-Blocker drei **P3**-Befunde auf (N7, N8 aus
> Re-Run 1; K2 aus Re-Run 3). Keiner ist RC-blockierend; es sind Feature-Lücken
> bzw. generatorweite/Ordnungs-Themen — daher hier getrackt statt in den engen
> Fix-Runden mitgezogen.
> **Aktivierungsbedingung:** vor 1.0.0 nacharbeiten oder bewusst nach 1.0.x
> verschieben; bei Aufnahme wandert der Eintrag nach `../next/`.

## N7 — Benutzerdefiniertes Aggregat wird von reverse nicht erfasst (P3, Feature)

**Repro:** PG `CREATE AGGREGATE group_concat(...)` → nicht im reverse-DDL;
abhängige Views (`SELECT group_concat(x) …`) scheitern beim Apply mit
`function group_concat does not exist`.

**Ursache/Richtung:** Der PG-Reverse liest Tabellen/Views/Funktionen/Trigger/
Sequenzen, aber **keine** `CREATE AGGREGATE`-Objekte (kein `pg_aggregate`-Pfad,
kein Modell-Typ `AggregateDefinition`). Das ist eine **echte Feature-Erweiterung**
(Reverse-Query + neutrales Modell + Generate je Dialekt), kein Einzeiler — daher
eigener Slice. Bis dahin: dokumentierte Lücke (Views, die Custom-Aggregate nutzen,
brauchen manuelle Nacharbeit).

## N8 — Index-Namens-Kollision MySQL→PG (P3)

**Repro:** MySQL erlaubt denselben Index-Namen (`idx_fk_address_id`) auf mehreren
Tabellen (per-Tabelle-Namensraum); PG-Index-Namen sind **schema-global**. Generate
emittiert die Roh-Namen → `ERROR: relation "idx_fk_address_id" already exists`;
einzelne Indizes fehlen im Ziel.

**Ursache/Richtung:** `PostgresDdlGenerator.generatedIndexNames` disambiguiert nur
**innerhalb einer Tabelle**. Für schema-globale Eindeutigkeit braucht der Generator
**generatorweiten State** über einen `generate()`-Lauf (Set benutzter Index-Namen,
deterministisch je Reset) oder einen Schema-Vorpass, der kollidierende explizite
Namen tabellen-präfigiert. Beides berührt die Generator-Orchestrierung und
Golden-Master — eigener, fokussierter Slice (Achtung: Golden-Churn).

## K2 — `--include-all`-Routinen nicht topologisch geordnet (P3)

**Repro:** `--include-all` emittiert die SQL-Funktion `film_in_stock`
(`LANGUAGE sql`) **vor** der von ihr referenzierten `inventory_in_stock` →
`CREATE`-Fehler. Zusätzlich: `RETURN NEXT` ohne `RETURNS SETOF`.

**Ursache/Richtung:** PG validiert `LANGUAGE sql`-Funktions-Bodies **bei `CREATE`**
(anders als plpgsql, dessen Body ein nicht geprüfter String ist) — die
emittierte Routine-Reihenfolge muss daher Funktion→Funktion-Abhängigkeiten
respektieren. Es braucht eine **topologische Routinen-Ordnung** (analog
`sortTablesByDependency` für FK-Kanten), gespeist aus Routine-Dependency-Kanten;
plus korrekte `RETURNS SETOF`-Ableitung bei `RETURN NEXT`. Vorbestehend, nur
`--include-all`, gleiche `--include-all`-Residualklasse wie N7/N8 — eigener,
fokussierter Slice (Reverse-Dependency-Extraktion + Emissions-Sortierung).

## Abgrenzung (bereits gefixt)

Alle P1/P2-Befunde der vier Pilot-Läufe sind erledigt (Commits auf `develop`):
Erstlauf I-01…I-10; Re-Run 1 N1 (CURRENT_DATE), N3 (Preflight Enum/Temporal),
N2 (PG-Partition leer), N4 (View `::`/`||`→MySQL), N5 (Nicht-PK-nextval),
N6 (Trigger-Action-Body); Re-Run 2 M2 (Preflight strukturell), M1 (Routinen-
Namen ohne Signatur-Suffix); Re-Run 3 K1 (PG-Array→MySQL-JSON-Wertkonverter).
Offen sind nur noch die P3-Reste oben (N7, N8, K2).
