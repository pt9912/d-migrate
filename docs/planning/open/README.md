# Trigger Watches und offene Vorabklärungen

Dieser Ordner sammelt Trigger, offene Folgearbeiten und Vorabklärungen,
die noch **keinen konkreten Scope** tragen. Sobald ein Scope formuliert
ist (Ziel + grobe Arbeitspakete + Akzeptanzkriterien), wandert der
Eintrag nach `../next/`.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Sprechender lowercase-kebab-Dateiname (z. B.
  `test-database-candidates.md`,
  `d-browser-integration-coupling-assessment.md`).
- Jeder Eintrag beschreibt im Kopf:
  - **Status**: `Draft` / `Vorschlag` / `Sammlung` / `Vorabklärung`
  - **Trigger**: was hat den Eintrag motiviert?
  - **Aktivierungsbedingung**: was muss passieren, damit er nach
    `../next/` wandert?
- Worked Examples und reine Referenz-/Sammlungs-Dokumente
  (z. B. `test-database-candidates.md`) dürfen hier liegen, auch
  wenn sie nie nach `next/` migrieren — sie sind dauerhaft
  "trigger-/referenz-haft" ohne Slice-Charakter.

## Wann **nicht** hierher

- Plan mit ausgearbeitetem Scope und Phasen → `../next/`.
- Aktiv bearbeiteter Slice mit Implementierungs-Commits →
  `../in-progress/`.
- Geliefert und geschlossen → `../done/`.
- Verworfen / vollständig überholt → `docs/archive/` (existiert
  bei Bedarf, siehe ADR-0004).
