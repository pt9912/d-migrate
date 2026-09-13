# `action_required` bestimmt den Ausgang

> **Status:** In Arbeit seit 2026-09-13 (P0 abgeschlossen: Erhebung + sechs `SkippedObject`-Lücken geschlossen, Voraussetzung für die Ausgangsregel; P1–P4 offen).
> **Trigger:** Konsumentenmeldung, gegen v1.3.1 und unverändert gegen v1.4.0
> nachgemessen: `schema generate` lässt einen nicht renderbaren Bestandteil
> weg und endet mit Exit 0.

## Befund

Nachgemessen mit dem ausgelieferten 1.4.0-Binary — ein Schema mit einer
CHECK-Constraint und einer berechneten Spalte, beide mit PostgreSQL-Cast
(`::`), gegen `--target mssql`:

```
EXIT=0
⚠ Action required [E053]: The computed expression of column 'line_total' …
⚠ Action required [E053]: The CHECK expression of constraint 'ck_quantity' …
```

Was dabei entsteht, ist nicht nur unvollständig, sondern inhaltlich anders:

```sql
CREATE TABLE [order_items] (
    [line_total] DECIMAL(12,2),   -- war eine berechnete Spalte
    CONSTRAINT [pk_order_items] PRIMARY KEY ([id])
);                                 -- die CHECK-Constraint fehlt ganz
```

`line_total` ist im Ziel eine **gewöhnliche, beschreibbare** Spalte, und die
Regel, die `quantity > 0` sicherstellte, ist weg. Wer auf den Rückgabestatus
statt auf den Report sieht, bemerkt davon nichts.

## Die Ursache: eine Stufe ohne Bedeutung im Vertrag

d-migrate kennt drei Stufen — `info`, `warning`, `action_required`. Der
Exit-Code-Vertrag in [`cli-spec.md` 2.1](../../../spec/cli-spec.md) kennt
davon zwei: „Warnungen ohne Fehler → Exit 0" und „bei mehreren Fehlern der
spezifischste Code". `action_required` kommt dort nicht vor. Es ist weder
Warnung noch Fehler und fällt zwischen die Regeln — und was zwischen die
Regeln fällt, wird zu dem, was der Code zufällig tut.

Deshalb geben fünf Oberflächen fünf Antworten. Sie sind nicht widersprüchlich
gebaut, sie füllen dieselbe Lücke je für sich:

| Oberfläche | Was sie sagt |
| --- | --- |
| stderr (CLI) | `⚠ Action required [E053]` |
| Sidecar-Report YAML | `type: action_required`, `summary.warnings: 0` |
| `--output-format json` | `type: action_required`, `status: "completed"` |
| MCP-Tool `schema_generate` | `severity: "error"` — das Protokoll kennt nur `info`/`warning`/`error`, der Handler **musste** wählen |
| Code-Ledger | `level: error` (E053, E057) |
| **Exit-Code / `isError`** | **Erfolg** — weil niemand gefragt hat |

Der MCP-Handler sagt es in seinem eigenen KDoc: `SkippedObject` → severity
`error`, „the object did not make it into the DDL — **clients should treat it
as blocking**". Genau das kann ein Client am Tool-Ergebnis nicht sehen.

## Die Asymmetrie, die 1.4.0 dazugebracht hat

Seit 1.4.0 hält `schema migrate` **an**, bevor ein Ausdruck aus einem fremden
Dialekt an den Server geht: `E053`, Exit 8, nichts angewandt. Derselbe Code,
derselbe Ausdruck, dieselbe Prüfung — und je nach Kommando der eine Ausgang
oder der andere. Vor 1.4.0 schwiegen beide konsistent.

## P0-Erhebung (2026-09-13): die Stufe ist im Code umstritten, nicht nur unbestimmt

Beim Durchgehen der Emissionsstellen kam eine **vierte** Einstufung dazu, die
oben fehlte — und sie ist die unangenehmste, weil sie ausdrücklich getroffen
wurde.

`TransformationNoteDiagnostics` (in `driver-common`) bildet jede
`ACTION_REQUIRED`-Notiz auf eine **`WARNING`**-Diagnose ab, wenn derselbe
Hinweis im Migrationspfad geführt wird. Die KDoc begründet das:

> `ACTION_REQUIRED` wird bewusst zu `WARNING`: die Diagnose-Ebene des Diffs
> kennt keine dritte Stufe, und ein Blocker wäre sie nicht — **das Statement
> entsteht ja.**

