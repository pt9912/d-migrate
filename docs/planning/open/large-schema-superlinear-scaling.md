# Große gemischte Schemas: Diff-/Render-Pipeline skaliert super-linear

> **Status:** Vorabklärung (Trigger, 2026-06-23)
> **Trigger:** Beim TPC-4d-Bau (DDL-1000-Gate) zeigte der `LargeSchemaScaleSpec`-
> 4×n-Scale eine **stark super-lineare** Laufzeit: N=100 (401 Objekte) ~385 ms vs.
> N=1000 (4001 Objekte) ~52 s — **≈ 136× für die 10-fache Objektzahl** (cold, 1
> Iteration, ungecappt). Reine Tabellen skalieren dagegen ~linear (1000 Tabellen
> ~1,7 s). Die Blow-up-Quelle ist also der **gemischte** Anteil: Views + Trigger +
> deren **Dependency-Topologie** (Views→Tabellen, Trigger→Funktion/Tabelle).
> **Bezug:** keine harte LF-Anforderung (LN-004 „1000 Tabellen" ist mit ~1,7 s erfüllt);
> relevant für reale Schemas mit vielen Views/Triggern + die Mess-Stabilität.

## Beleg (live, 2026-06-23, `make docker-perf MODULES=":test:perf-large-schema"`)

| Scale | Objekte | Median | ms/Objekt |
|-------|---------|--------|-----------|
| N=100 (4×n) | 401 | 385 ms | ~0,96 |
| N=1000 (4×n) | 4001 | 52 460 ms | ~13,1 |
| 1000 reine Tabellen | 1000 | 1 711 ms | ~1,7 |

Die ms/Objekt steigt von ~1 (N=100) auf ~13 (N=1000) → super-linear; reine Tabellen
bleiben bei ~1,7. Der Faktor liegt im gemischten/abhängigen Anteil.

## Hypothese (zu bestätigen)

O(n²)-Verhalten in der Dependency-/Topologie-Auflösung oder im Diff-Vergleich, wenn
n Views/Trigger auf Tabellen/Funktionen referenzieren (`SchemaComparator` / `DiffPlanner`
/ Renderer-Ordering). Zu profilen: welcher Pipeline-Schritt dominiert bei N=1000.

## Auswirkung / Einordnung

- **Nicht LF-blockierend:** LN-004 (1000 Tabellen) ist erfüllt; 1000 Views+Trigger ist
  ein ungewöhnlich großes Programmability-Volumen.
- Aber: reale große Schemas mit vielen Views/Triggern träfen die Super-Linearität;
  und der 4×n-N=1000-Stress-Guard musste deshalb großzügig (90 s) gesetzt werden.

## Entscheidung (offen)

Profilen + ggf. den quadratischen Schritt linearisieren (z. B. Map-Lookup statt Liste
für Dependency-Auflösung). Aktivieren, wenn ein reales großes Schema (oder ein
Performance-Ziel für Programmability-lastige Migrationen) es erfordert.
