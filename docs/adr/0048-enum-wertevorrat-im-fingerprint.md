---
status: accepted
date: 2026-08-23
decision-makers: pt9912
consulted: docs/planning/done/fingerprint-v8-enum-check-projection.md, docs/planning/open/enum-inline-check-fidelity.md
informed: hexagon/core (MigrationFingerprint), docs/planning/done/mssql-dialect-scoping.md, CHANGELOG.md
---

# Der Wertevorrat eines Enums zählt im Fingerprint unabhängig von seiner Darstellung, `schema-fingerprint-v7` → `v8`

> **Status: accepted (2026-08-23).** Der Post-Execute-Compare behandelt den
> Wertevorrat eines Enums als **eine** Aussage, gleich ob er am Spaltentyp
> steht (authored `enum(werte)`) oder als eigener CHECK daneben
> (zurückgelesen aus einem Dialekt ohne Enum-Typ). Die Erkennung ist
> **formbasiert**, nicht namensbasiert, und bleibt wie in
> [ADR 0026](0026-fingerprint-kanonisierung-post-compare.md) auf den
> Fingerprint begrenzt — `schema compare` und die Diff-Engine bleiben streng.

## Kontext und Problemstellung

`schema migrate --execute` vergleicht nach dem Apply das frisch
zurückgelesene Ziel gegen das gewünschte Schema. Für ein Enum stehen sich
dabei zwei Darstellungen derselben Aussage gegenüber:

- **authored**: `mood: enum(red, green)` — der Wertevorrat steckt im Spaltentyp,
  die Tabelle hat keinen Constraint.
- **zurückgelesen**: eine Textspalte **plus** ein CHECK, der die erlaubten Werte
  aufzählt — denn SQL Server hat keinen Enum-Typ.

Die Typseite fiel schon mit ADR 0026: die dialektbewusste Projektion faltet
`enum(red, green)` auf `text(5)`. Die Constraint-Seite blieb — und weil der
Fingerprint Constraints namentlich führt, konnten die beiden Seiten nie gleich
hashen. Eine fehlerfrei gelaufene Migration meldete damit Drift, **bei jeder
Enum-Spalte**.

Für PostgreSQL und SQLite blieb das folgenlos, weil deren Migrate-Pfad das
Inline-Enum bewusst als bare `TEXT` rendert und mit `W134` darauf hinweist; für
MySQL gibt es das Problem nicht, weil es einen nativen `ENUM` hat. Erst SQL
Server rendert den CHECK auch im Migrate-Pfad — der Diff-Pfad nutzt dort
denselben Spalten-Helfer wie `schema generate` — und macht die Kante damit
wirksam.

## Entscheidung

Der Fingerprint bringt beide Darstellungen auf dieselbe Form. Ein CHECK, der
den Wertevorrat **einer** Spalte dieser Tabelle aufzählt, wandert aus dem
Constraint-Block in dieselbe Projektion, in der auch der Spaltentyp seinen
Wertevorrat abliefert.

Drei Eigenschaften bestimmen die Regel:

- **Formbasiert, nicht namensbasiert.** PostgreSQL vergibt den Constraint-Namen
  automatisch, SQL Server nach Konvention. Ein namensbasierter Fold wäre auf
  einen Dialekt zugeschnitten und gegenüber fremden Datenbanken wertlos.
- **Zwei Schreibweisen.** Geschrieben wird `spalte IN ('a','b')` — zurück kommt
  bei SQL Server `spalte='b' OR spalte='a'`, normalisiert und umsortiert. Beide
  Formen werden erkannt, und die Werte gelten für den Abgleich als **Menge**.
  Der Spaltentyp behält dagegen seine Reihenfolge: MySQLs nativer `ENUM` hat
  Ordinal-Semantik.
- **Eindeutigkeit vor Toleranz.** Passen **zwei** CHECKs auf dieselbe Spalte, ist
  nicht entscheidbar, welcher den Wertevorrat beschreibt — dann faltet keiner.
  Einen zu falten liesse ihn spurlos verschwinden, samt dem Unterschied, den er
  ausmacht. Die Auswahl steht deshalb fest, bevor gefaltet wird, und hängt nicht
  von der Reihenfolge der Constraints ab.
