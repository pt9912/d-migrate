# Abgeschlossene Arbeit

Archiv der gelieferten Slice-Closure-Pläne und vollständig
abgeschlossenen Umbrella-Pläne. Zwei Typen leben hier:

1. **Per-Slice-Closure-Pläne** `ImpPlan-<version>-<slice>.md`
   (z. B. `ImpPlan-0.9.7-cross-dialect-sequencing.md`) — pro
   abgeschlossenem Slice ein eigenes Doc mit DoD-Belegen,
   Akzeptanztests und der Closure-Begründung. Etabliertes
   d-migrate-Muster, ~150 Dateien.
2. **Vollständig abgeschlossene Umbrella-Pläne** —
   landen hier, sobald alle Phasen geliefert sind (z. B.
   `diffresult-migration-plan.md` für den 0.9.7-Erstslice).
   Tragen am Ende eine `## Closure`-Sektion, die das Endergebnis
   zusammenfasst.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- **Closure-Notiz**: Per-Slice-Pläne tragen die Begründung am Ende
  (was wurde geliefert, was bleibt als Folge-Slice offen).
  Umbrella-Pläne erhalten beim Move nach hier eine eigene
  `## Closure`-Sektion.
- **Dateinamen**:
  - Per-Slice-Closure: `ImpPlan-<version>-<slice>.md`.
  - Umbrella: sprechender Name aus `../in-progress/` wird beibehalten.
- **Pfad-Stabilität**: Einträge sind nach dem Move immutabel im
  Sinne der ADR-Konvention, **bis auf**: gebrochene
  Querverweise dürfen aktualisiert werden, wenn referenzierte
  Pläne nach diesem Ordner oder nach `docs/archive/` wandern. Der
  Entscheidungsinhalt bleibt unberührt.

## Wann **nicht** hierher

- Plan ist verworfen oder vollständig überholt → `docs/archive/`
  (existiert bei Bedarf, siehe ADR-0004).
- Plan ist noch aktiv, mindestens eine Phase steht aus →
  `../in-progress/`.
- Scope steht, aber Slice ist nicht aktiv → `../next/`.
