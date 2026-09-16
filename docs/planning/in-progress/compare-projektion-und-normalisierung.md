# Compare: Restfehlalarme und Projektionslücken aus der Konsumentenmessung

> **Status:** In Arbeit seit 2026-09-16 (aktiviert nach zwei Review-Runden).
> **Stand der Pakete:** geliefert P8 (nachgetragen, Altbestand; `50ee1bd00`)
> und P5; offen: P3, P6, P2a, P2b, P1, Spec-Teil von P7. Der ADR-Teil von P7 ist mit ADR 0056
> geliefert (`c9737f909`).
> Gemeldet gegen `1.7.1`. **Belegart je Posten:** nachgemessen sind 1, 2, 3, 5, 6
> **und** 4 — bei 4 hat die Nachmessung nur eine andere *Art* ergeben als die
> Meldung nahelegte (Reader statt Kanonisierung), nicht eine andere Tatsache.
> **Vorbedingung / Gate:** Die **zwei** verbliebenen Grenzfragen
> (Schlüsselwort-Case, `RESTRICT` gegen implizit) gehören dem Eigner und werden
> **hier nicht** entschieden; `RESTRICT` ist in
> [`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md)
> verankert, der Schlüsselwort-Case hat noch keinen Ort (s. „Offen"). Die dritte
> Frage der ersten Fassung — `= ANY(ARRAY[…])` gegen `IN (…)` — ist **keine
> offene Frage**: sie ist in
> [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
> entschieden, samt Grenze („`schema compare` bleibt streng").
> **Und P3/P5 bewegen eine Linie, die ein akzeptierter ADR besitzt:**
> [`ADR 0053`](../../adr/0053-vergleich-rohen-sql-texts.md) schliesst lokale
> Normalisierung rohen SQL-Texts aus und nennt `IndexDefinition.where` namentlich.
> Der Linienwechsel ist Teil dieses Slices (P7) und läuft über eine
> **Statusänderung** auf `superseded by`, nicht über einen zweiten ADR daneben —
> sonst stünden zwei akzeptierte ADRs im Widerspruch und **kein Gate merkte es**.
> **Eigner-Entscheidung zur Linie (2026-09-16): voller Umfang.** `ADR 0053` wird
> von einem neuen ADR übersteuert, der den Stand von 1.7.1 festschreibt (die
> Dialekt-Schreibweise von CHECK- und Sichten-Text faltet nur `schema compare`)
> und ihn um P3 und P5 erweitert; `schema migrate` und der Fingerabdruck bleiben
> streng. Der neue ADR übernimmt die weiter geltenden Punkte aus 0053
> (Server-Form gegen Server-Form, Herkunft, `CanonicalPayload`) und fasst nur
> Entscheidung 1 und 4 neu. Die Vorbedingung ist damit erfüllt; die zwei
> Grenzfragen oben bleiben offen.
> **Der Widerspruch steht schon heute im Repo:** 1.7.1 faltet in `schema compare`
> bereits CHECK- und Sichten-Text (`canonicalizeRawExpressions = true`,
> `SchemaCompareWiring.kt:55`, `McpRuntimeRegistries.kt:125`), und
> `spec/cli-spec.md` beschreibt das — während `ADR 0053` weiter „`schema compare`
> bleibt streng" sagt. P7 schliesst also nicht nur die Erweiterung ab, sondern
> auch diesen Altbestand.
> **Aktiviert** am 2026-09-16 (Move aus `../next/`).

## Befund (gemeldet gegen 1.7.1, im Code nachgemessen)

Der Konsument vergleicht weiterhin PG-Reverse gegen Reverses der anderen
Dialekte und benennt sechs Restposten. **Fünf davon sind im Repo nachgemessen**
(1, 2, 3, 5, 6) — am Code oder an einer präparierten Datei; der sechste (4) hat
sich bei der Nachmessung als **etwas anderes** erwiesen, als die Meldung
nahelegte (s. dort).

| # | Posten | Art |
| - | ------ | --- |
| 1 | Constraint-Funde ohne `details` | Projektion |
| 2 | W137 nutzt ein anderes Pfad-Vokabular | Projektion |
| 3 | `OR` + `IS NULL` wird nicht normalisiert | Kanonisierung |
| 4 | MySQL-Charset-Introducer `_utf8mb4'…'` | **Reader** (macht das Schema ungültig) |
| 5 | Index-Prädikat `ANY(ARRAY[…])` vs `IN (…)` | Kanonisierung (a) / Eigner (b) |
| 6 | Identity-`sequenceName` (PG-seitig) | **Vergleich** — Naht existiert, ist nur nicht verdrahtet |

### 1 — Constraint-Funde tragen kein Vorher/Nachher (verifiziert)

`SchemaCompareHandler.kt:475` schickt Constraints durch das blanke
`changed("TABLE_CONSTRAINT_CHANGED", …)`, das **keine `details`** setzt —
während Spalten- und Typ-Funde welche liefern. Wer die verbleibenden
CHECK-Fehlalarme beurteilen will, muss die Artefakte von Hand gegenlesen.

**Dieselbe Lücke wurde in dieser Serie für den `VIEW_CHANGED`-Fund geschlossen**
(1.7.1, `viewChanged`) — aber nur zur **Hälfte**: `details` entstehen dort nur im
Spalten-Fall und nur, wenn beide Seiten nicht-blank sind
(`SchemaCompareHandler.kt:375-387`); der Query-Fall fällt in den detail-losen
Zweig (`:390-396`), und die CLI rendert für ihn ohnehin `query: changed` statt
eines Vorher/Nachher (`CompareRendererPlain.kt:94`). Der Präzedenzfall ist damit
eng — und es ist genau der Fall (View-Rumpf), den Abschnitt 3 als Beispiel
benutzt.

### 2 — Zwei Pfad-Vokabulare im selben Dokument (verifiziert)

`ComputedExpressionDecidability.kt:52` baut `path = "$tableName.$columnName"`,
also `order_item.line_total`. Alle übrigen Funde folgen
`tables.<tabelle>.columns.<spalte>…`. Für maschinelle Auswertung ist das ein
Bruch — und keiner der beiden Wege ist am anderen verankert.

### 3 — Zusammengesetzte Ausdrücke bleiben Unterschied (gemessen)

`ck_order_ship_after_place` feuert in **allen drei** Vergleichen. Es ist das
einzige CHECK mit `OR` und `IS NULL`:

```
((shipped_at IS NULL) OR (shipped_at >= placed_at))      PG
shipped_at IS NULL OR shipped_at>=placed_at              MSSQL
((`shipped_at` is null) or (`shipped_at` >= `placed_at`)) MySQL
```

Die einfachen Binärvergleiche werden normalisiert, der zusammengesetzte nicht.
**Strukturell plausibel:** `ConstraintDiffContract.canonicalForm` faltet
Whitespace, den äußeren Klammerring und Klammern um ein Literal — die
**gruppierenden** Klammern um die beiden Operanden bleiben stehen.

**Und die drei Beine sind nicht dasselbe.** Die Klammern sind nur bei
**PG↔MSSQL** der ganze Unterschied. Die MySQL-Form unterscheidet sich
**zusätzlich in der Schlüsselwort-Schreibweise** (`is null`/`or` gegen
`IS NULL`/`OR`) — und genau die nimmt die Abgrenzung unten ausdrücklich beim
Eigner aus. Eine Klammer-Faltung allein schließt PG↔MySQL und MSSQL↔MySQL
also **nicht**; sie schließt ein Bein von dreien.

Das ist dieselbe Klasse wie beim View-Rumpf (`sum` gegen `SUM`): die
Schlüsselwort-Schreibweise ist **durchgängig** nicht gefaltet, nicht nur dort.

### 4 — MySQLs Charset-Introducer macht das Schema ungültig (gemessen) — **Posten wandert in den Reader-Slice**

Der Konsument meldete `ck_customer_email_shape` als Fehlalarm „nur wo MySQL
beteiligt ist"; Ursache sei `_utf8mb4'%@%'`. **Nachgemessen ist es mehr als ein
Fehlalarm.** Eine Datei mit genau diesem Ausdruck ist nicht vergleichbar,
sondern **ungültig**:

```
$ d-migrate schema validate --source cs_my.yaml
  ✗ Error [E012]: Check expression 'ck_mail' references unknown column '_utf8mb4'
    → tables.orders.constraints.ck_mail
```

Der Introducer steht vor dem Literal; die Ausdrucks-Analyse liest `_utf8mb4`
als **Spaltenbezug**. Das ist damit kein Kanonisierungs-, sondern ein
**Reader**-Thema: was MySQL in `CHECK_CLAUSE` liefert, ist nicht das neutrale
Modell, sondern ein Server-Text mit Dialekt-Anhang.

**Der Konsument hat den zweiten Teil selbst gesehen:** die backslash-escapten
Anführungszeichen darin (`\'%@%\'`) sehen nach einem Serialisierungsartefakt
aus. Beides zusammen deutet für MySQL auf **eine** Stelle im Reader — die
Aussage „eine Stelle" gilt aber nur je Dialekt: der MSSQL-Reader hat eine
zweite solche Stelle (`MssqlMetadataQueries.kt:344`).

**Und die Entscheidung ist zu neunzig Prozent vorgezeichnet.** Der MSSQL-Reader
hat dieselbe Frage für den Unicode-Literal-Präfix `N'…'` schon entschieden und
begründet — **im Reader streichen**, weil der Validator das `N` sonst als
Spaltenbezug liest und jedes reverse-gelesene MSSQL-Schema mit `E012` abweist
(`MssqlTypeMapping.kt:312-314`, gepinnt in `MssqlTypeMappingTest.kt:203-222`).
`_utf8mb4'…'` ist dieselbe Klasse Konstrukt; die zweite Fassung („die Analyse
kennt den Introducer") hat damit einen Vorläufer, der sich dagegen entschieden
hat.

**Der Posten ist am 2026-09-16 in den Reader-Slice gewandert** — er ist der
einzige Reader-Posten dieser Messung, und der Reader-Slice führt für genau diese
Klasse einen eigenen Strang („Modell-Reinheit: der Reader nimmt ZUVIEL auf").
Dort steht er als Posten C1 mit Paket P6; dieser Slice führt ihn nur noch als
Befund und **behält die Postennummer**, damit die Querverweise in beiden Slices
stabil bleiben.

### 5 — Index-Prädikat ohne jede Faltung (verifiziert) — **und mehr als das**

Zwei Dinge, die nicht dasselbe sind.

**(a) Der Zweig fehlt.** `RawTextFolding.index` beginnt mit

```kotlin
if (authorship == null && serverForm == null) return desired
```

— und `schema compare` setzt **nur** `canonicalizeRawExpressions`, nicht
`authorship`/`serverForm`. Das Prädikat eines Index bekommt damit **gar keine**
Kanonisierung, obwohl `constraint` und `viewQuery` sie haben. Schon eine reine
**Schreibweise**-Differenz (Quoting, Whitespace, Cast) wird dort als Änderung
gemeldet.

**Nicht das „einzige" Feld ohne Faltung.** `columnGeneration`
(`RawTextFolding.kt:93`) hat den Zweig ebenfalls nicht — es faltet stattdessen
ueber die **Unentscheidbarkeits-Regel** auf Gleichheit und meldet die offene
Frage als `W137`. Der Index-Pfad hat **kein** solches Gegenstueck, und das ist
der Unterschied. „Einziges" waere die Einladung, dort eine zweite Regel
nachzuruesten, die es schon gibt.

**(b) Der gemeldete Fall ist keine Schreibweise — und die Messung des ersten
Entwurfs war falsch.** Nachgemessen an präparierten Dateien; die erste Zeile ist
gegenüber dem ersten Entwurf **korrigiert**:

```
# BEIDE Zeilen als CHECK-Constraint gemessen — auf dem CHECK-Pfad, wo die
# Faltung aktiv ist. Ueber den INDEX-Pfad waeren sie beide DIFFERENT, weil
# dort (a) gar nicht faltet; das ist genau der Befund von (a).
status = ANY (ARRAY['NEW'::text, 'PAID'::text])  <->  status = ANY (ARRAY['NEW','PAID'])
  → DIFFERENT      (der Cast faellt, die Komma-Luecke bleibt)

status = ANY (ARRAY['NEW','PAID'])                <->  status IN ('NEW','PAID')
  → DIFFERENT      (der Rest ist eine Umschreibung — auf JEDEM Pfad)
```

**Die erste Zeile stand im ersten Entwurf als `IDENTICAL`** („der Cast faellt —
Schreibweise, auf dem CHECK-Pfad"). Das ist widerlegt: `canonicalForm`
(`ConstraintDiffContract.kt:81`) faltet Whitespace nur um `[=<>!]+ | * | + | -`,
**nicht** um `,` — zwischen den beiden Literalen bleibt links `', '` und rechts
`','`. Der View-Kanonisierer daneben faltet Kommas sehr wohl
(`RawTextFolding.kt:168`: `\s*([,=()])\s*`). **Zwei Kanonisierer, zwei
Regelwerke** — die Faltung hat damit **zwei** fehlende Zweige, nicht einen: den
ganzen Index-Pfad (a) und die Listen-Kommas im CHECK-Pfad. Beide gehören zu P5;
ohne den zweiten bleibt dessen DoD („reine Schreibweise-Differenz meldet nichts
mehr") unerfuellt.

`= ANY(ARRAY[…])` und `IN (…)` sind **nicht** dieselbe Schreibweise, sondern zwei
Formen desselben Prädikats. Sie gleichzusetzen ist eine
**Bedeutungs**-Entscheidung — und sie ist **nicht offen**: [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
hat sie für den **zielbewussten** Vergleich entschieden und festgehalten, dass
„`schema compare` streng bleibt"; `EnumCheckProjection.kt:22-31` liest genau
diese Form und schreibt sie in **eine** Schreibweise (`canonicalText`, `:132-135`).
Der gemeldete `ix_order_open`-Fall wird von (a) allein also nicht geschlossen —
und ihn zu schliessen hiesse, die Grenze aus ADR 0055 zu verschieben. Das ist
damit **keine Eigner-Frage**, sondern eine ADR-Frage: sie gehört zur Linie, die
P7 nachzieht (s. „Offen").

**Folge für den Zuschnitt:** (a) ist ein Paket — P5, mit **beiden** fehlenden
Zweigen; (b) ist **nicht** Teil dieses Slices.

### 6 — Der Identity-`sequenceName` ist PG-Buchhaltung (verifiziert, Zuordnung korrigiert)

Der Konsument meldete `Identity(mode=BY_DEFAULT, sequenceName=public.customer_id_seq, …)`
gegen `sequenceName=null` als Fehlalarm bei PG↔MySQL. **Die Zuordnung „MySQL" war
falsch:** nachgesehen stammt der Name aus dem **PostgreSQL**-Reverse —

```kotlin
PostgresTypeMapping.kt:95   sequenceName = input.generatedSequenceName
MysqlTypeMapping.kt:42      ColumnGeneration.Identity(legacySerialSyntax = true)   // kein name
```

MySQL setzt bei `AUTO_INCREMENT` **nie** einen Sequenznamen. Der Posten ist damit
weder ein MySQL- noch ein Reader-Thema: der Vergleich wertet ein Feld, das
beschreibt, **wie** der Server die Erzeugung organisiert — nicht, **was** die
Spalte ist. Dieselbe Klasse wie `sourceDialect` (AP2) und `engine` (AP3) im
Compare-Slice.

**Und „nur eine Seite führt es" gilt nur fuer die PG↔MySQL-Paarung.** Oracles
Reverse setzt den Namen ebenfalls (`OracleTypeMapping.kt:83`,
`sequenceName = input.identitySequenceName`), und `OracleCapabilities` ist wie
`PostgresCapabilities` `false` (`:63`). PG↔Oracle vergleicht also **zwei** Namen,
und eine Naht, die `namesIdentitySequences` auswertet, wuerde dort beide Seiten
ausblenden. P6 muss deshalb sagen, **welcher Dialekt** der symmetrischen Naht
übergeben wird — zwei Reverses haben keine Zielseite.

**Die Naht existiert bereits und ist dialektabhängig:**

```kotlin
DialectCapabilities.namesIdentitySequences    // PostgresCapabilities:57 · OracleCapabilities:63 = false
capabilityGenerationCanonicalizer             // TypeCanonicalizerWiring.kt:256-275
```

Sie blendet den Namen aus — aber **nur auf dem `migrate`-Pfad**
(`SchemaMigrateRunner.kt:601-611`). Die Compare-Pfade übergeben keine Projektion.

**Achtung, Vertrag:** `sequenceName` steht auch in `CanonicalPayload.kt:238-243` und
`FingerprintValueProjection.kt:88-91` — er gehört zu den drei Projektionen
(`TargetProjection.kt:24-35`). Ihn unbedingt im Comparator auszublenden liefe dieser
Bindung zuwider; das Paket muss die vorhandene Fähigkeits-Naht benutzen, nicht eine
zweite Regel daneben stellen.

### Eine Verteilungs-Beobachtung, die den Zuschnitt bestimmt

Das **PG-Artefakt ist byte-identisch** zum 1.5.1-Lauf (sha256 `1ea858fb…`), die
MSSQL- und MySQL-Artefakte haben sich geändert. Der PG-Reader ist also
unberührt; die Verbesserungen sitzen im Comparator und in den MSSQL-/MySQL-
Readern. **Folge für diesen Slice:** keine PG-Reader-Arbeit.

Der einzige **Reader**-Posten war **4** (MySQL) — er ist am 2026-09-16 in den
Reader-Slice gewandert, dieser Slice führt keinen mehr. Posten 6 sitzt ebenfalls
**nicht** dort — er ist ein Vergleichs-Thema (die Naht ist nur nicht verdrahtet),
und verdrahtet wird er in den beiden Comparator-Baustellen der Driving-Adapter
(s. P6). Alles uebrige sitzt im Comparator oder in der
Projektion.

## Ziel

Drei **verschiedene** Ausgänge, je nach Posten — sie in einen Satz zu zwingen
wäre die erste Ungenauigkeit:

- **Fehlalarme hören auf** — bei 3 in **einem Bein von dreien**, bei 6 ganz,
  bei 5 für die Schreibweise. Dieselbe Sache in zwei Schreibweisen ist keine
  Änderung; was keine Schreibweise ist, bleibt ein Fund:
  - **3**: PG↔MSSQL schliesst die Klammer-Faltung. Die zwei MySQL-Beine
    brauchen zusaetzlich die Schlüsselwort-Case-Entscheidung (Abgrenzung).
  - **5**: die Schreibweise-Differenzen schliessen **zwei** fehlende Zweige —
    den Index-Pfad und die Listen-Kommas (s. Abschnitt 5). Die
    **Umschreibung** (`= ANY(ARRAY[…])` gegen `IN (…)`) bleibt: sie ist
    ADR-entschieden (0055, „`schema compare` bleibt streng") und nicht Teil
    dieses Slices.
  - **6**: schliesst ganz, und nur auf **einer** Seite der Paarung (PG↔MySQL).
- **Funde sagen, was sich geändert hat** (1) und folgen **einem** Pfad-Schema
  (2) — sie werden nicht weniger, sie werden brauchbar.
- **Eine Linie wird bewegt, und der Vertrag zieht nach** (3, 5): P3 und P5
  normalisieren rohen SQL-Text, den [`ADR 0053`](../../adr/0053-vergleich-rohen-sql-texts.md)
  bislang ausdrücklich ausnimmt. Das ist **kein** Nebeneffekt — der Linienwechsel
  steht in P7 mit Statusänderung, README-Zeile und Spec-Nachzug.

**Posten 4 ist am 2026-09-16 aus diesem Slice heraus:** er ist ein Reader-Thema
und steht als Posten C1 mit Paket P6 im
[Reader-Slice](../next/reader-treue-spatial-array-json.md) — dieselbe Messung, dieselbe
Woche. Die Postennummer hier bleibt frei, damit die Querverweise in beiden
Dokumenten stabil bleiben.

## Abgrenzung (nicht in diesem Slice)

- **Schlüsselwort-Case** (`sum` gegen `SUM`). Der Konsument weist zu Recht
  darauf hin, dass die dokumentierte Ausnahme **MySQL-spezifisch** formuliert
  ist („Kleinschreibung, Schemaqualifikation, gliedernde Klammern" — so steht es
  in der Erwartungsmatrix des Konsumenten; im Repo gibt es diese Datei nicht);
  der PG↔MSSQL-Fall fällt nicht darunter, und dort differiert nur
  `sum`/`SUM` plus Whitespace. Das Falten von **Schlüsselwörtern** wäre eng —
  das Falten von **Bezeichnern** wäre falsch (`"MyCol"` ≠ `mycol` in
  PostgreSQL). Die Grenze zu ziehen ist eine Eigner-Entscheidung; **sie hat
  noch keinen Ort** (s. „Offen"). Und „nirgends normativ gefasst" stimmt nur
  halb: `spec/ddl-generation-rules.md:2067` schreibt „Schlüsselwörter:
  **UPPERCASE**" fest (Generate-Pfad), und `spec/cli-spec.md:673` sagt,
  kanonisiert werde „**ausschliesslich** die **Schreibweise**, nicht die
  Bedeutung" — mit der Gross-/Kleinschreibung von **Bezeichnern** in der Liste
  (`:678-679`). Die Grenze liegt also normativ **vor**, nur nicht für den
  Vergleich roher SQL-Texte.
- **`= ANY(ARRAY[…])` gegen `IN (…)`** (Posten 5, Teil b) — **keine offene
  Frage.** [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
  hat sie für den **zielbewussten** Vergleich entschieden und `schema compare`
  ausdrücklich streng gelassen. Sie gehört damit zur ADR-Linie, die P7 nachzieht;
  sie zu verschieben wäre eine Statusänderung an 0055 (s. „Offen").
- **`RESTRICT` gegen implizit** — dieselbe offene Eigner-Frage, dort begründet.
- **Die MySQL-View-Formatierung.** Bleibt laut Harness-Erwartung bewusst ein
  Fund; der Punkt des Konsumenten (PG↔MSSQL, nur `sum`+Whitespace) ist oben
  unter „Schlüsselwort-Case" erfasst.

## Arbeitspakete

### P1 — Die Funde nennen ihr Vorher/Nachher — in **beiden** Oberflaechen

**MCP.** `SchemaCompareHandler.kt:475` schickt Constraints durch `changed(...)`
ohne `details`; die Spalten-/Typ-Funde uebergeben `beforeAfter(it.before, it.after)`
**positional** (dieselbe Datei, ab `:492`). Fix: `constraintsChanged` bekommt
dieselbe Behandlung.

**CLI — und hier liegt der eigentliche Fund.** Der Plan nahm an, die CLI trage
ein Vorher/Nachher. Sie *rendert* eines, es ist nur **leer**:

```kotlin
// SchemaCompareHelpers.kt:69-76
fun constraintSignature(c: ConstraintDefinition): String =
    "${c.name} (${c.type.name.lowercase()}" + cols + refs + ")"     // KEIN expression
```

Ein geaenderter CHECK ergibt damit beidseitig `ck_x (check)` und wird als
`~ constraint ck_x (check) -> ck_x (check)` gerendert. Derselbe Mangel in
`indexSignature` (`:62-67`, **ohne `where`**) — das ist der Fund, der nach P5
bewusst stehen bleibt, und er bliebe ohne diese Aenderung **stumm**.

**Der Mangel ist groesser als „der Constraint-Zweig".** Ohne `details` bauen
auch `TABLE_INDEX_CHANGED` (`:452-456`), `TABLE_CONSTRAINT_ADDED` (`:462`) und
die `SEQUENCE_`/`CUSTOM_TYPE_`/`FUNCTION_`/`PROCEDURE_`/`TRIGGER_CHANGED`-Funde
(`:264-276`). Ein Paket, das nur den CHECK nachzieht, laesst sie stehen — das ist
in Ordnung, aber es soll es **sagen**.

**Eine Falle im vorhandenen Helfer.** `beforeAfter` laesst leere Werte weg
(`takeIf { it.isNotBlank() }`, `:325-326`), und `finding` gibt eine **leere**
Detail-Map gar nicht aus (`:298`). Ist eine Seite blank, entstehen wieder
**detail-lose** Funde — genau der Zustand, den das Paket behebt.

**DoD:** Ein geaenderter CHECK nennt in **CLI und MCP** beide Ausdruecke; ein
Test pinnt den CHECK-Fall, den Fall **blanker** Seite und den Index-Fund. Der
Index-Fall braucht eine **echte** Praedikats-Aenderung: nach P5 bleibt eine
Schreibweise-Differenz kein Fund mehr, und ein Fixture mit einer solchen pinnte
nach dem anderen Paket nichts.

### P2 — Ein Pfad-Schema fuer alle Funde

**Der „Pfad" ist nur bei W137 kein Feld.** `DiffDiagnostic` traegt keinen
(`DiffDiagnostic.kt:12-18`), und fuer den W137-Fund zieht der MCP-Handler ihn per
**Regex aus dem Meldungstext** (`SchemaCompareHandler.kt:303-312`). Fuer **jeden
anderen** Fund ist `path` dagegen ein **Pflichtfeld des Wire-Vertrags**
(`McpToolSchemas.kt:763`/`:771` stehen in `required`, gesetzt in
`SchemaCompareHandler.kt:296`); das Vokabular, das P2 vereinheitlichen will, sitzt
in den **Werten** dieses Feldes (`:256-276`, `:421-575`). Das CLI-Dokument fuehrt
nur `code`/`severity`/`message` (`SchemaCompareProjection.kt:35`,
`spec/cli-spec.md:657`) — dort ist es wirklich Text. Ein Paket, das nur die Regex
anfasst, erreicht also den W137-Fund und sonst nichts.

**Und „die uebrigen Funde" sind mehrere Domaenen.** Neben `tables.…` (mit
`…columns.…`, `…indices.…`, `…constraints.…`, `…metadata`) stehen `views.`,
`sequences.`, `custom_types.`, `functions.`, `procedures.`, `triggers.`
(`SchemaCompareHandler.kt:256-276`, `:421-482`) — und `name`/`version` (`:240`,
`:249`) tragen gar kein Domaenen-Praefix. Ein Schema heisst: alle folgen
demselben Aufbau; die Richtung ist Teil des Pakets. Das ist ein Rename ueber
viele Aufrufstellen — Werkzeug dafuer ist `make ast-grep`, nicht `sed`.

**Und das neue Vokabular braucht einen normativen Ort.** Es ist maschinenlesbar
gedacht („Für maschinelle Auswertung ist das ein Bruch") und steht in keiner
Spec; P7 zieht heute nur die Faltungsmenge nach. Ein Pfad-Schema, das ein
Abnehmer auswertet, gehört in [`spec/cli-spec.md`](../../../spec/cli-spec.md)
bzw. [`spec/mcp-server.md`](../../../spec/mcp-server.md).

**P2a — der eine divergente Ort.** `ComputedExpressionDecidability.kt:52` zieht
`order_item.line_total` auf `tables.…`; die Regex im MCP-Handler wird mitgepinnt
(`SchemaCompareHandler.kt:310`).
**DoD:** Der W137-Fund traegt einen Pfad, der dem Schema der uebrigen Funde
folgt, und die MCP-Regex liest ihn.

**P2b — die uebrigen Domaenen.** `views.`/`sequences.`/`custom_types.`/
`functions.`/`procedures.`/`triggers.` und `name`/`version` auf denselben Aufbau;
das Schema bekommt seinen **normativen Ort** (Spec), und das Rename laeuft ueber
`make ast-grep` — es geht ueber viele Aufrufstellen, `sed` ist dort das falsche
Werkzeug.
**DoD:** Ein Test sammelt die Praefixe **aller** Fund-Arten eines nicht-trivialen
Vergleichs und verlangt **ein** Schema; das Schema steht in der Spec.

Je Paket Sabotage — die Trennung haelt einen Teilstand entscheidbar.

### P3 — Zusammengesetzte Ausdrücke kanonisieren

`OR`/`AND`-Komposition und die **redundanten** Klammern um einen Operanden
(Terminologie: s. u. — „gruppierend" wäre das Gegenteil). **Die enge Fassung
gilt weiter:** nur Klammern, die *keine* Bedeutung tragen, und nur Wortstellung,
die nichts umstellt. Der Wächter aus 1.7.1 bleibt: eine Kanonisierung, die zu
viel gleichsetzt, versteckt echte Unterschiede.

**DoD:** Der **PG↔MSSQL**-Leg des gemeldeten Vergleichs meldet
`ck_order_ship_after_place` nicht mehr. Die zwei MySQL-Beine bleiben es
**zunächst** — sie brauchen die Schlüsselwort-Case-Entscheidung (Abgrenzung).
Die Grenzfall-Tests aus `ExpressionCanonicalisationTest` bleiben grün; der
Faltungs-Nachzug in P7 ist erledigt.

**Terminologie, an der ein Nachbau scheitert:** die wegfallenden Klammern sind
die **redundanten um einen Operanden**. „Gliedernd" heisst im Repo das
**Gegenteil** — der Wächter-Test schützt genau die gliedernden Klammern („hier
gliedern die Klammern den Ausdruck, sie sind nicht redundant",
`ExpressionCanonicalisationTest.kt:74-78`). Wer sie „gruppierend" nennt, baut
die Regel nach, die `(a + b) * c` verfälscht.

**Warum sie redundant sind — die Begründung gehört zum Paket,** weil der
Schlusssatz des Plans sie als „unstrittig" führt: jeder Operand einer
`OR`/`AND`-Komposition ist selbst ein Vergleich, und der Vergleich bindet
stärker als die Konjunktion; die Klammern gliedern dort nichts. Um eine **ganze**
Komposition oder um ein Produkt gliedern sie sehr wohl — die bleiben.

**Und das Paket bewegt eine ADR-Linie.** [`ADR 0053`](../../adr/0053-vergleich-rohen-sql-texts.md)
führt `ConstraintDefinition.expression` unter den vier Stellen rohen SQL-Texts,
die nicht lokal normalisiert werden. P3 erweitert die Faltung genau dort; der
Nachzug in P7 ist Teil des Paketabschlusses, keine Zutat.

### P4 — entfällt hier: der Posten steht im Reader-Slice

Der MySQL-Introducer ist ein **Reader**-Thema (Abschnitt 4) und am 2026-09-16
mit seinem Paket in den [Reader-Slice](../next/reader-treue-spatial-array-json.md)
gewandert — dort als Posten C1 mit Paket P6, samt DoD und Modulzeile. Die Nummer
bleibt hier frei, damit die Querverweise stabil bleiben.

Der Grund für den Umzug: der Reader-Slice führt diese Klasse als eigenen Strang
(„Modell-Reinheit: der Reader nimmt ZUVIEL auf"), die Entscheidung ist durch den
MSSQL-Präzedenzfall vorgezeichnet, und hier wäre das Paket ein Fremdkörper
zwischen lauter Comparator-Paketen — es ist das einzige, das `make integration`
braucht.

### P5 — Zwei fehlende Zweige in der Faltung

**Zweig 1 — der Index-Pfad.** `RawTextFolding.index` bekommt denselben
`canonicalizeRawExpressions`-Zweig wie `constraint` und `viewQuery`. Kein
fehlender Sonderfall, sondern ein **fehlender Zweig**.

**Zweig 2 — die Listen-Kommas im CHECK-Pfad.** `canonicalForm`
(`ConstraintDiffContract.kt:81`) faltet Whitespace um `[=<>!]+ | * | + | -`,
**nicht** um `,`; der View-Kanonisierer daneben faltet Kommas sehr wohl
(`RawTextFolding.kt:168`: `\s*([,=()])\s*`). Zwei Kanonisierer, zwei Regelwerke
— ohne diesen Zweig bleibt eine reine Listen-Whitespace-Differenz
(`IN ('a','b')` gegen `IN ('a', 'b')`) ein Fund, und das DoD unten waere nicht
erfuellt. Das ist die Messung aus Abschnitt 5, Teil b.

**Nicht der naive Fix.** Den Guard in `index()` zu verschieben, statt den
`canonicalizeRawExpressions`-Zweig wie in `constraint()` voranzustellen, kippt
den **Migrate**-Pfad: beide KDoc-Stellen halten fest, dass `schema compare` die
Faltung setzt und `schema migrate` **nicht** (`RawTextFolding.kt:31-34`,
`ConstraintDiffContract.kt:32-41`). Migrate und Fingerabdruck bleiben
unveraendert — ein Test pinnt das.

**Der gemeldete `ix_order_open`-Fall wird von P5 NICHT geschlossen** (Posten 5,
Teil b): dort steht eine Umschreibung, und die Gleichsetzung ist in
ADR 0055 entschieden.

**Und P5 bewegt dieselbe ADR-Linie wie P3** — `IndexDefinition.where` ist in
[`ADR 0053`](../../adr/0053-vergleich-rohen-sql-texts.md) namentlich als eine
der Stellen genannt, die nicht lokal normalisiert werden. Der Nachzug steht in
P7.

**Gebaut:** der Zweig steht in `RawTextFolding.index` **vor** dem Guard und
faltet nur `where` — die Schlüssel-Ausdrücke bleiben wortgleich (ADR 0056,
Tabelle der Felder). Das Komma steht in derselben Operator-Regel wie `=`/`+`.
Der Rückzug aus P8 gilt am Index-Prädikat mit (gepinnt).
**Grenze, bewusst nicht gebaut:** ein **unbenannter** Index wird über einen
Schlüssel zugeordnet, der das rohe Prädikat enthält
(`TableIndexComparator.indexKey`). Zwei unbenannte Indizes, deren Prädikat sich
nur in der Schreibweise unterscheidet, erscheinen deshalb weiter als entfernt +
hinzugefügt. Reverses benennen jeden Index; betroffen sind nur zwei
handgeschriebene Dateien. Konservativ (ein Fund zu viel), s. „Offen".

**DoD:** Ein Index-Prädikat mit einer reinen Schreibweise-Differenz (Quoting,
Whitespace, **Listen-Komma**, Cast) meldet nichts mehr; ein Prädikat mit einer
**echten** Änderung bleibt ein Fund; `= ANY(ARRAY[…])` gegen `IN (…)` bleibt es
(ADR 0055); der Migrate-Pfad und der Fingerabdruck sind nachweislich unveraendert
— ein Test pinnt das.

### P6 — Die Identitäts-Naht in den Compare-Pfad ziehen

Nicht der Reader wird geändert, sondern die **Verdrahtung** — aber **nicht die
ganze Naht**. `capabilityGenerationCanonicalizer(dialect, serverVersion)`
(`TypeCanonicalizerWiring.kt:256`) faltet **zwei** Dinge:

```kotlin
dropsSequenceName = !capabilities.namesIdentitySequences      // gehoert hierher
foldsStored       = !capabilities.supportsVirtualComputedColumns   // NICHT
```

Sie ist ausserdem **ziel- und versionsparametrisiert** (`DialectCapabilities.forTarget`),
und ein symmetrischer Vergleich hat keine Zielseite: `SchemaDefinition` traegt
keinen Dialekt. In den Compare-Pfad gehoert deshalb nur der **Namens**-Teil;
`stored` bleibt sichtbar — laut `TargetProjection.kt:20` heisst `null` dort
ausdruecklich **strikter** Vergleich.

**Und verdrahtet wird in den Adaptern, nicht im Hexagon.** Der Helfer
`capabilityGenerationCanonicalizer` (`TypeCanonicalizerWiring.kt:256-275`) liegt
in `:hexagon:application` und ist heute **nur** auf dem Migrate-Pfad verdrahtet
(`SchemaMigrateRunner.kt:601-611`). Übergeben muss ihn eine der beiden
Comparator-Baustellen:
[`SchemaCompareWiring.kt:55`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareWiring.kt)
(CLI) und `McpRuntimeRegistries.kt:125` (MCP). Das Paket nennt **beide** und
sagt, **welcher Dialekt** übergeben wird — bei zwei Reverses gibt es keine
Zielseite, und Oracle setzt den Namen wie PostgreSQL
(`OracleTypeMapping.kt:83`, `OracleCapabilities.kt:63` = `false`).

Und die Projektions-Bindung beachten (s. Posten 6): kein zweiter Mechanismus
neben der vorhandenen Naht.

**DoD:** PG↔MySQL meldet die Identity nicht mehr; MySQL↔MySQL mit
unterschiedlichem **Modus** weiterhin schon; PG↔Oracle bleibt nachweislich
unveraendert (beide Seiten führen einen Namen — der übergebene Dialekt
entscheidet, und das Paket schreibt fest, welcher). Der Migrate-Pfad und der
Fingerabdruck ändern sich **nicht** — ein Test pinnt das.

### P8 — Die Faltung hält ihre Grenze (Altbestand 1.7.0/1.7.1)

**Nachgetragen am 2026-09-16, nach der Aktivierung.** ADR 0056 („Wann die
Faltung sich zurückzieht") schreibt den Altbestand nur fest, **soweit** er vier
Bedingungen erfüllt, und verlangt, ihn sonst anzupassen. Nachgestellt setzte der
Code aus 1.7.0/1.7.1 vier Paare gleich, die Verschiedenes bedeuten können:

| Feld | Paar | Ursache |
| ---- | ---- | ------- |
| CHECK **und** Sicht | `a = 1 -- x⏎AND b = 2` gegen `a = 1 -- x AND b = 2` | `\s+` → Leerzeichen kommentiert den Rest aus |
| CHECK | `$$a  b$$` gegen `$$a b$$` | Dollar-Quoting ist kein erkanntes Literal |
| CHECK | `(price::integer > 5)` gegen `price > 5` | `CAST_SUFFIX` strich **jeden** `::typ` |
| Sicht | `'a  b'` gegen `'a b'`, `'"x"'` gegen `'x'` | kein Literalschutz im Sichten-Rumpf |

**Beim Bau zusätzlich gefunden** (dieselbe Klasse, mitbehoben und gepinnt):
Leerraum **in** einem nicht-einfachen quotierten Bezeichner (`"my  col"` gegen
`"my col"`) wurde gefaltet; `REDUNDANT_PARENS` nahm auch die Klammern eines
Funktionsaufrufs (`f(x)` galt als `fx`); ein nicht geschlossenes Literal ließ
den Rest ungeschützt; ein MySQL-Backslash-Escape verschiebt die Literalgrenze
(`'a\'  b\''`).

**Gebaut** (`:hexagon:core`): ein gemeinsames Gerüst (`RawSqlSkeleton`) für
CHECK, Index-Prädikat und Sichten-Rumpf — String-Literale und nicht-einfache
quotierte Bezeichner stehen als Platzhalter, einfache quotierte Bezeichner
entpackt; `[` direkt hinter Name, `]`, `)` oder Platzhalter ist Index/Array,
kein T-SQL-Quoting. **Rückzug** (das Feld wird wortgleich verglichen): `--`,
Blockkommentar-Anfang, `$…$`-Dollar-Quoting außerhalb eines Literals, ein
Backslash irgendwo im Text, eine offene Quotierung. Die Kanonisierer dahinter:
`ExpressionSpelling` (CHECK/Index) und `QuerySpelling` (Sicht).

**Die Cast-Regel ist enger als der Vorschlag des Koordinators** („`::typ` an
String- oder Ganzzahl-Literal ohne Modifikator"): gefaltet wird nur ein
String-Literal auf `text`/`varchar`/`character varying` und ein
Ganzzahl-Literal (auch `(0)`) auf `numeric`/`decimal` — jeweils ohne
Modifikator und ohne `[]`. Grund: auch ohne Modifikator ändern Casts den Wert
(`'abc'::char` ist `char(1)` und kürzt, `70000::smallint` scheitert,
`'…'::date` deutet um). **Folge:** `(x > (0)::double precision)`,
`'…'::bpchar`, `'…'::date` und `'…'::timestamp without time zone` bleiben
Funde. Keine 1.7.1-Zusicherung pinnte einen Spalten- oder Modifikator-Cast als
gleich — `ExpressionCanonicalisationTest` bleibt unverändert grün.

**DoD:** Jedes der Paare oben ist ein Fund; je Paar steht eine **Gegenprobe**,
die zeigt, dass die Faltung daneben weiter greift (`SpellingFoldBoundaryTest`).
Die Grenzfall-Tests aus 1.7.1 bleiben unverändert grün. Sabotage je Teil
(Rückzug, Literalschutz im Sichten-Rumpf, Cast-Regel, Funktionsaufruf-Klammern,
offene Quotierung). P8 steht **vor** P5, weil P5 das Index-Prädikat an genau
diese Kanonisierung hängt.

### P7 — Der Vertrag zieht nach: Spec, ADR-Supersede, README

P3 und P5 aendern genau die Menge, die `spec/cli-spec.md:667` **normativ
aufzaehlt** — und die Spec sagt dort ausdruecklich, dass „umgestellte
Konjunktionen" ein Unterschied **bleiben**. P5 nimmt zusaetzlich die
Index-Praedikate auf, die die Spec an dieser Stelle gar nicht nennt (sie spricht
von CHECK/EXCLUDE und Sichten).

**Der Kern des Pakets ist eine Statusänderung — kein zweiter ADR daneben.** Die
bewegte Linie gehört einem akzeptierten ADR:
[`ADR 0053`](../../adr/0053-vergleich-rohen-sql-texts.md) (Entscheidung 1: „Rohes
SQL wird **nicht** lokal normalisiert. Kein Zeichen-Scanner, kein Parser";
Entscheidung 4: „`schema compare` bleibt streng") — und er nennt
`IndexDefinition.where` und `ConstraintDefinition.expression` **namentlich**.
Dieselbe Linie wiederholen
[`0048`](../../adr/0048-enum-wertevorrat-im-fingerprint.md),
[`0049`](../../adr/0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md),
[`0050`](../../adr/0050-overlay-bindung-uebergang-vs-darstellung.md) und
[`0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md) in ihren
Abgrenzungen.

**Warum ein zweiter ADR daneben nicht genügt:** akzeptierte ADRs sind im Kern
eingefroren (`make doc-immutable`), und **kein Gate prüft, ob zwei akzeptierte
ADRs sich widersprechen**. Stellte man den neuen ADR nur daneben, bliebe 0053
`accepted`, alle Gates blieben grün — und der Widerspruch stünde im Repo. Die
eine erlaubte Kernänderung ist die Statuszeile: `0053` wechselt auf
`status: superseded by ADR-00NN` (`.d-check.yml` lässt genau diese Form zu).

Der Repo-eigene Fahrplan für diese Linie steht in
`compare-falsch-positive-cross-dialekt.md:274-282` und verlangt
**Eigner-Entscheidung, ADR, Spec-Update, Testumzug** — in dieser Reihenfolge.
Die Eigner-Entscheidung holt das Paket **vorher** ein; sie ist die Vorbedingung
dieses Slices (s. Kopfzeile), nicht sein Inhalt.

**DoD:**
1. `ADR 0053` trägt `status: superseded by ADR-00NN`; der neue ADR nennt die
   übersteuerte Entscheidung und die neue Grenze und steht in
   [`docs/adr/README.md`](../../adr/README.md).
2. `spec/cli-spec.md` nennt die erweiterte Faltungsmenge (P3/P5) **und** das
   Pfad-Schema (P2b); die verbliebenen Grenzfragen stehen dort als solche.
3. `make docs-check` und `make doc-immutable RANGE=origin/main..HEAD` grün.
   **Und das ist falsifizierbar:** wer 0053 ohne Statusänderung im Kern anfasst,
   macht `doc-immutable` rot; ein bloss danebengestellter ADR lässt beide Gates
   grün und ist damit **nicht** die Erfüllung dieses DoD.

## Akzeptanzkriterien

1. Im gemeldeten Repro: **6** meldet nichts mehr (PG↔MySQL), **3** in seinem
   PG↔MSSQL-Bein, **5** seine Schreibweise-Differenzen (Index-Prädikat **und**
   Listen-Komma); 1 nennt Vorher und Nachher; 2 folgt dem Pfad-Schema der
   übrigen Funde. Was bleibt, bleibt **bewusst** — die zwei Grenzfragen und die
   ADR-entschiedene Umschreibung; ein Abnehmer, der die ganze Dreier-Matrix
   erwartet, erwartet zu viel.
2. Jeder Test fällt nachweislich, wenn man seinen Fix zurücknimmt — je Paket,
   und bei P2 je Teilpaket.
3. Die Pfad-Präfixe aller Fund-Arten folgen **einem** Schema (P2b), und der
   W137-Fund ist darauf gezogen (P2a).
4. Der Migrate-Pfad und der Fingerabdruck sind nachweislich unverändert (P5,
   P6) — gepinnt.
5. `ADR 0053` ist übersteuert **und** die Spec nennt die neue Faltungsmenge sowie
   das Pfad-Schema (P7); die zwei Grenzfragen stehen dort als solche.
6. Die Grenzfall-Tests aus 1.7.1 bleiben unverändert grün — insbesondere
   `("Quantity" > 0)` gegen `(quantity > 0)` und die Literal-Schutzfälle.
7. Die vier Altbestands-Paare aus P8 sind Funde — Kommentar mit wandernder
   Reichweite (CHECK und Sicht), Dollar-Quoting, Cast an einer Spalte,
   Literal im Sichten-Rumpf —, je mit Gegenprobe.

## Verifikation

1. **Jedes Paket dort bauen, wo es sitzt** — der erste Entwurf nannte die
   falschen Module:

   | Paket | Modul | womit |
   | ----- | ----- | ----- |
   | P1 | `:adapters:driving:cli` (Signatur) **und** `:adapters:driving:mcp` (`details`) | `make docker-check` |
   | P2a | `:hexagon:application` (Pfad) **und** `:adapters:driving:mcp` (Regex) | `make docker-check` |
   | P2b | `:adapters:driving:mcp` (Praefixe) **und** `spec/` (Pfad-Schema) | `make docker-check` |
   | P3, P5 | `:hexagon:core` | `make docker-check` |
   | P6 | `:hexagon:application` (Helfer) **und** die zwei Comparator-Baustellen `:adapters:driving:cli` + `:adapters:driving:mcp` | `make docker-check` |
   | P8 | `:hexagon:core` | `make docker-check` |
   | P7 | `docs/adr/` + `spec/` | `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD` |

   **Diesen Slice fährt kein Integrationsmodul:** der einzige Posten, der eines
   brauchte (4, MySQL-Reader), ist in den Reader-Slice gewandert — dort läuft er
   als `make integration INTEGRATION_TASKS=":test:integration-mysql:test"`.
   Zur Erinnerung für alles Künftige: ohne `-PintegrationTests` überspringen
   sich die Integrations-Tasks **lautlos** und Gradle meldet trotzdem
   `BUILD SUCCESSFUL`.

2. **Sabotage je Paket** — Fix zuruecknehmen, Fehlschlag sehen, zuruecksetzen.
   Und die Ruecknahme danach **verifizieren**: die Ausgabe lesen, nicht annehmen.

3. **Der Konsumenten-Repro ist die Abnahme**: PG-Reverse gegen MSSQL-, MySQL-
   und SQLite-Reverse. Die **Paare ausschreiben** — „Dreier-Matrix" heisst im
   Dokument sonst die Comparator-Matrix (PG↔MSSQL, PG↔MySQL, MSSQL↔MySQL), und
   SQLite kommt in keinem Posten vor: es ist der **Nullfall** (dort gibt es
   keine Kanonisierung zu prüfen). `examples/mcp-e2e` fährt die Matrix **nicht**
   (es vergleicht Quelle gegen je einen Reverse).

4. **Der Harness mit gepinnten Erwartungen ist ein anderer**:
   `examples/sample-db/expected/pagila-smoke.compare.txt` plus Byte-Diff-Abbruch
   (`examples/sample-db/scripts/smoke.sh:182`; die Baseline steht in Zeile 30).
   `examples/mcp-e2e` **pinnt nicht** — sein README
   sagt das ausdruecklich („der Harness pinnt sie nicht, er zeigt sie").

5. **Vertrags-Gates:** `make docs-check` (P7 fasst `spec/` an),
   `make doc-immutable RANGE=origin/main..HEAD` (P7 ändert eine ADR-Statuszeile —
   und muss **rot** werden, wenn jemand `ADR 0053` ohne Statusänderung im Kern
   anfasst) und `make solid-suppression-gate`.

## Offen (nicht Teil dieses Slices)

- **Der Schlüsselwort-Case** (`sum` gegen `SUM`) — Eigner-Frage **ohne Ort**. Der
  Plan hat für die beiden anderen Fragen einen Anker; für diese nicht. Sie
  braucht einen eigenen `open/`-Eintrag (Muster:
  [`../open/spatial-profile-e052-ganze-tabelle.md`](../open/spatial-profile-e052-ganze-tabelle.md)
  und [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md)
  — beide am 2026-09-16 aus derselben Messreihe entstanden) **oder** einen
  Abschnitt im in-progress-Slice, der die Linie laut eigenem Text besitzt. Ohne
  das ist die Frage nach der Graduation weg.
- **`= ANY(ARRAY[…])` gegen `IN (…)`** — keine offene Frage, sondern eine
  **ADR-entschiedene**: [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
  setzt sie für den zielbewussten Vergleich gleich und lässt `schema compare`
  streng. Sie zu verschieben wäre eine Statusänderung an 0055 — dieselbe Linie
  wie P7, aber eine **andere** Entscheidung.
- **Die zweite MCP-Oberfläche fehlt im Plan.** `schema_compare_start` baut den
  Comparator **ohne** `canonicalizeRawExpressions`
  (`McpCoreJobWorkerFactory.kt:142`) und publiziert den rohen `SchemaDiff` als
  JSON (`:318-323`). P1 und P5 sprechen von „MCP" und meinen den synchronen
  Handler — der asynchrone Pfad ist von beiden Änderungen nicht erreichbar. Ob
  er sie erben soll, ist eine eigene Entscheidung; heute ist es ein Unterschied,
  den niemand dokumentiert.
- **Unbenannte Indizes und die Schreibweise.** Der Zuordnungsschlüssel eines
  unbenannten Index trägt das rohe Prädikat (P5, „Grenze"); ob `schema compare`
  ihn über die kanonische Form bilden soll, ist nicht entschieden. Heute ist
  das Ergebnis konservativ: entfernt + hinzugefügt statt „unverändert".
- **Die Anwendersicht ist hier nicht betroffen** — und das ist begründet: kein
  `docs/user/`-Text zeigt Compare-Funde oder deren `path`, und der Präzedenzfall
  derselben Änderung (VIEW_CHANGED-Vorher/Nachher in 1.7.1) hat `docs/user/`
  nicht angefasst. **Eine Ausnahme wandert mit:** der Posten C1/P6 im
  Reader-Slice verschiebt die Grenze von `E012`, und die steht im
  Anwenderhandbuch (`docs/user/anwenderhandbuch.md:2125`) — dort zieht der
  Reader-Slice mit.

## Was der Slice bewusst nicht tut

Er entscheidet **keine** Grenzfrage: die zwei verbliebenen gehören dem Eigner,
die dritte ist ADR-entschieden. Er behebt, was unstrittig falsch ist — und trägt
die Begründung mit: ein fehlendes Vorher/Nachher (P1), zwei Pfad-Schemata
(P2a/P2b), **redundante** Klammern um einen Operanden (P3, samt der Begründung,
warum sie redundant sind), zwei fehlende Faltungszweige (P5) und ein
Herkunfts-Feld, das als Schema-Eigenschaft gewertet wird (P6). Und er bewegt
dabei eine ADR-Linie — das ist kein Nebeneffekt, sondern P7.

**Nicht mehr hier:** Posten 4 (MySQL-Reader) ist am 2026-09-16 in den
[Reader-Slice](../next/reader-treue-spatial-array-json.md) gewandert, als Posten C1 mit
Paket P6. **Nicht behoben** wird die Umschreibung `= ANY(…)` gegen `IN (…)`:
sie ist in ADR 0055 entschieden.
