---
status: accepted
date: 2026-11-23
decision-makers: pt9912
consulted: docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md, docs/adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md, docs/adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md
informed: hexagon/core (RawTextFolding, ConstraintDiffContract, EnumCheckProjection, ColumnTypes), spec/cli-spec.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# `schema compare`: ein Wertelisten-CHECK mit gleichem Wertevorrat ist keine Änderung

> **Status: accepted (2026-11-23).** Übersteuert [ADR 0056](0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
> in **einem** Punkt seiner Grenze: `schema compare` setzt `spalte IN (…)`, `spalte = ANY (ARRAY[…])` und eine
> OR-Kette von Gleichheiten gleich, wenn sie denselben Wertevorrat derselben Spalte aufzählen, unabhängig von der
> Reihenfolge. Alles andere aus ADR 0056 bleibt, wie es ist.

## Kontext und Problemstellung

ADR 0056 zog für `schema compare` die Grenze „Schreibweise, nie Bedeutung" und nannte als Beispiel ausdrücklich
`= ANY(ARRAY[…])` gegen `IN (…)`: zwei Formen desselben Prädikats, die nur der zielbewusste Vergleich
([ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md)) gleichsetzt. Issue #80 zeigt, was das in der
Praxis heißt:

- PostgreSQL gibt jeden `IN`-CHECK als `(spalte = ANY (ARRAY['a'::text, 'b'::text]))` zurück. Ein Vergleich Datei
  gegen Datenbank meldet deshalb für **jede** Tabelle mit Werteliste `~ constraint`, auch direkt nachdem die
  Datenbank aus genau dieser Datei erzeugt wurde. `IDENTICAL` ist für solche Tabellen unerreichbar.
- `schema migrate` wertet dasselbe Paar als gleich und plant nichts. Die beiden Befehle widersprechen sich am
  selben Paar.
- Seit 1.12.2 liest der PostgreSQL-Reader CHECKs aus `pg_constraint` (Issue #77). Damit sieht sie auch eine Rolle,
  die nicht Eigentümerin der Tabelle ist. Vorher fehlten ihr die CHECKs still, und der Fehlalarm blieb verdeckt.

Die Gleichsetzung in ADR 0056 auszuschließen hatte einen Grund: Zwei Formen desselben Prädikats gleichzusetzen ist
eine Entscheidung über die Bedeutung, kein Schreibweise-Detail. Für einen Wertelisten-CHECK ist diese Entscheidung
aber längst getroffen. ADR 0055 setzt die Formen im zielbewussten Vergleich gleich, die Erkennung
(`EnumCheckProjection`) ist eng und formbasiert, und die Aussage „die Spalte nimmt genau diese Werte an" hängt nicht
an der Reihenfolge.

## Betrachtete Optionen

- **Bei ADR 0056 bleiben.** Der Fund ist dokumentiert („Was trotzdem gemeldet wird"), und `schema migrate` ist die
  zielbewusste Prüfung. Folge: Der häufigste Prüfschritt, „steht die Datenbank wie die Datei?", meldet unter
  PostgreSQL dauerhaft einen Unterschied, den es nicht gibt.
- **Nur Wertelisten gleichsetzen** (gewählt): dieselbe enge Erkennung wie im zielbewussten Vergleich, als
  **Mengen**-Vergleich der Werte.
- **Allgemein Prädikate normalisieren** (umgestellte Konjunktionen, vertauschte Operanden): bräuchte einen Parser und
  wäre genau die Bedeutungsentscheidung, die ADR 0056 zu Recht ausschließt.

## Entscheidung

1. **Gleich ist, was denselben Wertevorrat derselben Spalte aufzählt.** Zwei CHECKs gleichen Namens sind in
   `schema compare` unverändert, wenn beide Ausdrücke von `EnumCheckProjection.valuesOf` als Werteliste **derselben**
   Spalte erkannt werden und die Wertemengen gleich sind. Erkannt werden die Formen, die die Erkennung heute schon
   kennt: `IN`-Liste, `= ANY (ARRAY[…])` mit Casts an Literal und Spalte, die einelementige Form `=` und eine
   OR-Kette von Gleichheiten.
2. **Die Spalte muss es auf beiden Seiten geben**, unter demselben Namen und als **Textspalte** (`text`, auch mit
   Länge). Ein CHECK über eine andere Spalte bleibt ein Unterschied.
3. **Casts zählen.** Die Erkennung übergeht jeden Cast; ein Cast kann aber den Wert ändern (`'ABC'::character` ist
   `'A'`), und an einer `char(n)`-Spalte vergleicht PostgreSQL mit Auffüllen. Gleichgesetzt wird deshalb nur, wenn
   jeder Cast in beiden Ausdrücken auf `text` oder `character varying` ohne Länge geht. Das sind die Casts, die
   PostgreSQL an einer Textspalte selbst setzt. Eine `char(n)`-Spalte (`::bpchar`) bleibt beim Textvergleich.
4. **Ein anderer Wertevorrat bleibt eine Änderung.** Ebenso `NOT IN`, eine Verknüpfung mit `AND`, ein Vergleich mit
   etwas anderem als String-Literalen und alles, was die Erkennung nicht als Werteliste liest. Das sind die Grenzen
   von `EnumCheckProjection`, sie werden hier nicht erweitert.
5. **Nur der Vergleichswert.** Wie bei jeder Faltung aus ADR 0056 trägt ein gemeldeter Fund die unveränderten
   Ausdrücke beider Seiten, und keine kanonische Form gelangt in ein Artefakt oder in DDL.
6. **Nur `schema compare`** (CLI und MCP, eine Semantik nach
   [ADR 0057](0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md)). `schema migrate` und der
   Fingerabdruck sind nicht betroffen; sie behandeln den Wertevorrat bereits über ADR 0055.

Alles Übrige aus ADR 0056 bleibt unverändert, insbesondere: Umgestellte Konjunktionen, vertauschte Operanden,
gliedernde Klammern und die Groß-/Kleinschreibung von Schlüsselwörtern bleiben Unterschiede.

## Konsequenzen

- Datei gegen PostgreSQL-Datenbank ergibt für eine Tabelle mit Wertelisten-CHECK wieder `IDENTICAL`, wenn die Werte
  übereinstimmen. `schema compare` und `schema migrate` sind sich an diesem Paar einig.
- Eine geänderte Werteliste bleibt sichtbar, als `~ constraint` mit beiden Originalausdrücken.
- Das Handbuch nennt `IN` gegen `= ANY` nicht mehr unter „Was trotzdem gemeldet wird". Die Spec von
  `schema compare` nennt die Gleichsetzung.
- Kein neuer Algorithmus-Tag: Der Fingerabdruck ändert sich nicht.
- **Grenze, gemessen an PostgreSQL 18:** An einer `varchar`-Spalte gibt PostgreSQL `b IN ('OFF','ON')` als
  `((b)::text = ANY ((ARRAY['OFF'::character varying, 'ON'::character varying])::text[]))` zurück. Diese Form
  erkennt `EnumCheckProjection` nicht, sie bleibt in `schema compare` ein Fund. Sie in die Erkennung aufzunehmen
  ändert den zielbewussten Vergleich und den Fingerabdruck (ADR 0048, ADR 0055) und ist deshalb eine eigene
  Entscheidung.
