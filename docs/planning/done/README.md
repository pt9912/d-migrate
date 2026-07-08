# Abgeschlossene Arbeit (frisch)

Frisch gelieferte Per-Slice-Closures (`ImpPlan-<version>-<slice>.md`)
und abgeschlossene Umbrella-Pläne landen hier. Der Ordner **bleibt im
Doku-Gate-Scan** (`make docs-check`): solange die Querverweise eines
Closure-Plans noch auf lebende Artefakte zeigen können, werden sie
geprüft.

Kalt gestellte Pläne — unveränderliche Historie, deren Referenzen nicht
mehr lebendig sind — wandern weiter nach
[`../done-archive/`](../done-archive/README.md), das vom Scan
ausgenommen ist. Begründung:
[`ADR 0010`](../../adr/0010-done-archive-und-gate-scan-ausschluss.md);
Basis-Lebenszyklus:
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md).

## Konvention für Einträge

- **Closure-Notiz**: Per-Slice-Pläne tragen die Begründung am Ende (was
  geliefert, was als Folge-Slice offen bleibt). Umbrella-Pläne erhalten
  beim Move hierher eine `## Closure`-Sektion.
- **Dateinamen**: Per-Slice-Closure `ImpPlan-<version>-<slice>.md`;
  Umbrella behält den sprechenden Namen aus `../in-progress/`.

## Wann **nicht** hierher

- Plan ist verworfen oder vollständig überholt → `docs/archive/`
  (siehe ADR 0004).
- Plan ist noch aktiv, mindestens eine Phase offen → `../in-progress/`.
- Kalt/eingefroren, Referenzen rein historisch → `../done-archive/`.
