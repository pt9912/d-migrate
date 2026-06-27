# Tracker: PG-only-Typen ohne first-class neutralen Typ (Degradierung zu `text` + R301)

> **Status:** Sammlung/Trigger-Watch (2026-06-27)
> **Trigger:** [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) (Abschnitt
> „Abgrenzung") modelliert **nur** Volltext-Vektoren (`tsvector` → `fulltext`) first-class
> und hält fest, dass **jeder weitere** native PG-Typ eine eigene first-class-Entscheidung
> ist. Bis dahin degradieren diese Typen verlustbehaftet zu `text`. Getrackt in
> [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 8, Zeile 2.
> **Aktivierungsbedingung:** Pro Typ einzeln — sobald ein **konkreter Fidelity-Bedarf**
> auftritt (z. B. ein Sample-DB-Harness-Finding wie seinerzeit bei `tsvector`), entsteht für
> diesen Typ ein eigener `next/`-Plan + ggf. eine eigene ADR (nach dem `geometry`-/
> `fulltext`-Muster); der Eintrag hier verweist dann darauf. Dieses Dokument bleibt eine
> **Referenz-/Trigger-Sammlung** ohne eigenen Slice-Charakter und wandert als Ganzes nicht
> nach `next/`.

## Mechanik heute

Jeder PG-Typ, den der Reverse nicht kennt, fällt im Typ-Mapper auf
`NeutralType.Text()` + Warn-Note **R301** durch — der `else`-Zweig in
[`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt)
(„Unknown PostgreSQL type … mapped to text"). Die Degradierung ist **ehrlich gemeldet** und
für Cross-Dialect oft korrekt (es gibt dort kein Pendant), aber für den
**Same-Dialect-Round-Trip (PG→PG)** ein unnötiger Fidelity-Verlust — genau die Lücke, die
ADR 0015 für `tsvector` geschlossen hat. `uuid`/`json`/`jsonb`/`xml` sind bereits gemappt
und **nicht** betroffen.

## Kandidaten (nicht abschließend)

| Typ(en) | Kategorie | Heute | Notiz |
| ------- | --------- | ----- | ----- |
| `inet`, `cidr` | Netzwerk-Adresse | `text` + R301 | In ADR 0015 namentlich genannt. PG-Kern-Typen. |
| `macaddr`, `macaddr8` | MAC-Adresse | `text` + R301 | Nachbarn von `inet`/`cidr`. |
| `tsquery` | Volltext-Suchanfrage | `text` + R301 | In ADR 0015 genannt; Gegenstück zum bereits gemappten `tsvector` (→ `fulltext`). |
| `int4range`, `int8range`, `numrange`, `tsrange`, `tstzrange`, `daterange` | Range-Typen | `text` + R301 | In ADR 0015 als „Ranges" genannt. PG-Kern. |
| `int4multirange`, … (PG 14+) | Multirange-Typen | `text` + R301 | Erweiterung der Range-Familie. |
| `ltree`, `lquery`, `ltxtquery` | Hierarchie-Label (Extension `ltree`) | `text` + R301 | In ADR 0015 genannt. **Extension**, nicht Kern. |
| `hstore` | Key-Value (Extension) | `text` + R301 | Nähe zu `json`/`jsonb` (bereits gemappt) — Mapping-Entscheid abwägen. |
| `money` | Währung | `text` + R301 | Locale-abhängige Formatierung; Abbildung auf `numeric`/`decimal` abzuwägen. |
| `bit`, `bit varying` (`varbit`) | Bit-String | `text` + R301 | Nur prüfen, falls real auftretend. |

## Entscheidungs-Leitplanken (pro Typ)

- **Kein Native-Passthrough.** Rohe Dialekt-Typ-Strings werden nicht durchs neutrale Modell
  gereicht (ADR 0015 Präzedenz) — ein neuer Typ wird *abstrahiert* modelliert
  (`geometry`-/`fulltext`-Muster) oder bleibt bei der `text`-Degradierung.
- **Auslöser = belegter Bedarf, nicht Vollständigkeit.** Ein Typ wird first-class, wenn ein
  konkretes Finding den Fidelity-Verlust als schmerzhaft ausweist — nicht „auf Vorrat".
- **Cross-Dialect-Profil mitdenken.** Die meisten dieser Typen haben in MySQL/SQLite kein
  Pendant → auch first-class bleibt die Cross-Dialect-Seite eine `text`-Degradierung mit
  Note (wie `fulltext`). Der Gewinn liegt primär im PG→PG-Round-Trip.

## Referenzen

- [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) — Abschnitt „Abgrenzung"
  (Quelle dieses Trigger-Watch).
- Neutrales-Modell-Vertrag: [`../../../spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md).
- Carve-Out-Tracker: [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 8.
- Strukturell verwandter (eigener) Slice:
  [`fulltext-structural-cross-dialect.md`](fulltext-structural-cross-dialect.md).
