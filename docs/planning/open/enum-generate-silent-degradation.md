# `enum`-Generate degradiert still zu bloßem `TEXT` (keine Werte-Durchsetzung, keine Note)

> Status: **Draft (Trigger Watch)**
> Trigger: AP0-Probe-Matrix des Typ-Kanonisierungs-Slices
> ([`../in-progress/postcompare-type-canonicalization-slice.md`](../in-progress/postcompare-type-canonicalization-slice.md),
> Status-Update 2026-07-03) plus gezielte Lautstärke-Nachprüfung der Reports.
> Aktivierungsbedingung: Scope-Schnitt bei belegtem Fidelity-Bedarf (analog dem
> Vorgehen in [`pg-only-types-first-class-candidates.md`](pg-only-types-first-class-candidates.md))
> oder Entscheidung für die Minimal-Variante „laute Note".

## Befund (live belegt 2026-07-03, Runtime-Image)

Ein Soll-Schema mit `val: { type: enum, values: ["red", "green"] }` rendert im
`migrate --execute`-Pfad auf **allen drei Dialekten** bloßes `TEXT`:

| Dialekt | Gerendertes DDL | Nativer Kandidat |
| ------- | --------------- | ---------------- |
| PostgreSQL | `"val" TEXT` | `CREATE TYPE … AS ENUM` |
| MySQL | `` `val` TEXT `` | natives `ENUM('red','green')` |
| SQLite | `"val" TEXT` | `TEXT` + `CHECK (val IN (…))` |

Die `values`-Liste wird dabei **vollständig verworfen** — im Ziel gibt es weder einen
nativen Enum-Typ noch einen CHECK, d. h. keine Werte-Durchsetzung. Und: der
Migrate-Report ist dazu **komplett still** (`diagnostics: []`, `blockers: []`, keine
Note auf stderr) — ein stiller Fidelity-Verlust, der dem Loud-Prinzip widerspricht
(Präzedenz: Fulltext-Degradationen sind mit Notes/W-Codes laut, ADR 0015-Muster).

Abgrenzung: Der Reverse liest das Ziel konsistent als `text` zurück; der daraus
folgende Post-Compare-Drift ist eine gewöhnliche Typ-Kante und wird vom
Kanonisierungs-Slice behandelt. **Dieses Ticket betrifft nur die Generate-Seite**
(Fidelity + Lautstärke), nicht den Post-Compare.

## Offene Vorabklärung

- Emittiert der reine `schema generate`-Pfad (ohne migrate) eine Note für die
  Degradation? Im `migrate`-Report kommt jedenfalls nichts an.
- **Pfad-Inkonsistenz BESTÄTIGT (AP1-Review-Verifikation 2026-07-03):** der
  `schema generate`-Pfad materialisiert Inline-Enums nativ (`columnEnumInline`
  in
  [`MysqlColumnConstraintHelper`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt)
  rendert `ENUM('…')` bei gesetzten `values`), der `migrate`-Diff-Pfad rendert
  dagegen uniform über `typeMapper.toSql` (`columnLine` in
  [`MysqlDiffSqlBuilders`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt),
  aufgerufen aus `renderCreateTable` in
  [`MysqlDiffTableOps`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffTableOps.kt))
  → bloßes `TEXT` trotz gesetzter `values` (deckt sich mit der
  AP0-Live-Evidenz). `schema generate` und `migrate` rendern damit
  verschiedene Spaltentypen für dasselbe Soll — beim Scope-Schnitt
  vereinheitlichen.
- Verhältnis zu Custom-Types: das Neutralmodell kennt Custom-Types (der Fingerprint
  hasht sie); zu klären, ob Inline-`enum` bewusst der degradierte Pfad ist und die
  native Abbildung über Custom-Types laufen soll.

## Scope-Optionen (bei Aktivierung zu entscheiden)

1. **Minimal:** laute Note/W-Code für die Degradation (kein Verhaltens-, kein
   DDL-Change; Ledger-Eintrag nach dem Muster aus
   [`warn-code-ledger-completeness.md`](warn-code-ledger-completeness.md)).
2. **Fidelity:** dialektbewusste Materialisierung — MySQL natives `ENUM`, SQLite
   `CHECK`, PG natives Enum via Custom-Type oder `CHECK`; Reverse-Pendants nötig
   (Round-Trip-Parität), sonst neue Post-Compare-Kanten.

## Nicht-Scope

- Keine Änderung am Kanonisierungs-Slice (dort bleibt `enum` eine Typ-Kante; sollte
  Option 2 später native Typen einführen, ändern sich die Kanten-Tabellen dort
  mit — der Kompositions-Kanonisierer folgt automatisch).
