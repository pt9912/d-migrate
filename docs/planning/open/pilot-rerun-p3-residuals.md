# Pilot-Re-Run-P3-Restbefunde (N7, N8)

> **Status:** Sammlung/Tracker (2026-06-17)
> **Trigger:** Der 0.9.9-Re-Validierungslauf
> ([`../in-progress/pilot-validation-0.9.9-rerun.md`](../in-progress/pilot-validation-0.9.9-rerun.md))
> deckte nach Behebung der P1/P2-Blocker (N1–N6, alle gefixt) zwei **P3**-Befunde
> auf. Beide sind nicht RC-blockierend; einer ist eine Feature-Lücke, der andere
> braucht generatorweiten State — daher hier getrackt statt in der engen Fix-Runde
> mitgezogen.
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

## Abgrenzung (bereits gefixt, Re-Run)

Die P1/P2-Befunde des Re-Runs sind erledigt: N1 (CURRENT_DATE-Default), N3
(Preflight Enum/Temporal), N2 (PG-Partition leer), N4 (View `::`/`||`→MySQL),
N5 (Nicht-PK-nextval), N6 (Trigger-Action-Body) — Commits auf `develop`.
