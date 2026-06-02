# Geplante Arbeit (Scope skizziert, noch nicht aktiv)

Einträge in diesem Ordner haben einen ausgearbeiteten Scope (Ziel,
grobe Phasen oder Arbeitspakete, Akzeptanzkriterien), aber **noch
keine aktive Slice-Arbeit** im Code. Sobald der erste Implementierungs-
Commit landet, wandert der Eintrag nach `../in-progress/`.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Sprechender lowercase-kebab-Dateiname (z. B.
  `parquet-export-import-evaluation.md`,
  `trino.md`, `telemetry-observability-port.md`).
- Jeder Eintrag enthält im Kopf:
  - **Status**: `Entwurf (<datum>)` / `Vorschlag` / `Draft mit Scope`
  - **Ziel**: was soll das Feature liefern?
  - **Scope-Skizze**: Phasen, grobe Arbeitspakete oder Sub-Slices
  - **Vorbedingungen**: was muss vorher feststehen (andere Pläne,
    ADRs, Lastenheft-Ergänzungen)
- Plan-Doc-interne Status-Updates (`> Status-Update YYYY-MM-DD`)
  sind willkommen, signalisieren aber keine Aktivierung — der Move
  nach `../in-progress/` erfolgt erst beim ersten Code-Commit.

## Wann **nicht** hierher

- Trigger ohne Scope → `../open/`.
- Slice-Arbeit aktiv (mindestens ein Implementierungs-Commit, das
  sich auf den Plan beruft) → `../in-progress/`.
- Vollständig geliefert → `../done/`.