- **Informationserhaltend.** Es wird nichts ignoriert, sondern zusammengeführt.
  Fehlt der CHECK im Ziel, meldet der Vergleich weiterhin Drift; ein CHECK, der
  dem Wertevorrat der Spalte **widerspricht**, bleibt ein eigener Constraint.

## Konsequenzen

- `MigrationFingerprint.ALGORITHM` springt auf `schema-fingerprint-v8`. Ältere
  Rollback-Artefakte und Overlays sind damit nicht mehr vergleichbar;
  `SchemaRollbackRunner` lehnt sie mit `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`
  ab, statt still falsch zu vergleichen — derselbe Umgang wie beim Sprung
  `v6` → `v7`.
- Die Toleranz gilt **nur** im Fingerprint. `schema compare` meldet einen
  zusätzlichen CHECK weiterhin als Unterschied.
- Der Reverse bleibt unverändert. Ob er den Enum eines Tages selbst
  rekonstruieren soll, ist eine eigene Frage
  ([`enum-inline-check-fidelity.md`](../planning/open/enum-inline-check-fidelity.md),
  Weg A) — sie wird durch diese Entscheidung nicht vorweggenommen.

## Bekannte Grenze: nur SQL Server und SQLite liefern eine erkennbare Form

Gemessen gegen echte Container:

| Dialekt | was der Reverse für `CHECK (mood IN ('red','green'))` liefert | erkannt |
| --- | --- | --- |
| MS SQL Server | `mood='green' OR mood='red'` | ja |
| SQLite | der Text unverändert, wie geschrieben | ja |
| PostgreSQL | `((mood = ANY (ARRAY['red'::text, 'green'::text])))` | **nein** |
| MySQL | ``(`mood` in (_latin1'red',_latin1'green'))`` | **nein** |

PostgreSQL und MySQL schreiben den Ausdruck beim Speichern in eine eigene
Normalform um — mit `= ANY (ARRAY[...])` und Typ-Casts beziehungsweise mit
Charset-Introducern vor jedem Literal. Beide werden von der Projektion nicht
erkannt.

Das ist **folgenlos für den Zweck dieser Entscheidung**: der Migrate-Pfad
rendert bei PostgreSQL und SQLite ohnehin bare `TEXT` (`W134`), und MySQL hat
einen nativen `ENUM` — die Kante entsteht dort gar nicht. Es ist aber die
sichere Richtung und keine Vollständigkeit: ein von Hand auf PostgreSQL oder
MySQL angelegter `IN`-CHECK faltet authored, aber nicht zurückgelesen. Sollten
diese Formen einmal gebraucht werden, gehören sie in dieselbe Projektion — und
sie müssen dann gegen echte Container gemessen werden, nicht angenommen.

## Verworfene Alternativen

- **Den CHECK auf beiden Seiten ignorieren.** Einfacher, aber verlustbehaftet:
  ein Ziel, dem der CHECK fehlt, sähe dann aus wie ein Ziel, das ihn hat.
- **Den Reverse den Enum rekonstruieren lassen** (Weg A). Der eigentliche
  Root-Fix, aber mit breitem Radius (`schema reverse`, `data transfer`,
  generate-aus-reverse, Goldens) und dem Default-Zielkonflikt aus
  [ADR 0027](0027-reverse-preferences-inhaerente-mehrdeutigkeit.md): der
  konservative Default „aus" ließe den Drift bestehen.
- **SQL Server auf bare `NVARCHAR` + `W134` zurückziehen.** Stellt Driftfreiheit
  her wie bei PostgreSQL, gibt aber die Werte-Durchsetzung auf — und anders als
  dort gibt es bei SQL Server keinen Ausweg über einen Custom Type, weil auch
  ein `refType`-Enum dort zu `NVARCHAR` + CHECK wird.
