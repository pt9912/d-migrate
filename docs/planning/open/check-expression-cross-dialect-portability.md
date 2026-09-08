---
id: check-expression-cross-dialect-portability
title: "CHECK-Ausdruecke reisen als roher Dialekt-Text, ungeprueft"
status: open
---

# CHECK-Ausdruecke reisen als roher Dialekt-Text, ungeprueft

## Befund (gemessen, PostgreSQL 16)

```sql
CONSTRAINT ck_email CHECK (email LIKE '%@%')
CONSTRAINT ck_price CHECK (unit_price > 0)
```

kommt aus dem Reverse zurueck als

```
ck_email/CHECK/expr=((email ~~ '%@%'::text))
ck_price/CHECK/expr=((unit_price > (0)::numeric))
```

`~~` ist PostgreSQLs interner Operator fuer `LIKE`, `::` seine Cast-Form.
Beides kennt weder T-SQL noch MySQL noch SQLite. Ein `schema generate
--target mssql` aus diesem Modell rendert den Text unveraendert in die DDL
und erzeugt damit **ungueltiges SQL**, das erst der Zielserver ablehnt.

## Warum das anders liegt als Sichten und Routinen

Fuer die beiden anderen Sorten rohen Texts gibt es je einen Mechanismus:

| Textsorte | Was heute geschieht |
| --- | --- |
| Sichten-Rumpf | `ViewQueryTransformer.assessPortability` beurteilt **inhaltlich** (`::`, Backticks, `LIMIT` gegen T-SQL, `[dbo]`-Quoting) und schreibt teilweise um; was nicht traegt, meldet `E053` |
| Routinen-Rumpf | Entscheidung an der **Herkunft** ([ADR 0054](../../adr/0054-routinen-ruempfe-werden-nicht-uebersetzt.md)); fremder `source_dialect` → `E053` |
| CHECK-Ausdruck, `IndexDefinition.where`, `IndexColumn.expression` | **nichts** |

Und der Grund, warum die Routinen-Antwort hier nicht einfach uebernommen
werden kann: `ConstraintDefinition` traegt **kein** `sourceDialect`. Es gibt
also nicht einmal die Herkunft, an der ADR 0054 entscheidet — ein
CHECK-Ausdruck ist der einzige rohe Text im Modell ohne jedes Signal.

## Warum eine inhaltliche Beurteilung hier plausibler ist als bei Routinen

ADR 0054 verwarf die inhaltliche Beurteilung fuer Routinen-Ruempfe, weil
prozedurale Sprachen keine Grundstruktur teilen. Ein CHECK-Ausdruck ist aber
kein Programm, sondern ein **skalarer Ausdruck** — er ueberlappt zwischen den
Dialekten noch staerker als ein `SELECT`, und die Marker sind dieselbe
Familie, die `assessPortability` schon kennt (`::`, `~~`, Backticks,
T-SQL-Klammern). Der `ViewQueryTokenizer` steht ebenfalls schon.

Die naheliegende Richtung ist deshalb, `assessPortability` auf die drei
uebrigen Textfelder auszudehnen, statt einen zweiten Mechanismus zu bauen.

## Was der Schnitt klaeren muss

- **Wo die Beurteilung greift.** `schema generate` (dort entsteht die
  ungueltige DDL) und der Diff-Pfad; `schema compare` bleibt streng, wie
  ueberall.
- **Was bei „nicht portabel" geschieht.** `E053` wie bei Sichten — der
  Constraint faellt benannt weg statt ungueltig gerendert zu werden. Das ist
  eine Verhaltensaenderung: heute entsteht DDL, die der Server ablehnt,
  danach entsteht sie gar nicht.
- **Ob `~~` umgeschrieben wird.** `email ~~ '%@%'` ist woertlich
  `email LIKE '%@%'` — eine Umschreibregel, kein Verwerfen. Dasselbe gilt
  fuer `(0)::numeric` → `0`. Wo eine Regel existiert, ist Umschreiben besser
  als Ablehnen; die Grenze zwischen „Regel" und „Parser" ist dieselbe wie in
  [ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md).
- **Das Verhaeltnis zu ADR 0053.** Jener ADR sagt, roher Text werde **nicht
  lokal normalisiert**. Eine Portabilitaets-*Beurteilung* ist keine
  Normalisierung — sie aendert den Text nicht, sie lehnt ihn ab. Eine
  Umschreibregel dagegen aendert ihn, und zwar auf einem Weg, der bis in die
  erzeugte DDL laeuft. Der Schnitt muss diese Grenze ausdruecklich ziehen.

## Herkunft

Externe Durchsicht der neutralen Form (2026-09-08), am PostgreSQL-Reverse
nachgemessen. Verwandt, aber nicht dasselbe:
[`raw-sql-text-drift.md`](raw-sql-text-drift.md) fragt, warum derselbe Text
gegen **denselben** Server driftet; hier geht es um seine Gueltigkeit auf
einem **anderen**.
