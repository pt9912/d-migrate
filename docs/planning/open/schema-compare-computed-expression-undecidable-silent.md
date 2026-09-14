---
id: schema-compare-computed-expression-undecidable-silent
title: "`schema compare` verschluckt W137 — die unentscheidbare Frage bleibt unausgesprochen"
status: open
---

# `schema compare` verschluckt W137 — die unentscheidbare Frage bleibt unausgesprochen

## Der Befund (gemeldet aus einem Konsumentenprojekt, Cross-Dialect-Falsch-Positiv-Audit)

Ein Wiederholungs-Audit (Baseline `v1.2.0` gegen `v1.5.1`, PG↔MSSQL/PG↔MySQL/
MSSQL↔MySQL, dieselben Schemas, frische Reverses) bestätigt: drei der vier
ursprünglichen Befunde sind behoben — berechnete Spalten werden jetzt erfasst
(`generation`-Block in allen drei Dialekten), benannte UNIQUE-Constraints
bleiben erhalten (`unique_constraint`-Feld), FK-Constraint-Namen sind echt
(`fk_order_customer` statt `_fk_customer_id`), und alle zehn
`TABLE_COLUMN_REQUIRED_RELAXED`-Falsch-Positiven sind weg. Falsch-Positiv-Quote
PG↔MSSQL 50 % → 38 %, PG↔MySQL 48 % → 38 %.

**Neuer Fund, unabhängig von den vier ursprünglichen:** Der Berechnungsausdruck
einer `generation: {type: computed, ...}`-Spalte wird jetzt zwar **erfasst**
(Text landet im Modell), aber ein `schema compare` zwischen zwei Schemas mit
unterschiedlichem Ausdruck (z. B. `quantity * unit_price` vs.
`quantity * unit_price * 1.19`) erzeugt **keinen**
`TABLE_COLUMN_GENERATION_CHANGED`-Fund — die beiden gelten als gleich. Für
`generation: {type: identity, mode: ...}` funktioniert derselbe Vergleich
korrekt (ein `BY_DEFAULT`↔`ALWAYS`-Wechsel wird erkannt).

Das ist kein neuer Datenverlust (der war der ursprüngliche Befund und ist
behoben) — es ist eine **Vergleichs-Blindheit**: die Information steht jetzt
im Modell, wird aber beim Vergleich nicht genutzt. Praktisch bedeutet das:
zwei Schemas mit inhaltlich unterschiedlicher Berechnungsformel laufen durch
`schema compare` als identisch durch, ohne jede Meldung.

## Warum (gemessen im Code, 2026-09-14)

Das ist **kein Versehen, sondern zur Hälfte Absicht** — und die andere Hälfte
ist die eigentliche Lücke.

