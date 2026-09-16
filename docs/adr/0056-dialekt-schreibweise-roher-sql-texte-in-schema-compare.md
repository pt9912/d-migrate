---
status: accepted
date: 2026-09-16
decision-makers: pt9912
consulted: docs/planning/in-progress/compare-projektion-und-normalisierung.md, docs/planning/in-progress/compare-falsch-positive-cross-dialekt.md, docs/adr/0053-vergleich-rohen-sql-texts.md, docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md
informed: hexagon/core (RawTextFolding, ConstraintDiffContract, SchemaComparator, MigrationFingerprint), adapters/driving/cli (SchemaCompareWiring), adapters/driving/mcp (McpRuntimeRegistries, McpCoreJobWorkerFactory), spec/cli-spec.md, CHANGELOG.md
---

# Dialekt-Schreibweise roher SQL-Texte — `schema compare` setzt sie gleich, `schema migrate` und der Fingerabdruck nicht

> **Status: accepted (2026-09-16).** Übersteuert
> [ADR 0053](0053-vergleich-rohen-sql-texts.md). `schema compare` vergleicht
> Sichten-Rumpf, CHECK-/EXCLUDE-Ausdruck und Index-Prädikat über eine Form, in
> der die **Dialekt-Schreibweise** gleichgesetzt ist — nie die Bedeutung, und
> ohne Parser. Gefaltet wird nur der Vergleichswert, nie die gemeldete
> Definition. `schema migrate` und der Fingerabdruck normalisieren weiterhin
> nicht; für sie gilt die Linie aus ADR 0053, und dieser ADR übernimmt sie
> ausdrücklich.

## Kontext und Problemstellung

Das neutrale Modell trägt an vier Stellen SQL, das kein Baustein ist, sondern
Text:

- `ViewDefinition.query`
- `ConstraintDefinition.expression` (CHECK/EXCLUDE)
- `IndexDefinition.where`
- `IndexColumn.expression`

[ADR 0053](0053-vergleich-rohen-sql-texts.md) hat für diese Felder zwei Dinge
zugleich entschieden: dass Planung und Post-Compare von `schema migrate` die
Frage „hat sich etwas geändert?" **nicht** über lokale Normalisierung
beantworten, sondern über Server-Form und Herkunft (Entscheidungen 1 bis 3) —
und dass `schema compare` streng bleibt (Entscheidung 4).

Die zweite Hälfte gilt im Code nicht mehr. Seit 1.7.0 setzt `schema compare`
bei CHECK-/EXCLUDE-Ausdrücken und beim Sichten-Rumpf die Dialekt-Schreibweise
gleich; 1.7.1 hat das Identifier-Quoting im CHECK-Pfad ergänzt. Beide
Vergleichsstellen — `SchemaCompareWiring` (CLI) und das MCP-Werkzeug
`schema_compare` (`McpRuntimeRegistries`) — bauen den Comparator mit
`canonicalizeRawExpressions = true`, und
[`spec/cli-spec.md`](../../spec/cli-spec.md) beschreibt das Verhalten.
ADR 0053 sagte weiter „`schema compare` bleibt streng". Zwei normative Aussagen
widersprachen sich, und kein Gate bemerkte es: akzeptierte ADRs sind im Kern
eingefroren, und ob ein ADR dem Code, der Spec oder einem anderen ADR
widerspricht, prüft keines.

Den Anlass, die Linie jetzt zu ordnen, gab eine Konsumentenmessung gegen 1.7.1.
Zwei Reverses desselben Schemas melden weiterhin Funde, die keine
Schemaänderung sind:

| Feld | eine Seite | andere Seite | Differenz |
| --- | --- | --- | --- |
| CHECK | `((shipped_at IS NULL) OR (shipped_at >= placed_at))` | `shipped_at IS NULL OR shipped_at>=placed_at` | Klammern um die Operanden einer `OR`-Komposition |
| CHECK | `status = ANY (ARRAY['NEW'::text, 'PAID'::text])` | `status = ANY (ARRAY['NEW','PAID'])` | Cast (schon gefaltet), Leerraum nach dem Komma (nicht gefaltet) |

