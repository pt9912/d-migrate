# `action_required` bestimmt den Ausgang

> **Status:** Draft mit Scope (2026-09-13).
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

- **Weg α:** Ausgangsregel an der Notiz-Stufe (`action_required`), wie oben
  beschrieben.
- **Weg β:** Ausgangsregel an „wurde etwas übersprungen?", und die
  Emissionsstellen, die ein Objekt fallen lassen, erzeugen zusätzlich das
  `SkippedObject`, das ihnen fehlt.

β ist genauer — es trennt „ein Objekt fehlt" von „ein Hinweis liegt an" —
und behebt nebenbei einen falschen Zähler. α ist kleiner. Die Erhebung der
Emissionsstellen (P0, Arbeitspaket 1) muss die Frage ohnehin beantworten: für
jede Stelle ist zu sagen, ob sie ein Objekt fallen lässt oder nur einen
Hinweis anhängt. Wer das erhoben hat, hat β fast schon gebaut.

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