**Die Absicht (Weg C, Eigner bestätigt 2026-09-10,
[`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md)):**
Ein Berechnungsausdruck ist roher SQL-Text — Autorentext und Serverform
stimmen nie wortgleich überein. Ob sich der Ausdruck *inhaltlich* geändert
hat, ist nur entscheidbar, wenn eine Herkunfts-Überlagerung (`authorship`)
oder ein Server-Sandkasten (`serverForm`) vorliegt. Ohne beides gilt die
Frage als **unentscheidbar**, und der Entwurf sagt ausdrücklich: dann wird
**nichts geplant** — ein Fehlalarm hier kostet im schlimmsten Fall einen
`AccessExclusiveLock`-Tabellen-Neubau, einen verlorenen Index oder eine
gebrochene abhängige Sicht, teurer als bei jedem anderen rohen Textfeld.

Umgesetzt in
`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/RawTextFolding.kt`
(`columnGeneration()`, Zeilen 73-98): Ist die Frage unentscheidbar (`decide()` liefert
`null`), wird die gefaltete Kopie so überschrieben, dass sie dem Original
gleicht — `TableComparator.kt:289-293` sieht dann Gleichheit und erzeugt kein
`ColumnDiff.generation`. Bei `ColumnGeneration.Identity` passiert das nicht:
`columnGeneration()` gibt für jeden Nicht-`Computed`-Typ sofort `desired`
zurück (Zeilen 79-80), der Vergleich läuft also strukturell direkt auf
`mode`/`sequenceName`/`legacySerialSyntax` — deshalb wirkt Identity
entscheidbar und Computed nicht. Beide Zweige sind vollständig (der `when` in
`ColumnGenerationTransition.of()`, `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ColumnGenerationTransition.kt` (Zeilen 53-58),
deckt `Identity` und `Computed` ab) — die Asymmetrie liegt einzig in
`RawTextFolding`, nicht in einem fehlenden `when`-Zweig.

**Die eigentliche Lücke:** Der Entwurf selbst sagt „nichts geplant heißt nicht
nichts gesagt" — unentscheidbar soll **W137** melden
(`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ComputedExpressionDecidability.kt`, Zeilen 29-32,
Kommentar Zeilen 25-27: „Nichts zu planen heisst aber nicht, nichts zu sagen:
waere die Frage unbeantwortet **und** unerwaehnt, aenderte jemand den
Ausdruck, bekaeme Exit 0 und die Datenbank rechnete weiter nach der alten
Formel."). Genau dieser Satz trifft aber zusätzlich auf `schema compare` zu —
und dort ist er nicht eingelöst:

- `ComputedExpressionDecidability.diagnostics(...)` wird **ausschließlich**
  von `SchemaMigrateRunner.kt:673` aufgerufen (einziger Produktionscaller im
  Repo).
- `SchemaCompareRunner.kt` (`hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`)
  ruft diese Funktion nirgends auf.
- `SchemaCompareHandler.kt`
  (`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaCompareHandler.kt`, Zeilen 444-454)
  projiziert nur `diff.generation` — das ist bei einer unentscheidbaren
  Computed-Änderung bereits `null`, also gibt es dort auch nichts zu
  projizieren.
- `SchemaCompareWiring.kt:50`
  (`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareWiring.kt`)
  konstruiert `SchemaComparator()` ohne Argumente — `authorship`/`serverForm`
  sind also für `schema compare` ohnehin nie gesetzt
  (`hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`, Zeilen 20-29),
  was die Undecidable-Rate für `compare` strukturell höher macht als für
  `migrate` (dort können Herkunfts-Overlay oder Sandkasten zumindest
  zugeschaltet werden).

Ergebnis: ein einfacher `schema compare` gegen zwei Schemas mit geänderter
Berechnungsformel meldet **weder** einen Diff-Fund **noch** eine Warnung —
er meldet gar nichts. Genau das Szenario, vor dem der eigene Code-Kommentar
warnt, ist für `schema compare` (und den MCP-`schema_compare`-Tool-Call, der
denselben Pfad nutzt) unabgesichert.

## Was zu tun bleibt

**In Scope — die W137-Lücke schließen:** `ComputedExpressionDecidability.diagnostics(...)`
(oder eine äquivalente Meldung) auch aus `SchemaCompareRunner.execute()`
heraus aufrufen, mit denselben `current`/`desired`-Schemas, die der Vergleich
ohnehin hat. Für den MCP-Pfad (`SchemaCompareHandler`) entsprechend in die
Findings-Projektion aufnehmen. Der Effekt ist bewusst **nur eine Meldung**,
keine Verhaltensänderung am Diff selbst — Weg C bleibt unangetastet, `schema
compare` soll weiterhin keinen Fehlalarm auf eine unentscheidbare
Computed-Änderung werfen, aber jetzt sagen, dass er es nicht konnte, statt
stillschweigend „gleich" zu melden.

**Nicht in Scope — bewusst nicht angefasst:**

- Die Vergleichsregel selbst (Weg C) ist Eigner-entschieden und bleibt so;
  dieses Ticket schlägt keine Rücknahme vor.
- Die übrigen, im selben Audit als unverändert gemeldeten Punkte sind
  bekannte, bestehende Einschränkungen, keine neuen Funde — nicht Teil dieses
  Tickets: rohe CHECK-Constraint-Ausdrücke bleiben dialektspezifischer Text
  (größter Einzelposten, 4 von 8 Falsch-Positiven pro PG-Vergleich, ohne
  Vorher/Nachher-Details); MSSQLs explizites `on_update: no_action` gegen
  implizites Weglassen; `ON DELETE RESTRICT` geht verloren; `VIEW_CHANGED`
  durch Dialekt-Text; die MSSQL-View trägt weder `columns` noch
  `dependencies`; `engine: InnoDB` erzeugt vier Info-Funde;
  `custom_types.order_status REMOVED` bei MySQL ist irreführend (der Enum ist
  inline an der Spalte, nicht weg).

## Herkunft

Gemeldet 2026-09-14 als Zweitmessung eines länger laufenden
Falsch-Positiv-Audits (Baseline `v1.2.0`), gegen `v1.5.1`. Der Fund zur
Vergleichs-Blindheit ist neu gegenüber der Erstmessung — er wurde erst
sichtbar, nachdem der ursprüngliche Datenverlust-Befund (Computed-Spalten
fielen beim Reverse ganz weg) mit `generated-column-expression-dropped.md`
behoben war. Kein bestehendes Tracking-Dokument für den Gesamt-Audit
gefunden; dieses Ticket deckt ausschließlich den neuen, konkreten Fund ab.