Beim **Index-Prädikat** faltet der Compare-Pfad überhaupt nicht. Dort wird
schon eine reine Differenz in Quoting, Leerraum oder Cast als Änderung
gemeldet.

Die Frage dieses ADR: **Darf `schema compare` die Dialekt-Schreibweise roher
SQL-Texte gleichsetzen, obwohl ADR 0053 lokale Normalisierung ausschließt —
und wenn ja, wie weit, mit welcher Grenze und unter welchen Bedingungen?**

## Warum die zwei Pfade verschieden antworten dürfen

`schema compare` und `schema migrate` stellen verschiedene Fragen, und ein
Irrtum kostet verschieden viel.

- **`schema compare`** fragt: „Sind diese zwei Schemata dasselbe?" — häufig für
  zwei Reverses aus verschiedenen Dialekten. Ein **Fehlalarm** kostet einen Fund
  zu viel. Einzeln ist das lästig; gehäuft macht es den Bericht unbrauchbar.
  Vor dem Abbau der ersten Fehlalarme hatte der Konsument eine
  Falsch-Positiv-Quote zwischen 30 und 38 Prozent gemessen.
- **`schema migrate`** fragt: „Was muss auf dem Ziel ausgeführt werden?" Eine
  **übersehene** Änderung kostet eine falsch stehende Datenbank. Dort ist „im
  Zweifel planen" die richtige Richtung. Außerdem hat dieser Pfad Quellen, die
  ohne Normalisierung auskommen: Herkunft und Server-Form.

Für zwei Reverses gibt es diese Quellen nicht. Kein Migrations-Artefakt
verbindet beide Seiten, und es gibt keinen Server, auf dem beide stehen. Ohne
Schreibweise-Faltung bliebe `schema compare` nur der wortgleiche Textvergleich,
und der meldet jeden Dialektunterschied.

## Die Einwände aus ADR 0053, für den Compare-Pfad beantwortet

