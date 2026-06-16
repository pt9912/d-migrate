# Eingefrorenes Done-Archiv

Kalter, **vom Doku-Gate ausgeschlossener** Ablageort für vollständig
abgeschlossene und seit Längerem unveränderte Pläne — die historische
Masse der gelieferten Per-Slice-Closures
(`ImpPlan-<version>-<slice>.md`) und abgeschlossenen Umbrella-Pläne.

Dieser Ordner ist in [`.d-check.yml`](../../../.d-check.yml)
(`scan.ignore`) vom `make docs-check`-Scan ausgenommen: eingefrorene
Pläne sind unveränderliche Historie und werden nicht mehr auf Pfad-/
Referenz-Frische geprüft (sie referenzieren naturgemäß Stände, die zur
Closure-Zeit galten). Begründung und Lebenszyklus:
[`ADR 0010`](../../adr/0010-done-archive-und-gate-scan-ausschluss.md)
(erweitert
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)).

## Abgrenzung

- **`../done/`** — *frisch* abgeschlossene Pläne; bleiben im Scan, bis
  ihre Referenzen kalt sind, dann Move hierher.
- **`docs/archive/`** — explizit *verworfene* oder überholte Pläne
  (andere Bedeutung als „abgeschlossen"; siehe ADR 0004).
- Inhalt ist immutabel; nur gebrochene Querverweise dürfen nachgezogen
  werden, wenn referenzierte Pläne wandern.
