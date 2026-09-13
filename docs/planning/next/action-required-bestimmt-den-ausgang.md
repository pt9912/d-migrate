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
   in `spec/cli-spec.md` 2.1; der Vertragstext von Exit 8 verallgemeinert.
   Erhebung dazu: **welche Codes emittieren heute `action_required`** und
   trifft die Definition („das Ergebnis trägt nicht, was die Eingabe
   verlangt") auf jeden einzelnen zu? Wo nicht, ist der Code falsch eingestuft
   und wird zur Warnung — das ist Teil dieses Arbeitspakets, nicht ein
   Nachzügler.
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