ADR 0053 hat lokale Normalisierung mit drei Einwänden verworfen und einem
vierten, grundsätzlichen zusammengefasst. Für den Migrate-Pfad gelten sie
unverändert. Für den Compare-Pfad werden sie hier nicht übergangen: sie sind
**nur unter Bedingungen** tragbar, und diese Bedingungen gehören zur
Entscheidung (siehe „Wann die Faltung sich zurückzieht").

1. **Erzeugte DDL.** Nach ADR 0053 wandert ein kanonisierter CHECK-Ausdruck
   über `AddConstraint` bis in die erzeugte DDL. Im Compare-Pfad nicht. Die kanonische Form verlässt die Gleichheitsprüfung nie.
   Ergibt sie Gleichheit, übernimmt der Vergleich den **unveränderten** Text der
   Gegenseite. Ergibt sie keine, meldet er beide Definitionen unverändert. Das
   ist dieselbe Grenze, die `TargetProjection` zieht: eine
   Vergleichsprojektion fließt nicht in das, was gemeldet oder erzeugt wird.
   Außerdem erzeugt `schema compare` keine DDL, und der Pfad, der DDL erzeugt,
   faltet nicht.
2. **Kommentare.** Ein `--`-Kommentar reicht bis zum Zeilenende. Wer den
   Zeilenumbruch zu einem Leerzeichen macht, kommentiert den Rest der
   Bedingung aus. Das stimmt, und ein lexikalischer Kanonisierer kann es nicht
   zuverlässig erkennen. Nachgestellt:
   `a = 1 -- x⏎AND b = 2` und `a = 1 -- x AND b = 2` bedeuten Verschiedenes,
   ergeben nach dem Zusammenziehen aber dieselbe Form. Daraus folgt nicht der
   Verzicht auf jede Faltung, sondern ein **Rückzug**: Steht in einem der
   beiden Texte außerhalb eines Literals ein Kommentarzeichen, wird dieses Feld
   nicht gefaltet.
3. **Dollar-Quoting und Escapes.** PostgreSQLs `$$…$$` ist eine Zeichenkette,
   die ein Quote-Scanner nicht als solche erkennt. Dieselbe Antwort. Der Kanonisierer schützt, was er sicher als
   Literal erkennt, und fasst dessen Inhalt nie an. Trägt ein Text eine
   Quotierung, die er nicht sicher abgrenzen kann, zieht er sich zurück.
4. **Parser.** Ein Normalisierer, der Kommentare, Dollar-Quoting und
   Dialekt-Escapes kennt, wäre ein Parser. Das bleibt richtig, und deshalb **kennt** der Kanonisierer diese Konstrukte nicht. Er
   erkennt nur, **dass** eines vorliegt, und faltet dann nicht. Dafür reichen
   ein Literal-Muster, eine Klammertiefe und die Suche nach bestimmten
   Zeichenfolgen. Eine Grammatik ist dafür nicht nötig.

Eine Erfahrung aus dem Bau der ersten Fassung wiegt mit: Ein Platzhalter für
String-Literale enthielt Leerzeichen, die Leerraum-Regel zerstörte ihn, und
`note = 'a~~b'` galt als gleich zu `note = 'a like b'`. Aufgefallen ist das
nur, weil die Gegenprobe im Test stand. **Eine Faltung, die zu viel
gleichsetzt, ist gefährlicher als eine, die zu wenig gleichsetzt.** Sie
versteckt Unterschiede, statt sie zu melden.

## Entscheidungstreiber

- Die Irrtumskosten sind asymmetrisch (siehe oben): Bei `schema compare` kostet
  ein Fehler einen Fund, bei `schema migrate` eine falsch stehende Datenbank.
- Kein Parser — das Hauptargument aus ADR 0053 bleibt.
- Ein Widerspruch zwischen zwei akzeptierten ADRs fällt keinem Gate auf. Eine
  Linie braucht deshalb **einen** Besitzer.
- Der Altbestand aus 1.7.0 und 1.7.1 braucht einen Ort in der
  Entscheidungslage, nicht nur im Changelog.

## Betrachtete Optionen

### A — ADR 0053 bleibt, `schema compare` wird wieder streng

Verworfen. Diese Option nähme den Altbestand zurück und stellte die
Fehlalarm-Quote wieder her, die der Anlass war. Für zwei Reverses hat der
Compare-Pfad keine andere Entscheidungsquelle.

### B — Neuer ADR neben ADR 0053, beide `accepted`

Verworfen. Dann stünden zwei akzeptierte ADRs im Widerspruch, und sowohl
`make docs-check` als auch `make doc-immutable` blieben grün. Am eingefrorenen
Kern ist nur eine Änderung zulässig: die Statuszeile.

### C — Schreibweise-Faltung auch für `schema migrate` und den Fingerabdruck

Verworfen. Dort gelten die Einwände aus ADR 0053 ungeschmälert, und ein
Irrtum kostet am meisten. Herkunft und Server-Form beantworten die Frage ohne
Normalisierung. Fingerabdruck und `CanonicalPayload` hängen an denselben
Projektionen wie der Comparator. Eine Faltung dort änderte Plan-Artefakte und
Overlay-Operations-IDs, und zwar für eine Toleranz, die der Migrate-Pfad
nicht braucht.

### D — Schreibweise-Faltung nur in `schema compare`, voller Umfang (gewählt)

Der Altbestand wird festgeschrieben und um drei Punkte erweitert: die
redundanten Klammern um die Operanden einer `AND`-/`OR`-Komposition, den
Leerraum um Kommas im CHECK-Pfad und das Index-Prädikat.

### E — Ein SQL-Parser

Nicht Gegenstand dieses ADR. Ein Parser wäre eine neue Laufzeitabhängigkeit und
braucht eine eigene Entscheidung des Eigners, die im Planungsbaum als offener
Eintrag zur Ausdrucks-Analyse per Parser geführt wird. Diese Entscheidung kommt
ohne Parser aus. Ob später einer kommt, entscheidet sie nicht.

## Entscheidung

### Übernommen aus ADR 0053 — gilt unverändert weiter

1. **Der Post-Compare vergleicht Server-Form gegen Server-Form.** Für die vier
   Textfelder ist die Grundlinie die Form, die der Server **unmittelbar nach dem
   Anwenden** führt, nicht der Dateitext. Eine Handänderung am Server fällt
   weiterhin auf. Dass die Katalogform vom Autorentext abweicht, gilt dagegen
   nicht als Drift.
2. **Die Planung entscheidet aus der Herkunft.** Die Frage lautet: „Hat der
   **Autor** den Text seit dem letzten Anwenden geändert?" Sie vergleicht zwei
   Autorentexte und braucht den Server nicht.
3. **Die fünf in ADR 0053 entschiedenen Punkte:**
   1. Die Herkunft liegt im Migrations-Artefakt, nicht in einer Ablage auf dem
      Zielsystem.
   2. Ohne Herkunft wird konservativ geplant: Die Änderung wird ausgeführt,
      auch wenn sie vielleicht unnötig ist. Ab dem zweiten Lauf konvergiert der
      Plan.
   3. Ändert sich die Projektion der Textfelder, wird der Fingerabdruck
      angehoben.
   4. Der Sandkasten (ein eigenes Schema, in dem das Soll angewendet und
      zurückgelesen wird) ist ein Zusatz. Er wird per Konfigurationsdatei
      eingeschaltet, ist nicht voreingestellt und greift dort, wo die Herkunft
      fehlt.
   5. `CanonicalPayload` geht mit. Die Index-Identität hängt an drei
      Projektionen: Comparator, Fingerabdruck und `CanonicalPayload`. Sie
      ändern sich nur gemeinsam oder gar nicht.
4. **Die Textfelder aus dem Vergleich zu nehmen, bleibt verworfen** — in beiden
   Pfaden. Der Text **ist** die Aussage von Sicht, CHECK und Index-Prädikat.

### Neu gefasst: Entscheidung 1 — keine lokale Normalisierung, für `schema migrate` und den Fingerabdruck

Planung und Post-Compare von `schema migrate`, der Fingerabdruck und
`CanonicalPayload` normalisieren rohen SQL-Text nicht lokal: kein
Zeichen-Scanner, kein Parser, keine „konservative" Teilmenge. Unberührt bleibt
die Angleichung von Zeilenenden und Randleerraum
(`ConstraintDiffContract.comparable`), die älter ist als ADR 0053. Der
Comparator dieser Pfade wird **ohne** `canonicalizeRawExpressions` gebaut.

Für `schema compare` gilt Entscheidung 1 nicht mehr.

### Neu gefasst: Entscheidung 4 — `schema compare` setzt die Dialekt-Schreibweise gleich

**Geltungsbereich.** Gemeint ist der Vergleich, den `schema compare` in der CLI
und im MCP-Werkzeug `schema_compare` ausführt. Beide bauen den Comparator mit
`canonicalizeRawExpressions = true`.

**Felder und was dort als Schreibweise gilt.**

| Feld | Schreibweise, die gleichgesetzt wird |
| --- | --- |
| `ViewDefinition.query` | Identifier-Quoting (`"x"`, `` `x` ``, `[x]`), Leerraum, abschließende Semikola (eines oder mehrere) |
| `ConstraintDefinition.expression` (CHECK, EXCLUDE) | Leerraum, auch um Operatoren **und um Kommas**; Identifier-Quoting einfacher Bezeichner; Klammern um ein Literal oder einen Bezeichner; Klammern um den ganzen Ausdruck; **redundante Klammern um einen Operanden einer `AND`-/`OR`-Komposition**; der PostgreSQL-Operator `~~` als `LIKE`; ein Cast auf den Typ, den der Operand ohnehin hat |
| `IndexDefinition.where` | dieselbe Schreibweise wie beim CHECK-Ausdruck |
| `IndexColumn.expression` | **keine** — bleibt auch in `schema compare` wortgleich |

Neu gegenüber 1.7.1 sind drei Punkte: der Leerraum um Kommas und die
Operanden-Klammern im CHECK-Pfad sowie die ganze Zeile `IndexDefinition.where`.
Ein Komma außerhalb eines Literals trennt immer eine Liste, sei es eine
Werte- oder eine Argumentliste. Der Leerraum daneben ist deshalb überall
Schreibweise.

Der **Sichten-Rumpf bleibt bewusst enger**. Klammern und Casts werden dort nicht
gefaltet, denn in einem Abfragetext gliedern Klammern Joins und
Unterabfragen. Eine Regel, die `(a join b)` und `a join b` gleichsetzt, betrifft
nicht mehr die Schreibweise, sondern die Struktur.

Der **Berechnungsausdruck** einer Spalte (`ColumnGeneration.Computed`) gehört
nicht zu diesem ADR. Für ihn gilt eine eigene Regel: Kann der Vergleich nicht
entscheiden, ob sich der Ausdruck geändert hat, wertet er ihn als gleich und
meldet das mit `W137`. Diese Regel bleibt unberührt.

**Die Operanden-Klammern, genau.** Eine Klammer fällt nur weg, wenn beide
Bedingungen erfüllt sind:

- Sie grenzt links und rechts nur an den Ausdrucksrand, an `AND` oder an `OR`.
- Sie umschließt ein einzelnes Vergleichsprädikat, also einen
  Vergleichsoperator oder `IS [NOT] NULL`. Auf ihrer obersten Ebene steht kein
  `AND`, `OR` oder `NOT`.

Eine solche Klammer trägt keine Bedeutung, weil ein Vergleich in allen fünf
Dialekten stärker bindet als `AND` und `OR`. Stehen bleiben:

- Klammern um eine ganze Komposition, etwa `(a = 1 OR b = 2) AND c = 3`: Sie
  gliedern.
- Klammern um einen Rechenausdruck, etwa `(a + b) * c`: Sie gliedern.
- Klammern hinter einem Funktionsnamen, hinter `IN` oder hinter `NOT`: Sie
  gehören zur Syntax. Die erste Bedingung schließt sie bereits aus.

„Gliedernd" heißt in diesem Repo genau das, was stehen bleibt. Die
wegfallenden Klammern sind die **redundanten**.

### Die Grenze: Schreibweise, nie Bedeutung

Ein Unterschied bleibt alles, was eine andere Aussage sein könnte oder nur mit
einem Parser gleichzusetzen wäre:

- umgestellte Konjunktionen (`a = 1 AND b = 2` gegen `b = 2 AND a = 1`) und
  vertauschte Operanden (`a > b` gegen `b < a`);
- gliedernde Klammern (siehe oben);
- andere Literale, andere Operatoren, andere Funktionen;
- die Groß-/Kleinschreibung von **Bezeichnern** (`"Quantity"` gegen `quantity`),
  denn in PostgreSQL sind das verschiedene Spalten;
- ein Cast, der den Wert ändern kann, etwa auf einen engeren Zahlentyp — er ist
  keine Schreibweise;
- zwei Formen desselben Prädikats, insbesondere `= ANY(ARRAY[…])` gegen
  `IN (…)`. Diese Gleichsetzung ist eine Entscheidung über die Bedeutung.
  [ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md) hat sie für
  den zielbewussten Vergleich getroffen und für `schema compare` ausdrücklich
  nicht. Dabei bleibt es.

**Nicht entschieden** ist die Groß-/Kleinschreibung von **Schlüsselwörtern**
(`sum` gegen `SUM`, `is null` gegen `IS NULL`). Dieser ADR lässt ihre Faltung
weder zu, noch schließt er sie aus. Bis der Eigner entscheidet, bleibt sie ein
Unterschied.

### Wann die Faltung sich zurückzieht

Die Faltung gilt nur unter vier Bedingungen, und zwar für alle drei gefalteten
Felder:

1. **Sie betrifft nur den Vergleichswert.** Die kanonische Form entscheidet nur
   über „gleich" oder „nicht gleich". Gemeldete Funde tragen die unveränderten
   Definitionen beider Seiten, und keine kanonische Form gelangt in ein
   Artefakt oder in DDL.
2. **Literale sind geschützt.** Keine Regel verändert den Inhalt eines
   String-Literals: weder Leerraum noch Quoting noch Operator-Folgen. Das gilt
   auch für den Sichten-Rumpf.
3. **Bei Unsicherheit wird nicht gefaltet.** Trägt einer der beiden Texte
   außerhalb eines erkannten Literals ein Kommentarzeichen (`--`, `/*`) oder
   eine Quotierung, die der Kanonisierer nicht sicher abgrenzen kann (etwa
   Dollar-Quoting oder Backslash-Escapes), bleibt dieses Feld ungefaltet. Es
   gilt dann der wortgleiche Vergleich.
4. **Die Faltung ergänzt die anderen Quellen, sie ersetzt keine.** Sie kann nur
   „unverändert" ergeben. Was sie nicht gleichsetzt, entscheiden wie bisher
   Herkunft und Sandkasten; fehlen beide, gilt der Textvergleich.

Der Altbestand aus 1.7.0 und 1.7.1 wird festgeschrieben, **soweit** er diese
Bedingungen erfüllt. Wo er sie nicht erfüllt, wird er an sie angepasst, nicht
die Bedingungen an ihn.

## Bestätigung

- **Jede Regel hat ihre Gegenprobe.** Zu jeder Faltung pinnt ein Test das
  gleichgesetzte Paar **und** einen Fall, der ein Unterschied bleiben muss. Die
  Grenzfälle stehen in `ExpressionCanonicalisationTest` und
  `ViewQueryCanonicalisationTest`. Eine Regel ohne Gegenprobe gehört nicht zu
  dieser Entscheidung.
- **Die Rückzugsfälle sind gepinnt**, und zwar in jedem gefalteten Feld: ein
  Kommentar, dessen Lage sich ändert; ein Literal, das sich nur in Leerraum
  oder Quoting unterscheidet; eine Quotierung, die der Kanonisierer nicht
  abgrenzen kann.
- **Der Migrate-Pfad bleibt nachweislich streng.** Ein Test zeigt, dass
  dasselbe Paar ohne `canonicalizeRawExpressions` gemeldet wird.
  `SchemaComparatorRawTextProvenanceTest` pinnt die Linie aus Herkunft und
  Server-Form. Der Algorithmus des Fingerabdrucks springt nicht.
- **Sabotage:** Wird eine Regel zurückgenommen, muss ihr Test rot werden.

## Konsequenzen

**Dafür:**

- Zwei Reverses verschiedener Dialekte melden für dieselbe Aussage keinen Fund
  mehr, soweit die Differenz nur Schreibweise ist. Der Bericht wird dadurch
  brauchbar.
- Die Linie hat einen Besitzer, und der Altbestand ist in der
  Entscheidungslage gedeckt.
- Migrate-Pfad, Fingerabdruck, `CanonicalPayload`, Plan-Artefakte und Overlays
  bleiben unberührt. Der Algorithmus des Fingerabdrucks springt nicht.
- Kein Parser, keine neue Abhängigkeit.

**Dagegen / zu tragen:**

- `schema compare` und `schema migrate` können dasselbe Paar verschieden
  beurteilen. `schema compare` meldet nichts, während `schema migrate` ohne
  Herkunft ein `CREATE OR REPLACE` oder ein Drop+Add plant. Das ist gewollt und
  muss im Vertrag stehen. Wer `schema compare` als Vorschau auf den
  Migrationsplan liest, liest es falsch.
- Wo Kommentare oder unsicher abgrenzbare Quotierungen im Text stehen,
  lässt der Rückzug Fehlalarme stehen. Das ist der Preis dafür, dass die
  Faltung keinen Unterschied versteckt.
- Die Menge der gleichgesetzten Schreibweisen gehört zum Vertrag von
  `schema compare` und steht in [`spec/cli-spec.md`](../../spec/cli-spec.md).
  Eine neue Regel ändert die Spec mit, und die Spec nennt die offenen
  Grenzfragen als offen.
- Der asynchrone MCP-Job `schema_compare_start` baut seinen Comparator heute
  ohne die Faltung (`McpCoreJobWorkerFactory`). Ob er zum Geltungsbereich
  gehört, **entscheidet dieser ADR nicht**. Bis dahin vergleicht er
  wortgleich, und diese Abweichung von `schema_compare` ist als solche
  festzuhalten.

## Verhältnis zu benachbarten ADRs

[ADR 0026](0026-fingerprint-kanonisierung-post-compare.md),
[ADR 0048](0048-enum-wertevorrat-im-fingerprint.md) (inzwischen übersteuert von
ADR 0055), [ADR 0049](0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md),
[ADR 0050](0050-overlay-bindung-uebergang-vs-darstellung.md) und
[ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md) sagen jeweils
„`schema compare` bleibt streng". Sie meinen damit jeweils einen eigenen
Gegenstand:

- **0026** — die dialektbewusste Faltung von Typen und Äquivalenzen im
  Fingerabdruck. Ein gewolltes `smallint → integer` bleibt in `schema compare`
  ein Unterschied.
- **0048 und 0055** — der Wertevorrat eines Enums, dargestellt als Spaltentyp
  oder als CHECK, einschließlich der Formen, in denen die Dialekte diesen CHECK
  zurückgeben (`IN`-Liste, `OR`-Kette, `= ANY(ARRAY[…])`).
- **0049** — INCLUDE-Spalten und `clustered`, die ein Ziel nicht ausdrücken
  kann.
- **0050** — ein Overlay stellt Identität her und lockert keine Gleichheit.

Diese Aussagen betreffen Projektionen, die einen Unterschied **wegdefinieren**,
weil ein Ziel ihn nicht ausdrücken kann, oder zwei **Darstellungen** derselben
Aussage. Die Schreibweise-Faltung dieses ADR gehört zu keiner der beiden
Arten: Sie hängt an keinem Zieldialekt und keiner Fähigkeit und betrifft nur,
wie **derselbe** Ausdruck geschrieben ist. Die Abgrenzungen der genannten ADRs
bleiben in ihrem Gegenstand gültig. Keiner von ihnen muss übersteuert werden.

**Ein Wort, zwei Bedeutungen.** ADR 0048 nennt `spalte IN ('a','b')` und
`spalte='b' OR spalte='a'` „zwei Schreibweisen", und ADR 0055 übernimmt das.
In **diesem** ADR ist „Schreibweise" enger gefasst: Gemeint ist nur, wie
derselbe Ausdruck geschrieben ist — Quoting, Leerraum, redundante Klammern,
Casts ohne Wertänderung. Eine `IN`-Liste gegen eine umsortierte `OR`-Kette
oder gegen `= ANY(ARRAY[…])` ist nach diesem ADR **keine** Schreibweise,
sondern eine andere Form desselben Prädikats. `schema compare` setzt sie nicht
gleich (siehe „Die Grenze").

Für rohen SQL-Text hatte ADR 0053 diese Strenge ausdrücklich übernommen: Seine
Entscheidung 4 berief sich auf dieselbe Grenze, die der zielbewusste Vergleich
schon für Fähigkeitsunterschiede zog. Diese Übernahme hebt der vorliegende ADR
auf. Wer „`schema compare` bleibt
streng" ohne Gegenstand liest, liest es seitdem so: keine Faltung nach
Fähigkeit oder Darstellung — wohl aber eine Faltung der Schreibweise
roher SQL-Texte nach den Regeln oben.

## Weitere Informationen

- Übersteuert [ADR 0053](0053-vergleich-rohen-sql-texts.md). Deren Herleitung —
  die Messungen gegen PostgreSQL 16 und Oracle 23 sowie die Abwägung von
  Wegwerf-Objekten und Sandkasten — bleibt dort nachlesbar und gilt, soweit
  dieser ADR sie übernimmt.
- Der normative Vertrag der Faltung steht in
  [`spec/cli-spec.md`](../../spec/cli-spec.md), Absatz „Dialekt-Schreibweise
  roher Ausdruecke".
