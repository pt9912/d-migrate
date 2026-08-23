# Fingerprint v8: Enum und `IN`-CHECK auf eine kanonische Form bringen

- **Status**: **GEBAUT** (2026-08-23), AP0–AP5 durch,
  [ADR 0048](../../adr/0048-enum-wertevorrat-im-fingerprint.md) accepted.
  Die Design-Entscheidung (Eigner, Weg B) steht in
  [`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md).
- **Trigger**: MSSQL-Sub-Slice 5e
  ([`mssql-dialect-scoping.md`](../in-progress/mssql-dialect-scoping.md)) lässt
  das `DialectCommandGate` für `schema migrate` fallen. Ab dann läuft der
  Post-Execute-Compare auch für SQL Server — und meldet bei **jeder**
  Enum-Spalte Drift.
- **Blockiert**: MSSQL-5e. Ohne diesen Schnitt wäre `schema migrate --execute`
  für jedes Schema mit Enum-Spalte praktisch unbenutzbar.
- **Präzedenz**: der v6→v7-Sprung
  ([`postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md),
  [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)) — gleicher
  Ort, gleiche Mechanik, gleicher Umgang mit alten Artefakten.

## Das Problem in einem Satz

[`SchemaMigrateExecutionStage.runPostCompare`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateExecutionStage.kt)
vergleicht das nach `--execute` zurückgelesene Ziel gegen das authored
Soll-Schema; ein Enum steht authored als `enum(werte)` ohne Constraint und
zurückgelesen als Textspalte **plus** `CHECK (spalte IN (…))`, und
[`MigrationFingerprint`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt)
führt Constraints namentlich — die Fingerprints können also nicht gleich sein.

## Die Projektion

Ein CHECK, der den Wertevorrat **einer** Spalte dieser Tabelle aufzählt,
wandert aus dem Constraint-Block in dieselbe Projektion, in der auch der
Spaltentyp seinen Wertevorrat abliefert.

**Zwei Schreibweisen, dieselbe Aussage — beim Bau live gemessen.** Geschrieben
wird `spalte IN ('a','b')`; SQL Server speichert daraus aber
`spalte='b' OR spalte='a'`, normalisiert und umsortiert. Der erste Entwurf
dieses Plans kannte nur die `IN`-Form und wäre gegen eine echte Datenbank nie
angesprungen. Beide Formen zählen, die Werte gelten für den Abgleich als
**Menge** — der Spaltentyp behält dagegen seine Reihenfolge, weil MySQLs
nativer `ENUM` Ordinal-Semantik hat.

Beide Darstellungen landen damit auf derselben kanonischen Form:

| Seite | vorher | nachher |
| --- | --- | --- |
| authored | `mood: enum(red, green)` | derselbe Wertevorrat |
| zurückgelesen | `mood: text(5)` + `CHECK (mood='green' OR mood='red')` | derselbe Wertevorrat |

Drei Eigenschaften, auf die es ankommt:

- **Formbasiert, nicht namensbasiert.** Der Constraint-Name spielt keine Rolle;
  PostgreSQL vergibt ihn automatisch, SQL Server nach Konvention. Damit ist die
  Regel dialektunabhängig, ohne pro Dialekt gepflegt zu werden.
- **Informationserhaltend.** Es wird nichts entfernt, sondern zusammengeführt.
  Ein von Hand geschriebener `IN`-CHECK verhält sich auf beiden Seiten gleich;
  fehlt er im Ziel, meldet der Vergleich weiterhin Drift. Das unterscheidet
  diesen Weg von „den CHECK beidseitig ignorieren", das eine ganze
  Constraint-Form unsichtbar gemacht hätte.
- **Nur im Fingerprint.** `schema compare` und die Diff-Engine bleiben
  strukturell streng — dieselbe Grenze, die [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)
  für die Typ-Kanonisierung gezogen hat.

### Was die Regel NICHT trifft

- Ein CHECK über **mehrere** Spalten.
- Ein CHECK mit anderer Form als eine reine `IN`-Liste über die eigene Spalte
  (Bereichsprüfungen, Funktionsaufrufe, `OR`-Ketten).
- Eine Spalte mit **mehr als einem** passenden CHECK — dann ist nicht
  entscheidbar, welcher den Wertevorrat beschreibt.
- Nicht-Text-Spalten. Ein `IN`-CHECK über eine Zahlenspalte bleibt ein CHECK.

## Arbeitspakete

| AP | Inhalt | Abnahme |
| --- | --- | --- |
| **AP0** | Erkenner für die Form (`<spalte> IN (<String-Literale>)`) als eigene, testbare Einheit in `hexagon/core`. Literal-bewusst: ein Komma **in** einem Literal darf die Liste nicht zerlegen | Unit-Tests inkl. der Nicht-Treffer aus der Liste oben |
| **AP1** | Projektion in `MigrationFingerprint`: Spalte → `Enum(values)`, Constraint aus der Liste. Reihenfolge der Werte kanonisch, damit `('a','b')` und `('b','a')` gleich hashen | Fingerprint-Gleichheit authored ↔ zurückgelesen als Unit-Test, beide Richtungen |
| **AP2** | `ALGORITHM` → `schema-fingerprint-v8`, KDoc-Historieneintrag mit Verweis hierher; die fünf Tests, die den Stempel pinnen, nachziehen | `grep -r schema-fingerprint-v7` liefert nur noch Historie |
| **AP3** | CHANGELOG-Eintrag wie beim v6→v7-Sprung: ältere Rollback-Artefakte und Overlays werden ungültig. `SchemaRollbackRunner` lehnt sie bereits explizit ab — belegen, nicht neu bauen | Test, dass ein Artefakt mit `v7`-Stempel abgelehnt wird statt still falsch zu vergleichen |
| **AP4** | Live-Beleg gegen echtes SQL Server: `generate` → `apply` → `reverse` → Fingerprint beider Seiten gleich. Der bestehende `MssqlPostCompareFingerprintIntegrationTest` bekommt einen Enum-Fall | Der Test scheitert ohne AP1 und trägt danach |
| **AP5** | ADR: die Projektion gehört in denselben Vertrag wie ADR 0026 — entweder als Ergänzung dort oder als eigener ADR mit Verweis. Entscheidung beim Bau | `make docs-check` grün, ADR verlinkt |

## Nicht im Scope

- **Weg A** (der Reverse rekonstruiert den Enum selbst). Bleibt als eigentlicher
  Root-Fix möglich und schließt diesen Schnitt nicht aus; er hat aber breiten
  Radius (`schema reverse`, `data transfer`, generate-aus-reverse, Goldens) und
  den Default-Zielkonflikt aus [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md).
- **Die Durchsetzung selbst.** Dass PostgreSQL und SQLite im Migrate-Pfad bare
  TEXT rendern und `W134` melden, während SQL Server den CHECK schreibt, bleibt
  wie es ist. Dieser Schnitt ändert nur, wie verglichen wird.
- **Der `Enum(refType)`-Schema-Kontext** aus dem MSSQL-Plan. Das ist eine andere
  Baustelle derselben Ecke und gehört zu 5e.
