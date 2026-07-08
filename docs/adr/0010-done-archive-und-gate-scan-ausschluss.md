---
status: accepted
date: 2026-06-16
decision-makers: pt9912
consulted: ADR-0004 (Planungs-Lebenszyklus)
informed: Plan-Doc-Autoren; Reviewer; d-check-Gate-Pflege
---

# Eingefrorenes Done-Archiv (`done-archive/`) und d-check-Scan-Ausschluss

## Kontext und Problemstellung

Mit der Adoption weiterer d-check-Module (`matrix`, `codepaths`, `ids`;
Treiber-Plan `docs/planning/done/d-check-module-adoption.md`) wird der
`make docs-check`-Lauf über `docs/planning/` deutlich strenger:
`codepaths` prüft Inline-Code-Pfade auf Existenz, `ids` die
Kennungs-Verlinkung. `docs/planning/done/` trug zu diesem Zeitpunkt
rund 195 abgeschlossene Pläne — Per-Slice-Closures und Umbrella-Pläne —,
die naturgemäß Stände referenzieren, die **zur Closure-Zeit** galten:
inzwischen verschobene Quelldateien, frühere Plan-Pfade, abgekürzte
Code-Pfade. Diese Pläne sind unveränderliche Historie; sie laufend auf
Pfad-/Referenz-Frische zu prüfen, erzeugt dauerhaftes Rauschen ohne
Nutzen und drängt zu einer Carveout-Kaskade (Regelwerk Modul 7: viele
gleichartige `d-check:ignore`-Marker auf dieselbe Sub-Area sind ein
Anti-Muster).

[`ADR 0004`](0004-documentation-and-planning-structure.md) etabliert den
Lebenszyklus `open/ → next/ → in-progress/ → done/` (plus
`docs/archive/` für verworfene Pläne). `done/` ist dort der Endzustand
für gelieferte Arbeit — ohne Unterscheidung zwischen *frisch*
abgeschlossen (Querverweise evtl. noch lebendig, Prüfung sinnvoll) und
*kalt* eingefroren (reine Historie).

## Entscheidung

Ein fünfter Ruhe-Ordner **`docs/planning/done-archive/`** wird parallel
zu `done/` eingeführt:

- **`done/`** bleibt im Scan: frisch abgeschlossene Pläne, deren
  Querverweise noch auf lebende Artefakte zeigen können, prüft
  `make docs-check` weiter.
- **`done-archive/`** wird in [`.d-check.yml`](../../.d-check.yml) via
  `scan.ignore: ["docs/planning/done-archive/**"]` vom Scan ausgenommen.
  Eingefrorene Pläne sind unveränderliche Historie und werden nicht
  mehr auf Referenz-Frische geprüft.

Die zum Entscheidungszeitpunkt vorhandenen 195 `done/*.md` wandern per
`git mv` nach `done-archive/`; ihre Querverweise aus *gescannten*
Dateien werden auf den neuen Pfad nachgezogen (Bulk-Sweep
`done/<datei>.md` → `done-archive/<datei>.md`, 71 Dateien).

### Abgrenzung zu `docs/archive/`

`done-archive/` ist **nicht** `docs/archive/`. `docs/archive/`
(ADR 0004) ist für **verworfene oder vollständig überholte** Pläne.
`done-archive/` ist für **erfolgreich abgeschlossene**, kalt gestellte
Pläne. Verschiedene Bedeutung, verschiedener Ordner.

### Verhältnis zu ADR 0004

Diese ADR **erweitert** den Lebenszyklus aus ADR 0004 um eine Ruhestufe
hinter `done/`; ADR 0004 bleibt unberührt (Accepted = immutabel). Der
Übergang `done/ → done-archive/` läuft per `git mv` mit
Querverweis-Sweep wie die übrigen Lebenszyklus-Übergänge in ADR 0004.

## Konsequenzen

- `make docs-check` bleibt auf lebende Dokumentation fokussiert; das
  historische Rauschen aus eingefrorenen Plänen entfällt, ohne dass pro
  Plan ein `d-check:ignore`-Marker (Carveout-Kaskade) nötig wird.
- Eingefrorene Pläne behalten ihren stabilen Pfad als zitierbaren
  Token; gebrochene Verweise *innerhalb* `done-archive/` werden nicht
  mehr vom Gate erzwungen — bewusst, denn Archiv-Inhalt ist immutabel.
- Wann ein Plan von `done/` nach `done-archive/` wandert, ist eine
  Pflege-Entscheidung (Faustregel: Referenzen sind kalt, keine lebenden
  Artefakte mehr betroffen) — kein automatischer Trigger.

## Weitere Informationen

- [`ADR 0004`](0004-documentation-and-planning-structure.md) — Basis-Lebenszyklus.
- [`docs/planning/done/README.md`](../planning/done/README.md) und
  [`docs/planning/done-archive/README.md`](../planning/done-archive/README.md) — operative Konvention pro Ordner.
- Treiber: [`docs/planning/done/d-check-module-adoption.md`](../planning/done/d-check-module-adoption.md) (Entscheidung D3).
