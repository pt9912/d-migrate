# `schema generate` meldet Fehler nur im Report, nicht im Ausgang

- **Status**: Befund (gemessen), Eignerentscheidung offen
- **Trigger**: Konsumentenmeldung mit Repro, geprüft gegen v1.3.1 und
  unverändert gegen v1.4.0.
- **Aktivierungsbedingung**: sobald entschieden ist, ob der Ausgang sich
  ändern darf (siehe „Warum das nicht nur ein Flag ist").

## Befund

Ein `schema generate`, das einen Bestandteil nicht rendern kann, lässt ihn
weg und endet mit **Exit 0**. Nachgemessen mit dem ausgelieferten
1.4.0-Binary: ein Schema mit einer CHECK-Constraint und einer berechneten
Spalte, beide mit PostgreSQL-Cast (`::`), gegen `--target mssql`.

```
EXIT=0
⚠ Action required [E053]: The computed expression of column 'line_total' …
⚠ Action required [E053]: The CHECK expression of constraint 'ck_quantity' …
```

Was dabei entsteht, ist nicht nur unvollständig, sondern inhaltlich anders:

```sql
CREATE TABLE [order_items] (
    [id] INT IDENTITY(1,1) NOT NULL,
    [quantity] INT NOT NULL,
    [unit_price] DECIMAL(10,2) NOT NULL,
    [line_total] DECIMAL(12,2),          -- war eine berechnete Spalte
    CONSTRAINT [pk_order_items] PRIMARY KEY ([id])
);                                        -- die CHECK-Constraint fehlt ganz
```

`line_total` ist im Ziel eine **gewöhnliche, beschreibbare** Spalte, und die
Regel, die `quantity > 0` sicherstellte, ist weg. Wer auf den Rückgabestatus
statt auf den Report sieht, bekommt davon nichts mit.

## Dieselbe Sache, fünf Einstufungen

Das ist der Kern: es gibt keine gemeinsame Antwort auf „ist das ein Fehler?".

| Oberfläche | Was sie sagt |
| --- | --- |
| stderr (CLI) | `⚠ Action required [E053]` |
| Sidecar-Report YAML | `type: action_required`, `summary.warnings: 0` |
| `--output-format json` | `type: action_required`, `status: "completed"` |
| MCP-Tool `schema_generate` | `severity: "error"` (`SchemaGenerateHandler` bildet `NoteType.ACTION_REQUIRED` darauf ab) |
| Code-Ledger | `level: error` (E053, E057) |
| **Exit-Code / `isError`** | **Erfolg** |

Der MCP-Handler sagt es in seinem eigenen KDoc: `SkippedObject` → severity
`error`, „the object did not make it into the DDL — **clients should treat it
as blocking**". Genau das kann ein Client am Tool-Ergebnis aber nicht sehen.

## Die Asymmetrie, die 1.4.0 dazugebracht hat

Seit 1.4.0 hält `schema migrate` **an**, bevor ein Ausdruck aus einem fremden
Dialekt an den Server geht: `E053`, Exit 8, nichts angewandt. Derselbe Code,
derselbe Ausdruck, dieselbe Prüfung — und je nach Kommando der eine Ausgang
oder der andere. Das war vor 1.4.0 konsistent (beide schwiegen) und ist es
jetzt nicht mehr.

## Warum das nicht nur ein Flag ist

Der Melder schlägt zwei Wege vor: (a) Exit ≠ 0, sobald ein Finding
`severity: error` trägt, oder (b) ein `--fail-on-error`, das es optional
erzwingt. Ein `--fail-on-error` gibt es heute nicht.

Weg (a) ist ein **Bruch am Ausgangsvertrag**: jede Automatisierung, die heute
grün durchläuft und Findings in Kauf nimmt, wird rot. Das kann richtig sein —
es ist der Sinn des Vorschlags —, aber es ist eine bewusste Entscheidung und
kein Bugfix. `spec/cli-spec.md` Abschnitt 2.1 sagt heute nur „Warnungen ohne
Fehler → Exit 0" und lässt offen, was `action_required` ist; das ist die
Lücke, aus der der Befund kommt.

Vor dem Bauen zu klären:

1. **Ist `action_required` ein Fehler?** Solange die fünf Oberflächen oben
   verschiedene Antworten geben, ist jede Ausgangsregel willkürlich. Die
   Antwort gehört in `spec/cli-spec.md` Abschnitt 2.1, nicht in den Code.
2. **Welcher Exit-Code?** `8` (`MIGRATION_BLOCKED`) hat die passende
   Bedeutung „vor Ausführung nicht renderbar" und wäre der Gleichklang mit
   `schema migrate`. `3` (`VALIDATION_ERROR`) passt nicht — das Schema ist
   gültig, nur das Ziel kann es nicht.
3. **Bruch oder Flag?** Wenn (a), dann mit CHANGELOG-Eintrag als
   Verhaltensänderung und einer Ausnahme für die, die es nicht wollen. Wenn
   (b), dann heißt der Default weiterhin „still" — und der Befund bleibt für
   alle bestehen, die das Flag nicht kennen.

## Berührte Stellen

- `spec/cli-spec.md` Abschnitt 2.1 (Exit-Code-Regeln) — die Lücke
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaGenerateHandler.kt`
  (`ACTION_REQUIRED` → `severity: error`, ohne `isError`)
- Der CLI-Ausgangspfad von `schema generate`

## Repro

`docs/planning/open/` trägt keine Fixtures; die verwendete Schemadatei ist
sechs Zeilen Nutzlast — eine Spalte mit
`generation: {type: computed, expression: "((quantity)::numeric * unit_price)"}`
und eine Constraint mit `expression: "((quantity)::numeric > (0)::numeric)"`,
dann `schema generate --target mssql`.
