# SQLite `migrate --execute` Post-Compare meldet Drift bei `identifier`-Spalten

> Status: Vorabklärung (entdeckt 2026-06-22 beim 5d-SpatiaLite-Live-Apply)
> Trigger: SpatiaLite-`migrate --execute`-Round-Trip (VA4/5d,
>   [`spatial-harness-slice.md`](../in-progress/spatial-harness-slice.md)).
> Bezug: technisches Migrate-Verhalten; betrifft `spec/cli-spec.md` (Exit-Codes
>   `migrate --execute`).
> **Nicht-spatial, pre-existing** — NICHT von der Spatial-Slice verursacht; hier nur
>   dokumentiert, weil der 5d-Smoke ihn umgehen muss.

## Befund

`schema migrate --execute` gegen ein **SQLite-Ziel** endet mit **Exit 5**
(„Post-execute compare detected drift"), obwohl die Migration sauber durchläuft
(Report `"status": "ok"`, `execution.completed = true`, `executionError = null`).

Reproduzierbar **ohne** Geometrie — minimal:

```yaml
tables:
  widgets:
    columns:
      id:    { type: identifier, auto_increment: true }
      label: { type: text }
```

`migrate --execute` gegen eine frische `.db` → Exit 5, Post-Compare-Drift.

## Ursache (Hypothese)

Der Post-Execute-Compare reverse-t das Ziel und vergleicht den **Fingerprint**
gegen das Soll. Die Reverse-Seite materialisiert die Primärschlüssel-Spalte explizit
als `primary_key: [id]`, während das Soll-Schema den PK nur **implizit** über
`type: identifier` trägt. Die Fingerprint-Normalisierung kanonisiert diese
`identifier`→`primary_key`-Asymmetrie (noch) nicht → unterschiedlicher Fingerprint →
gemeldete Drift, obwohl die Schemata **semantisch identisch** sind (belegt: `schema
generate` aus dem Reverse erzeugt byte-identisches DDL wie aus dem Soll).

## Belege

- `schema generate`(Reverse) == `schema generate`(Soll) — kein echter Schema-Diff.
- Auch nicht-spatiale Schemata driften → der Befund ist nicht spatial-spezifisch.
- Report trägt `"status": "ok"` und `"executionError": null` — die DDL-Ausführung
  selbst ist fehlerfrei; allein der nachgelagerte Fingerprint-Vergleich schlägt an.

## Akzeptanz (für eine spätere, nicht-spatiale Slice)

- `migrate --execute` gegen ein SQLite-Ziel mit `identifier`-PK-Spalte endet mit
  Exit 0 (kein Post-Compare-Drift), wenn die Migration sauber durchläuft.
- Die Fingerprint-/Normalisierungs-Schicht behandelt `type: identifier` und einen
  expliziten `primary_key`-Eintrag äquivalent (Same-Schema → Same-Fingerprint).
- Regressionstest: Reverse(`identifier`-PK) und Soll(`identifier`-PK) haben denselben
  Fingerprint.

## Umgehung im 5d-Smoke (bis dahin)

`examples/sample-db/scripts/smoke-spatial.sh` Abschnitt `[lite]` toleriert den
Prozess-Exit (`|| true`) und prüft den **Report** (`status: ok`, kein
`executionError`) sowie den **Reverse**-Round-Trip (Geometrie + SRID + Spatial-Index,
Metatabellen gefiltert) — die für 5d relevanten Aussagen.