Damit stehen zwei Lesarten live nebeneinander, jede an ihrem Ort konsequent:

- **(A)** „das Ergebnis trägt nicht, was die Eingabe verlangt" — die Lesart des
  MCP-Handlers (`severity: error`, KDoc: „clients should treat it as
  blocking") und des Code-Ledgers (`level: error`).
- **(B)** „etwas braucht deine Aufmerksamkeit, aber der Lauf hat seine Arbeit
  getan" — die Lesart der Diff-Diagnosen.

Dieser Slice wählt (A). Das heißt: die Entscheidung in
`TransformationNoteDiagnostics` wird **umgedreht**, nicht übergangen, und ihr
Argument gehört beantwortet.

**Gemessen ist es halb falsch.** „Das Statement entsteht ja" stimmt für die
Tabelle — aber nicht für das Objekt, um das es geht. Im Repro oben erzeugt die
CHECK-Constraint `ck_quantity` **gar kein** Statement; das Wort `CHECK` kommt
in der Ausgabe nur im Kommentar vor. Für die berechnete Spalte stimmt es
ebenfalls nur halb: die Spalte entsteht, aber als gewöhnliche, beschreibbare.

## Der Nebenbefund, der ein besserer Hebel sein könnte

Derselbe Report sagt:

```
  action_required: 2
  skipped_objects: 0
```

Die Constraint **wurde** übersprungen und zählt trotzdem nicht als
übersprungenes Objekt. `ManualActionRequired` kann beides erzeugen —
`toNote()` und `toSkipped()` —, und der Generate-Pfad nimmt hier nur die
Notiz.

Das ist womöglich der genauere Hebel als die Notiz-Stufe: ein
`SkippedObject` sagt „dieses Objekt kam nicht in die Ausgabe" und ist damit
genau die Aussage, auf die eine Ausgangsregel gehören würde. Der MCP-Handler
stuft `SkippedObject` **schon heute** als `error` ein, mit derselben
KDoc-Zusage („the object did not make it into the DDL"). Vor P1 ist deshalb zu
entscheiden:

- **Weg α:** Ausgangsregel an der Notiz-Stufe (`action_required`).
- **Weg β:** Ausgangsregel an „wurde etwas übersprungen?", und die
  Emissionsstellen, die ein Objekt fallen lassen, erzeugen zusätzlich das
  `SkippedObject`, das ihnen fehlt.

**Entschieden (2026-09-13): Weg β.** Er trennt „ein Objekt fehlt" von „ein
Hinweis liegt an" und behebt nebenbei den falschen Zähler
(`skipped_objects: 0` bei tatsächlich übersprungener Constraint). Die
Ausgangsregel greift damit an `SkippedObject`, nicht an `NoteType
.ACTION_REQUIRED` — eine Emissionsstelle, die nur einen Hinweis anhängt, ohne
ein Objekt fallen zu lassen, ändert den Ausgang nicht.

## P0-Erhebung, Teil 2: alle Emissionsstellen klassifiziert (2026-09-13)

11 Dateien, 17 Codestellen, drei Formen. Für die Ausgangsregel (Weg β) zählt
nur eine Frage je Stelle: **verschwindet ein Objekt aus der Ausgabe, ohne dass
`SkippedObject` es sagt?**

| Code | Stelle | Objekt | Heute `SkippedObject`? | Form |
| --- | --- | --- | --- | --- |
| E054 | `SqliteRoutineDdlHelper` (Function) | Funktion | ja | — |
| E054 | `SqliteRoutineDdlHelper` (Procedure) | Prozedur | ja | — |
| E057 | `SqliteCapabilityDdlSupport` (zirkuläre FK) | FK-Constraint | ja | — |
| E052 | `SqliteTableDdlSupport`/`AbstractDdlGenerator` (Spatial-Block) | Tabelle | ja, über `blocksTable` | — |
| E054 | `SqliteCapabilityDdlSupport` (COMPOSITE-Typ) | Custom Type | ~~nein~~ **behoben** | — |
| E054 | alle fünf `*ColumnConstraintHelper` (EXCLUDE) | Constraint | ~~nein~~ **behoben** | — |
| E053 | alle fünf `*ColumnConstraintHelper` (CHECK nicht portabel) | Constraint | ~~nein~~ **behoben** | der ursprüngliche Repro, Hälfte 1 |
| E053 | alle fünf `generateColumnSql`/`renderColumn` (Computed nicht portabel, `computedRefusal`) | berechnete Spalte | ~~nein~~ **behoben** | der ursprüngliche Repro, Hälfte 2 — **eigener Codepfad, in der ersten P1-Runde übersehen** |
| E057 | `MysqlIndexPartitionDdlHelper` (Partial Index) | Index | ~~nein~~ **behoben** | — |
| E065 | `MysqlDdlGenerator` (FK auf partitionierter Tabelle) | FK-Constraint | ~~nein~~ **behoben** | — |
| E056 | `SqliteSequenceDdlSupport` / `MysqlSequenceDdlSupport` (Sequenz-Default ohne Helper-Tabelle) | *Facette* einer Spalte | n/a | Degradiert, kein Objektverlust — die Spalte entsteht |
| E057 | `SqliteSequenceDdlSupport` (WITHOUT ROWID) | *Facette* einer Spalte | n/a | wie oben |
| E055 | `SqliteTableDdlSupport` / `PostgresDdlGenerator` (Partitionierung ignoriert) | *Facette* einer Tabelle | n/a | Degradiert, kein Objektverlust — die Tabelle entsteht |
| E060 | `ViewPhaseClassifier` (Split-Phase unbestimmbar) | *Facette* einer Sicht | n/a | Degradiert — die Sicht entsteht, nur die Phasen-Zuordnung ist unsicher |

**Vier echte Lücken**, eine davon der ursprüngliche Befund. Der gemeinsame
Grund: **die Signatur reicht kein `skipped: MutableList<SkippedObject>`
durch.**

- `generateConstraintClause(constraint, notes)` — in **allen fünf**
  `*ColumnConstraintHelper`-Klassen ohne `skipped`-Parameter. Trägt sowohl das
  EXCLUDE-Verweigern als auch (über `RawSqlExpressionPortability
  .notPortableNote`) das CHECK-/Computed-Verweigern aus dem Repro. Eine
  Signaturänderung an einer Stelle behebt drei der vier Lücken auf einmal.
- `generateCustomTypes(types)` (SQLite) — kein `skipped`-Parameter, anders als
  das benachbarte `generateSequences`.
- `generateIndices(tableName, table)` (MySQL) — kein `skipped`-Parameter.
- `generateTable(...)` — der Basisvertrag in `DdlGenerator` selbst trägt kein
  `skipped`; nur die Tabellen-Orchestrierung in `AbstractDdlGenerator` hat es
  (daher funktioniert `blocksTable` für ganze Tabellen, aber nichts
  Feineres).

**Was das für P1 hieß, und was blieb:** die sechs echten Lücken sind
geschlossen (siehe unten). Die vier Facetten-Fälle (E055 ×2,
E056/E057-Sequenz, E060) bleiben unter β bewusst ohne Ausgangswirkung — das
Objekt entsteht, nur unvollständig. Ob das für den Sequenz-Fall (E056/E057)
richtig ist, ist eine offene Frage: eine `DEFAULT`-Klausel, die ohne
Serverzwang verschwindet, ist näher an „trägt nicht, was verlangt wurde" als
an einer reinen Formatfrage. Bleibt fuer die Eignerentscheidung, statt hier
mitentschieden zu werden.


## P0-Fortsetzung: die sechs `SkippedObject`-Lücken geschlossen (2026-09-13)

Kein eigener Sub-Slice, sondern der Teil von P0, den die Erhebung selbst
vorhersagte: „wer die Emissionsstellen erhoben hat, hat β fast schon
gebaut." Ohne diesen Schritt hätte P1 (`--allow-incomplete`, Exit 8) einen
Ausgang verdrahtet, der auf einer Datenquelle (`skippedObjects`) beruht, die
an sechs Stellen leer geblieben wäre — der Ausgang stimmte, aber aus
Zufall, nicht aus Vollständigkeit.

Gebaut und live belegt — der Repro aus der Konsumentenmeldung liefert jetzt
`skipped_objects: 2` statt `0`:

```
⚠ Skipped [E053] computed_expression 'line_total': …
⚠ Skipped [E053] constraint 'ck_quantity': …
```

**Sechs Emissionsstellen tragen jetzt `SkippedObject`**, fünf davon über eine
gemeinsame Signaturerweiterung (`skipped: MutableList<SkippedObject>? = null`
auf `generateConstraintClause`/`generateColumnSql`, real befüllt nur im
Generate-Pfad, `null` und damit folgenlos in Diff-/Rebuild-Pfaden):

- E053 CHECK/Computed nicht portabel — **alle fünf** `*ColumnConstraintHelper`.
- E054 EXCLUDE nicht unterstützt — alle fünf (PostgreSQL rendert EXCLUDE
  nativ und hat dort keinen Skip-Fall).
- E054 SQLite COMPOSITE-Typ — `generateCustomTypes` bekam den Parameter neu.
- E057 MySQL Partial Index — `generateIndices`/`generateIndex` bekamen ihn
  neu.
- E065 MySQL FK auf partitionierter Tabelle — an allen drei Stellen
  (Spaltenreferenz-Loop, Constraint-Loop, `handleCircularReferences`), über
  einen gemeinsamen `partitionedFkSkip`-Helper statt dreifacher Duplikation.

**Eine Korrektur am eigenen Befund unterwegs:** Die P0-Tabelle hatte
„E053, alle fünf `*ColumnConstraintHelper` (CHECK/Computed nicht portabel)"
als **einen** Fund geführt. Der erste Live-Test nach der Constraint-Reparatur
zeigte `skipped_objects: 1` — die Constraint stand drin, `line_total` nicht.
Computed-Column-Refusal läuft über `RawSqlExpressionPortability
.computedRefusal`, einen **eigenen** Codepfad je Dialekt
(`generateColumnSql`/`renderColumn`, nicht `generateConstraintClause`), den
die erste Reparatur nicht erreichte — obwohl er die Hälfte des
namensgebenden Repros war. Nachgezogen, ebenfalls für alle fünf Dialekte,
gleiches Muster.

**Bewusst unverändert, je mit eigener Begründung im P0-Table oben:**
E055 (Partitionierung ignoriert), E056/E057-Sequenz (Default entfällt),
E060 (Split-Phase unsicher) — die Tabelle/Spalte/Sicht entsteht, nur eine
Facette fehlt. Ob das für E056/E057 richtig ist, bleibt die offene Frage aus
der Erhebung.

**Belegt:** je Dialekt eine neue Spec
(`<Dialekt>ActionRequiredSkippedObjectsTest`), die über den vollen
`generate()`-Pfad prüft, dass CHECK/EXCLUDE/Computed als `SkippedObject` mit
Typ, Name und Code ankommen — nicht nur als Notiz. Eine davon per absichtlich
falscher Zusicherung als laufend belegt, Rücknahme geprüft. `detekt` schlug
dabei einmal zu (`CyclomaticComplexMethod` in `MysqlDdlGenerator.generateTable`
nach dem E065-Zusatz) — behoben durch echte Aufteilung
(`inlineForeignKeyLines` extrahiert), nicht durch `@Suppress`.

Grün: alle 49 Module, `a-check`, `solid-suppression-gate`.

## Ziel

`action_required` bekommt eine Bedeutung im Vertrag, und der Ausgang folgt
daraus — statt je Kommando und Oberfläche neu entschieden zu werden.

**Die Definition geht über das Artefakt, nicht über die Schwere:**

> **`info`** — eine Notiz zum Lauf. Das Ergebnis trägt, was die Eingabe
> verlangt.
> **`warning`** — das Ergebnis trägt, was die Eingabe verlangt, aber etwas
> daran verdient einen Blick (eine Degradation mit Ersatz, eine Annahme, eine
> Emulation).
> **`action_required`** — **das Ergebnis trägt nicht, was die Eingabe
> verlangt.** Ein Bestandteil ist weggefallen oder anders herausgekommen, und
> nur der Anwender kann die Lücke schließen.

**Daraus folgt die Ausgangsregel ohne Einzelfallentscheidung:**

> Ein Lauf mit mindestens einer `action_required`-Meldung endet mit
> **Exit 8**. Warnungen und Notizen allein lassen den Ausgang bei `0`.

Warum 8 und nicht 3: Exit 3 heißt „Validierung fehlgeschlagen" — das Schema
ist aber gültig, nur das Ziel kann es nicht abbilden. Exit 8 heißt heute schon
„durch Blocker nicht renderbar, **vor** Ausführung". Sein Vertragstext wird
dafür von „Migration" auf „was erzeugt werden sollte" verallgemeinert; dann
decken sich `generate` und `migrate`, und die Asymmetrie oben verschwindet als
Nebenwirkung statt als eigene Reparatur.

Für MCP fällt es damit auch: `isError: true` genau dann, wenn der Ausgang
≠ 0 wäre.

## Die Einführung: umstellen, nicht optional machen

Default wird Exit 8; der Ausweg heißt umgekehrt — ein Flag (Arbeitstitel
`--allow-incomplete`), das Exit 0 erzwingt und im Report vermerkt, dass der
Ausgang unterdrückt wurde.

Das `--fail-on-error` aus der Meldung wäre der andere Weg und ist der
schwächere: es lässt den stillen Default für alle stehen, die das Flag nicht
kennen — also für genau die, die der Befund trifft. Ein Gate, das man
einschalten muss, schützt niemanden vor dem, was er nicht weiß. Umgekehrt
herum muss, wer die Lücke in Kauf nimmt, das einmal hinschreiben; danach ist
es eine dokumentierte Entscheidung statt eines Übersehens.

Das ist die Linie, die das Projekt an dieser Stelle schon gewählt hat — im
1.4.0-CHANGELOG steht wörtlich „Lautes Scheitern schlägt stille Degradation",
als es um `stored: false` gegen eine alte Zielversion ging.

**Es bleibt ein Bruch am Ausgangsvertrag.** Wer heute grün durchläuft und
Findings in Kauf nimmt, wird rot. Das ist der Sinn, aber niemand darf davon
überrascht werden: CHANGELOG-Eintrag als Verhaltensänderung mit
Migrationsbeispiel, wie beim Bind-Mount-Wechsel in RC3.

## Scope-Skizze

1. **P0 — Vertrag zuerst.** Die drei Stufen-Definitionen und die Ausgangsregel
   in `spec/cli-spec.md` 2.1; der Vertragstext von Exit 8 verallgemeinert; die
   Entscheidung α/β oben.
   Erhebung dazu: **welche Codes emittieren heute `action_required`**, trifft
   die Definition auf jeden zu, und **lässt die Stelle ein Objekt fallen oder
   hängt sie nur einen Hinweis an?** Wo die Definition nicht trifft, ist der
   Code falsch eingestuft und wird zur Warnung. Angefangen: 17 Emissionsstellen
   in acht Modulen, der Schwerpunkt bei SQLite (9) — die Einzelbewertung steht
   aus.
2. **P1 — Ausgang in der CLI.** Eine Stelle, durch die der Ausgang von
   `schema generate` entsteht; `--allow-incomplete` samt Report-Vermerk.
3. **P2 — MCP nachziehen.** `isError` aus derselben Regel, nicht aus einer
   zweiten Abbildung im Handler.
4. **P3 — Die übrigen Kommandos prüfen.** `schema generate` ist der gemeldete
   Fall; die Regel gilt für jeden Lauf, der Notizen erzeugt. Welche das sind,
   gehört erhoben, bevor die Regel greift.
5. **P4 — Doku.** CHANGELOG als Verhaltensänderung mit Migrationsbeispiel,
   Anwenderhandbuch dort, wo der Ausgang eines Laufs beschrieben ist.

## Akzeptanzkriterien

- Der Repro oben endet mit Exit 8, und die erzeugte DDL ist unverändert (der
  Ausgang ändert sich, nicht das Rendern).
- Mit `--allow-incomplete` endet derselbe Lauf mit Exit 0 und der Report
  vermerkt die Unterdrückung.
- Das MCP-Tool `schema_generate` liefert für denselben Fall `isError: true`.
- `spec/cli-spec.md` 2.1 beantwortet für jede der drei Stufen, was sie für den
  Ausgang bedeutet — ohne dass ein Code einzeln genannt werden muss.
- Kein Lauf, der heute nur `warning`/`info` erzeugt, ändert seinen Ausgang.

## Vorbedingungen

- Eignerentscheidung über den Bruch (Default umstellen statt Opt-in-Flag) —
  **erteilt 2026-09-13**.
- P0 vor P1: eine Spec, die etwas anderes sagt als der Code, ist die Falle,
  vor der `CLAUDE.md` warnt. Beide in derselben Scheibe.

## Repro

Eine Spalte mit
`generation: {type: computed, expression: "((quantity)::numeric * unit_price)"}`
und eine Constraint mit `expression: "((quantity)::numeric > (0)::numeric)"`,
dann `schema generate --target mssql`.
