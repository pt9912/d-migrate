# Compare: Restfehlalarme und Projektionslücken aus der Konsumentenmessung

> **Status:** In Arbeit seit 2026-09-16 (aktiviert nach zwei Review-Runden).
> **Stand der Pakete:** geliefert P8 (nachgetragen, Altbestand; `50ee1bd00`),
> P5 (`3b30d9f8a`), P3 (`ba263c737`), P6 (`10eb5a1df`), P2a (`a723584ff`),
> P2b (`b1de205f9`), P1 (`e08e217fb`) und der Spec-Teil von P7 (Commit
> „docs(spec): …" direkt danach). Offen bleibt nur die Abnahme am
> Konsumenten-Repro (Verifikation 3). Der ADR-Teil von P7 ist mit ADR 0056
> geliefert (`c9737f909`); `make doc-immutable` ist dafür im frischen
> `--no-local`-Klon geprüft (0 Befunde, Sabotage erkannt — im Arbeits-Repo ist
> das Gate still grün, s. `../open/doc-immutable-lokal-still-gruen.md`).
> **Offen: P9** (Eigner-Entscheidung 2026-09-16, nach P8 nachgetragen) — der
> Cast an einer Spalte wird mit dem Spaltentyp entschieden.
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

**Gebaut — über den CHECK hinaus.** Eine gemeinsame Kurzform
(`CompareSignature`, `:hexagon:application`) trägt jedes Feld, das der
Vergleich an Index und Constraint wertet: Schlüssel, Art, `unique`,
`clustered`, `INCLUDE`, Text-Search-Konfiguration und Prädikat; Spalten, Ziel,
`on_delete`/`on_update` und Ausdruck. Der Mangel war also breiter als
beschrieben: auch eine geänderte Schlüsselspalte eines **benannten** Index und
eine geänderte FK-Aktion standen beidseitig gleich da. Die CLI rendert damit
(`ck_qty (check: qty > 0) -> ck_qty (check: qty > 1)`), und die MCP-Funde
tragen sie als `details` — bei `…_CHANGED` beide Seiten, bei `…_ADDED` nur
`after`, bei `…_REMOVED` nur `before`. Weil die Kurzform den Namen trägt, ist
keine Seite je blank; die Falle im Helfer greift nicht mehr. Die je Feld
zerlegten Funde der übrigen Objekte (P2b) tragen `before`/`after` ebenfalls —
**ohne** Werte bleiben `query`, `body`, `parameters`, `returns` und `fields`
(lange Rümpfe, strukturierte Werte). Ohne `details` bleiben weiterhin die
Funde „hinzugefügt"/„entfernt" ganzer Objekte und Spalten.

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
**Gebaut:** `W137` nennt `tables.<tabelle>.columns.<spalte>.generation.expression`
— bis aufs Feld, wie `schema validate` den Berechnungsausdruck adressiert
(`E134` ff.). Gebaut wird der Ort über `SchemaFindingPath` in
`:hexagon:application`, den P2b auch für die übrigen Funde nutzt; damit hängen
beide Wege an einer Stelle. Die Meldung heißt jetzt „The computed expression at
`<pfad>` …"; weil `schema migrate` dieselbe Diagnose ausgibt, ändert sich dort
derselbe Text mit.

**DoD:** Der W137-Fund traegt einen Pfad, der dem Schema der uebrigen Funde
folgt, und die MCP-Regex liest ihn.

**P2b — die uebrigen Domaenen.** `views.`/`sequences.`/`custom_types.`/
`functions.`/`procedures.`/`triggers.` und `name`/`version` auf denselben Aufbau;
das Schema bekommt seinen **normativen Ort** (Spec), und das Rename laeuft ueber
`make ast-grep` — es geht ueber viele Aufrufstellen, `sed` ist dort das falsche
Werkzeug.
**Gebaut — und eine Annahme des Pakets trägt nicht.** Die gewählte Richtung
ist der **Dokument-Pfad** des neutralen Schemas, dasselbe Vokabular wie
`schema validate` (`tables.orders.constraints.ck_mail`). In dieser Richtung
folgten `views.`/`sequences.`/… und `name`/`version` dem Schema **bereits**:
`name` und `version` sind Schlüssel der obersten Ebene des Dokuments, ein
erfundenes Präfix (`schema.name`) wäre dort gerade kein Dokument-Pfad. Sie
bleiben deshalb unverändert. Der tatsächliche Bruch im Aufbau lag eine Ebene
tiefer: Tabellen melden **je geändertem Feld** mit dem Feld im Pfad
(`tables.t.columns.c.type`), die übrigen Objekte meldeten **einen** Fund am
Objekt (`sequences.s`, bei Sichten das Feld nur im Meldungstext). Gebaut ist
deshalb: ein Fund je geändertem Feld auch für Sichten, Sequenzen,
benutzerdefinierte Typen, Funktionen, Prozeduren und Trigger
(`views.v.query`, `sequences.s.min_value`, Schlüssel wie in
`spec/schema-reference.md`); Indizes und Constraints bleiben Funde am Objekt,
weil der Vergleich sie als Ganzes führt. Alle Pfade entstehen über
`SchemaFindingPath`; die Abschnitts-Templates sind per `make ast-grep`
umgeschrieben. Die Projektion ist dafür aus dem Handler in eigene Bausteine
gewandert (`SchemaCompareFindings`, `TableCompareFindings`, `CompareFinding`,
`ObjectDiffFields`) — der Handler stand bei 590 Zeilen. **Für MCP-Abnehmer ein
Vertragswechsel:** mehr Funde je Objekt, und `VIEW_CHANGED` trägt das Feld im
Pfad. Der Spec-Eintrag folgt mit dem Spec-Teil von P7.

**DoD:** Ein Test sammelt die Praefixe **aller** Fund-Arten eines nicht-trivialen
Vergleichs und verlangt **ein** Schema; das Schema steht in der Spec.

Je Paket Sabotage — die Trennung haelt einen Teilstand entscheidbar.

### P3 — Zusammengesetzte Ausdrücke kanonisieren

`OR`/`AND`-Komposition und die **redundanten** Klammern um einen Operanden
(Terminologie: s. u. — „gruppierend" wäre das Gegenteil). **Die enge Fassung
gilt weiter:** nur Klammern, die *keine* Bedeutung tragen, und nur Wortstellung,
die nichts umstellt. Der Wächter aus 1.7.1 bleibt: eine Kanonisierung, die zu
viel gleichsetzt, versteckt echte Unterschiede.

**Gebaut — enger als oben beschrieben, nach ADR 0056** (`OperandParens`,
`:hexagon:core`): eine Klammer fällt nur, wenn sie links und rechts nur an
Ausdrucksrand, `AND` oder `OR` grenzt (an mindestens einer Seite an `AND`/`OR`)
**und** ein einzelnes Vergleichsprädikat umschließt — einen Vergleichsoperator
(`=`, `<>`, `!=`, `<`, `>`, `<=`, `>=`; `@>` zählt nicht) oder `IS [NOT] NULL` —
ohne `AND`/`OR`/`NOT`/`XOR`/`BETWEEN`/`CASE`/Abfrage oder MySQLs `||`/`&&` auf
seiner obersten Ebene. Über den ADR hinaus: auf einer Ebene mit `BETWEEN` fällt
**keine** Klammer — dessen `AND` ist keine Konjunktion, und `BETWEEN` bindet in
PostgreSQL stärker als `=` (`x BETWEEN 1 AND (y = 2)`). `IN`, `LIKE` und
andere Operatoren gelten nicht als Vergleich; ihre Klammern bleiben.

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

**Gebaut.** Die Naht ist zerlegt: `capabilityGenerationCanonicalizer`
setzt sich jetzt aus `capabilityIdentitySequenceNameCanonicalizer`
(Namens-Teil) und dem Speicherform-Teil zusammen — für den Migrate-Pfad
verhaltensgleich. `schema compare` bekommt **nur** den Namens-Teil, über einen
neuen, von `TargetProjection` getrennten Parameter
`SchemaComparator(comparisonGeneration = …)`; eine gesetzte `TargetProjection`
hat Vorrang. **Welcher Dialekt:** der der ersten Seite (Quelle vor Ziel), deren
Reverse den Namen als Server-Buchhaltung liest
(`namesIdentitySequences = false`, `compareProjectionDialect`); liest keine
Seite ihn so, bleibt der Vergleich strikt. Der Dialekt einer Seite kommt aus
ihrer Reverse-Markierung (`reverseSourceDialect`) — für Datei- **und**
DB-Operanden, weil jeder Reader sie setzt; ein handgeschriebenes Schema hat
keinen. Dafür reicht der Runner jetzt je Seite ein `CompareSide(schema,
sourceDialect)` an den Comparator (geteilte Signatur; drei Aufrufstellen in
`test/integration-mysql` per `make ast-grep` nachgezogen). Beide Baustellen
(`SchemaCompareWiring`, `McpRuntimeRegistries`) bauen den Comparator je Aufruf.
Der asynchrone Pfad (`schema_compare_start`) bleibt unberührt (s. „Offen").

**Das DoD trägt in einer Lesart nicht.** „PG↔Oracle bleibt nachweislich
unverändert" ist mit der Fähigkeits-Naht nur in der Lesart „meldet keine
Änderung" erreichbar: jeder Dialekt, der PG↔MySQL faltet (PostgreSQL oder
Oracle, beide `namesIdentitySequences = false`), blendet bei PG↔Oracle
**beide** Namen aus. Die Lesart „meldet wie heute einen Fund" bräuchte eine
zweite Regel neben der Naht („nur falten, wenn genau eine Seite …"), die das
Paket ausdrücklich ausschließt. Gebaut und gepinnt ist deshalb: PG↔Oracle
meldet den Sequenznamen **nicht** mehr — beide Namen hat ein Server vergeben;
ein abweichender **Modus** bleibt ein Fund.

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

**Überholt durch P9** (zweiter Bauabschnitt, Review H1): auch die
kontextfreie Literal-Regel oben ist eine Falsch-Gleichsetzung — ein Cast kann
die umgebende Operation umtypen. Seit P9 faellt ohne Spaltenkontext gar kein
Cast mehr.

**DoD:** Jedes der Paare oben ist ein Fund; je Paar steht eine **Gegenprobe**,
die zeigt, dass die Faltung daneben weiter greift (`SpellingFoldBoundaryTest`).
Die Grenzfall-Tests aus 1.7.1 bleiben unverändert grün. Sabotage je Teil
(Rückzug, Literalschutz im Sichten-Rumpf, Cast-Regel, Funktionsaufruf-Klammern,
offene Quotierung). P8 steht **vor** P5, weil P5 das Index-Prädikat an genau
diese Kanonisierung hängt.

### P9 — Ein Cast faellt nur am Vergleich, und nur mit dem Spaltentyp

**Nachgetragen am 2026-09-16, nach P8; neu gefasst im zweiten Bauabschnitt
(Review H1).** Die enge Cast-Regel aus P8 holt Fehlalarme zurueck, die 1.7.1
nicht hatte — und zwar in der haeufigsten Form: PostgreSQL schreibt bei
**jeder** `varchar`-Spalte in einem CHECK `(status)::text`, dazu
`(0)::double precision`, `'…'::date` und `'…'::bpchar`. 1.7.1 strich jeden
Cast (falsch, s. P8); P8 strich fast keinen (zu eng fuer den Zweck des
Slices).

**Eigner-Entscheidung (2026-09-16): den Spaltentyp heranziehen.** ADR 0056
nennt als Schreibweise „einen Cast auf den Typ, den der Operand ohnehin hat";
fuer einen Spaltenbezug ist dieser Typ bekannt — der Comparator haelt an der
Faltstelle beide Tabellen (`TableComparator`, Aufruf von `folding.constraint`;
fuer den Index-Pfad `TableIndexComparator`).

**Die erste Fassung ist widerlegt — und mit ihr die kontextfreie Regel aus
P8.** Sie sah eine Tabelle je Operand vor und fuer Literale zwei
**kontextfreie** Zeilen (`date`, `time`, `timestamp`, `bpchar`, Gleitkomma),
mit einem „bekannten Restrisiko", das der Eigner in Kauf nahm. Der Review hat
gegen PostgreSQL gemessen, dass ein Cast den Wert eines Literals nicht
aendert, aber die **umgebende Operation** umtypt (Ergebnis mit Cast / ohne):

| Paar | Kontext | PG |
| ---- | ------- | -- |
| `qty / 2::numeric > 1` gegen `qty / 2 > 1` | `qty integer = 3` | `t` / `f` |
| `email = 'FOO'::text` gegen `email = 'FOO'` | `citext` | `f` / `t` |
| `code = 'a  '::text` gegen `code = 'a  '` | `char(3)` | `f` / `t` |
| `v = 'a  '::bpchar` gegen `v = 'a  '` | `varchar` | `t` / `f` |

Damit war schon die kontextfreie Literal-Regel aus P8 (`TEXT_LITERAL_CAST`,
`INTEGER_LITERAL_CAST`) eine Falsch-Gleichsetzung, und das „Restrisiko" der
geplanten Zeilen keines, das ein Plan decken kann — ADR 0056 laesst nur
Schreibweise zu. Nachgemessen (PostgreSQL 18.6) sind zusaetzlich: ein
`ARRAY[…]` aus lauter unmarkierten String-Literalen ist `text[]`; PostgreSQL
zeigt implizite Casts an Operator-Argumenten (`((v)::text ~~ 'a%'::text)`,
`((nu)::double precision > …)` bei `numeric`,
`(ttz > ('08:00:00'::time …)::time with time zone)` bei `timetz`); `real`
gegen eine Zahl vergleicht als `double precision`; `1 < @ 2` ist gueltig,
`1 <@ 2` nicht.

**Regel (CHECK und Index-Praedikat, nie Sichten-Rumpf, nie Migrate oder
Fingerabdruck).** Ein Cast faellt nur, wenn **beides** gilt:

1. Der gecastete Operand ist **unmittelbarer Operand eines Vergleichs** (`=`,
   `<>`, `!=`, `<`, `<=`, `>`, `>=`, `LIKE`/`~~`; links auch vor `= ANY (…)`)
   — nie neben einem Rechenoperator, nie in einer Argumentliste, nie auf einer
   Ebene mit `BETWEEN` (`SqlScopes`, `CastOperands`).
2. Der Typ ist aus der Tabelle **dieser Seite** belegt (`CastRules`):
   - **Spalten-Cast** `col::T`/`(col)::T` ohne Modifikator, der den Wert
     haelt: Text variabler Laenge auf `text`/`varchar`/`character varying`;
     Ganzzahl auf gleich breite oder breitere Ganzzahl, `numeric`/`decimal`;
     `numeric` auf `numeric`/`decimal`. Nicht `char(n)` auf Text, nichts mit
     Modifikator, nicht `citext` (im Modell `enum` mit `ref_type`, damit ohne
     Familie).
   - **Literal-Cast** `lit::T`/`(lit)::T` ohne Modifikator, dem eine Spalte
     dieser Tabelle gegenuebersteht (bloss oder hinter einem Spalten-Cast, der
     selbst faellt), mit **genau** deren Typfamilie: Text ↔ `text`/`varchar`/
     `character varying`; `char(n)` ↔ `bpchar`; `date` ↔ `date`;
     `datetime` ↔ `timestamp` bzw. `timestamptz` (auch ausgeschrieben);
     `time` ↔ `time`; Ganzzahl ↔ Ganzzahltyp; `decimal` ↔ `numeric`;
     Gleitkomma ↔ `double precision`/`float8`/`float`.
   - Ohne Tabellenkontext, bei unbekannter Spalte oder unbekanntem Typ: nicht
     falten.

**Beim Bau enger gezogen** (je ein gemessener oder nachgelesener Grund, alles
in `ColumnCastFoldTest` gepinnt):

- Der **Partner eines Spalten-Casts** behaelt seinen Typ: bei Text ein
  String-Literal, ein Text-Cast, eine Textspalte oder ein Text-Array; bei
  Zahlen eine Zahl, ein Zahl-Cast oder eine Zahlspalte. Ein unmarkiertes
  String-Literal nimmt den Typ seines Gegenuebers an —
  `(qty)::bigint = '3000000000'` gelingt, `qty = '3000000000'` scheitert
  (gemessen).
- **Ganzzahl-Literale:** eine Zahl nur, wenn sie in den Zieltyp passt
  (`70000::smallint` scheitert); ein String-Literal nur auf **genau** den Typ
  der Spalte (`'70000'` scheitert an `smallint`, `'70000'::integer` nicht).
  Ein `identifier` gilt als breiteste Ganzzahl (seine Breite ist je Dialekt
  verschieden) — nur `bigint` faellt dort als Spalten-Cast.
- **Gleitkomma:** eine Zahl nur auf `double precision` — so vergleicht
  PostgreSQL `real` und `double precision` mit einer Zahl; `(0.1)::real`
  rundet (`0.1::real > 0.1` ist wahr, `> (0.1)::real` nicht). Ein
  String-Literal nimmt den Spaltentyp an, nur dieser ist Schreibweise.
- **`char(n)`:** nur `bpchar`. `character` ohne Laenge ist `character(1)` und
  kuerzt; der Plan nannte ihn mit.
- **`LIKE`:** nur ein Text-Muster rechts an einer Textspalte. Ein
  `bpchar`-Muster wird zu Text gekuerzt (`'a  '::char(3) LIKE 'a  '::bpchar`
  ist falsch, `… LIKE 'a  '` wahr — gemessen).
- **Typnamen nur kleingeschrieben:** das Geruest entpackt `"TEXT"` zu `TEXT`,
  und quotiert waere es ein anderer Typ. Faellt damit weg: das 1.7.1-freie
  P8-Gegenprobenpaar `'NEW'::VARCHAR` (kein 1.7.1-Bestand; PostgreSQL schreibt
  Typnamen klein).
- **Mehrdeutige Namen** loesen nicht auf: ein Schluesselwort (`user`) und ein
  Name, den zwei Spalten ohne Ruecksicht auf die Schreibweise teilen.
- **Ueber die Vorgabe hinaus gleichgesetzt:** in `spalte = ANY (ARRAY[…])` die
  `::text` der Elemente, wenn die Spalte Text ist und das Array nur
  String-Literale traegt — ein solches Array ist auch ohne sie `text[]`
  (gemessen). Das haelt das gemessene Paar aus Abschnitt 5 (P5-Test) gleich;
  `::bpchar`-Elemente bleiben Unterschied (dort vergleicht PostgreSQL
  gepolstert).

**Grenze, bewusst: ein verlustbehafteter Reader.** Der Vergleich nimmt den
Spaltentyp aus dem Modell. Wo ein Reader zwei PostgreSQL-Typen auf einen
neutralen faltet — `numeric` ohne Praezision auf `float`, `timetz` auf `time`
—, ist schon der Spaltentyp-Vergleich blind. Eine Falsch-Gleichsetzung **durch
den Cast** entsteht dort nicht: PostgreSQL schreibt in genau diesen Faellen
einen Spalten-Cast (`(nu)::double precision`) oder einen doppelten Cast
(`('…'::time)::time with time zone`), und beide Formen faltet die Regel nicht
(gemessen, s. o.). `citext` liest der Reader als eigenen Typ; er faltet nicht.

**Folge fuer den Altbestand — eine begruendete Abweichung von AK 6.** Das
kontextfreie `canonicallyEqual` faltet keine Casts mehr. Das 1.7.1-Paar
`(email ~~ '%@%'::text)` gegen `email like '%@%'`
(`ExpressionCanonicalisationTest`) steht deshalb jetzt mit einer Textspalte
`email` — und daneben ohne Tabelle als Fund. Der Sinn des Paares bleibt; der
Aufbau aendert sich, weil derselbe Text bei `citext` etwas anderes bedeutet
(Tabelle oben). Keine Zusicherung „bleibt verschieden" ist weggefallen: die
kontextfreien Gegenproben aus P8 (`'abc'::varchar(2)`, `5::smallint`,
`2.5::integer`, `f(0)::numeric`, `'{a}'::text[]`) stehen weiter, daneben mit
Tabelle.

**Nicht:** der Sichten-Rumpf (ADR 0056: dort keine Cast-Faltung), der
Migrate-Pfad, der Fingerabdruck. Die Spalte wird nur nachgeschlagen, nie
umgeschrieben; gemeldete Definitionen bleiben unveraendert.

**Gebaut** (`:hexagon:core`): `ColumnCasts` (Faltung), `CastOperands`
(Operanden-Formen und -Grenzen), `SqlScopes` (Klammer-Ebenen), `SqlTokens`
(Tokens des Geruests), `CastRules` (Typfamilien), `ColumnTypes`/`SideColumns`
(Spaltentypen je Seite). `ConstraintDiffContract.canonicallyEqual`,
`RawTextFolding.constraint`/`index` und `TableIndexComparator` nehmen die
Spaltentypen beider Seiten; `TableComparator` reicht sie aus beiden Tabellen
durch. Die Cast-Faltung laeuft **vor** allen anderen Regeln auf dem Geruest und
streicht nur `::typ`.

**DoD:** Die Pflicht-Gleichsetzungen melden nichts — in CHECK **und**
Index-Praedikat: `(status)::text = 'x'::text` gegen `status = 'x'`
(`varchar`), `(price > (0)::numeric)` gegen `price > 0`,
`(x > (0)::double precision)` gegen `x > 0`, `d > '2024-01-01'::date` gegen
`d > '2024-01-01'`, `code = 'A'::bpchar` gegen `code = 'A'`. Die
Pflicht-Gegenproben bleiben Funde: die vier PG-Paare der Tabelle, eine
`timestamp`-Spalte mit `'2024-01-01 12:00'::date`, `(qty)::numeric / 2` gegen
`qty / 2`, ein Cast an einem Namen, der keine Spalte ist, `'abc'::varchar(2)`
— dazu je Regel eine weitere (`ColumnCastFoldTest`). Der Migrate-Pfad bleibt
nachweislich unveraendert. Spec (`spec/cli-spec.md`, Faltungsmenge) zieht
nach. Sabotage je Teilregel — **erfuellt**, Protokoll unter
„Zweiter Bauabschnitt".

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

**Gebaut (Spec-Teil):** `spec/cli-spec.md` nennt unter „`schema compare`" die
Faltungsmenge mit Grenze, Literalschutz und Rückzug (P3, P5, P8), die
Index-Schlüssel-Ausdrücke als wortgleich, den Identity-Sequenznamen (P6), die
Kurzform von Index und Constraint (P1) und das **Pfad-Schema der
Vergleichsfunde** als Grammatik (P2a/P2b); die zwei Grenzfragen
(Schlüsselwort-Case, `RESTRICT`) stehen dort als „nicht festgelegt".
`spec/mcp-server.md` hat einen Abschnitt „`schema_compare` — Funde" (Fund je
Feld, `path`, `details`, und dass `schema_compare_start` wortgleich
vergleicht). `CHANGELOG.md` führt beides unter `[Unreleased]`. `docs/user/`
ist nicht betroffen: kein Text dort zeigt Fund-Pfade, `details` oder die
Faltungsmenge (nachgesehen).

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

### Zweiter Bauabschnitt — Review und Verifikation (2026-09-16)

Nach P1–P8 lagen ein Review (nachgemessen per `jshell` gegen die gebauten
Klassen und gegen PostgreSQL 16) und eine Verifikation (per Sabotage) vor.
Umgesetzt ist, was unten steht; der Review-Befund H1 ist P9 (oben).

**B — Rueckzug und Literalerkennung vervollstaendigt (Review M1, L2;
Verifikation 3).** Die Faltung zieht sich zusaetzlich zurueck bei `#`
ausserhalb eines Literals (MySQLs Zeilenkommentar; kostet PostgreSQLs
`#`-XOR), bei Oracles `q'…'`/`Q'…'`/`nq'…'`/`Nq'…'`, bei Dollar-Tags mit
Zeichen ausserhalb von ASCII (`$ä$…$ä$`; wie PostgreSQL zaehlt jedes solche
Zeichen) und bei einem **zweideutigen `[`** nach Leerraum hinter einem Namen,
`)`, `]` oder einem Literal (`tags [pos]` ist in PostgreSQL ein Index).
**Abweichung vom Vorschlag:** ein reiner Rueckzug haette den 1.7.x-Test
`FROM [orders] [o]` (T-SQL-Alias, `ViewQueryCanonicalisationTest`) gekippt.
Das zweideutige `[` gilt deshalb als Quoting, wenn der Text an anderer Stelle
**eindeutiges** Bracket-Quoting traegt — ein `[` am Anfang, hinter einem
Operator, Komma, `(`, `.` oder Schluesselwort ist in PostgreSQL, MySQL und
Oracle gar keine Syntax, der Text also T-SQL oder SQLite, wo `[` nie ein Index
ist. Hinter einem Schluesselwort ist `[` Quoting, hinter `ARRAY` ein Array.
`"…"` bleibt Bezeichner-Quoting; dass MySQL/SQLite es je nach Modus als
String lesen, steht als **Grenze** in der Spec (und gepinnt).

**C — Quotierte Schluesselwoerter werden nicht entpackt (Review M2).**
`"user"`/`[user]` gegen `user`, `"null"` gegen `null`, `"current_date"`,
`"true"` waren gleich — im Sichten-Rumpf ebenso. Jetzt bleibt ein quotiertes
Schluesselwort ein Platzhalter in **einer** Quotierung (`"user"`), sodass
`[user]`, `` `user` `` und `"user"` untereinander gleich bleiben. Die Liste
(`SqlKeywords`) vereinigt die Woerter, die in PostgreSQL, MySQL, SQL Server,
Oracle oder SQLite als Wert oder Funktion ohne Klammern gelesen werden, und
die, die die Faltungsregeln als Syntax lesen (`and`/`or`/`not`, `in`, `like`,
`between`, `case` …) — damit `OperandParens` `"and"` nicht als Junktor liest.
Bewusst **nicht** darin: haeufige Spaltennamen, die unquotiert eine Spalte
bleiben (`date`, `time`, `timestamp`, `name`, `type`, `value`, `position`).

**D — kleinere Falsch-Gleichsetzungen (Review L1, L3; Verifikation 2).**
Namen zaehlen wie in PostgreSQL mit jedem Zeichen ausserhalb von ASCII
(`maß(x)` ≠ `maßx`, `señor(b = 2) OR c` ≠ `señorb = 2 OR c`; `SqlLexis`
ersetzt das ASCII-`\b`). `f (x)` ist ein Aufruf — hinter einem Namen und
Leerraum faellt die Klammer nur, wenn der Name ein Operanden-Wort ist (`AND`,
`OR`, `NOT` …). `~~` wird zu ` like ` (mit Leerraum; `!~~` und `~~*` bleiben).
Eine Klammer direkt vor `.` bleibt (`(addr).city`). Leerraum um `/` und `%`
wird gefaltet — Code und Spec sagen jetzt dasselbe. **Beim Bau zusaetzlich
gefunden:** die Leerraum-Regel zog zwei Operatorzeichen zusammen —
`a < @ b` (`a < abs(b)`) galt als `a <@ b` (gemessen: das erste ist gueltig,
das zweite nicht), `a @ > b` als `a @> b`, im Sichten-Rumpf `a = @ b` als
`a =@ b`. Leerraum zwischen zwei Operatorzeichen faellt jetzt nur, wo
PostgreSQL die zusammengezogene Folge wieder genauso zerlegt (hinten nur
`+`/`-`, kein verlaengerndes Zeichen: `a < -1` gleich `a<-1`).

**H — Aufraeumen (Review INFO 2, 4, 6).** `stripOuterParens` ist in
`OperandParens.stripEnclosing` aufgegangen, das doppelte `WHITESPACE` in
`SqlLexis`. **Nicht ganz ohne Verhaltensaenderung:** bei unbalancierten
eckigen Klammern (`(a])`, kein gueltiges SQL) faellt die aeussere Klammer nicht
mehr (gepinnt). `TableComparator.projectGeneration`: die KDoc sagte „kein
`?:`-Fallback", der Code hatte einen — auf die **Funktion**, nicht auf ihr
Ergebnis. Entschieden: verhaltensgleich als `when` ausgeschrieben (mit
Zielprojektion gilt nur deren Erzeugungs-Projektion, sonst die des
Vergleichs, sonst keine); die KDoc sagt das jetzt. `spec/cli-spec.md`
„hinter … einer Klammer" heisst jetzt „hinter `)`, `]`".

**Sabotage-Protokoll A–D, H** (`make docker-test MODULES=":hexagon:core"`,
fuenf Laeufe mit disjunkten Erwartungen; nach jedem Lauf Ruecknahme per
Archiv und `diff -r` bestaetigt; danach gruen, 1468 Tests):

| Lauf | Sabotage | rot |
| ---- | -------- | --- |
| 1 | Operanden-Grenzen immer erfuellt | „a cast right at the comparison, but with arithmetic on its other side" |
| 1 | Schluesselwoerter entpackt | drei Faelle „Quotierte Schluesselwoerter" |
| 1 | `#` kein Rueckzug | „a hash comment whose extent changes" |
| 1 | Namen nur ASCII | `maß(x)`, `señor(…)` |
| 1 | `~~` ohne Leerraum | `x~~y`, „with or without whitespace" |
| 2 | unbekannter Name gilt als Textspalte | „a cast on a name that is no column" |
| 2 | kein `q'`-Rueckzug | „q'…' … are not folded" |
| 2 | Dollar-Tag nur ASCII | `$ä$…$ä$` |
| 2 | Leerraum vor `(` ignoriert | „whitespace before a call's parenthesis" |
| 2 | Feldzugriff ignoriert | `(addr).city` |
| 3 | `citext` (Enum) als Text | „on a citext column" |
| 3 | `date` fuer `timestamp` | „a date literal against a timestamp column" |
| 3 | zweideutiges `[` immer Quoting | „PostgreSQL reads `tags [pos]`" |
| 3 | Operatorzeichen immer zusammengezogen | „not where joining two operator characters" |
| 3 | kein Leerraum-Falten um `/`, `%` | „around division and modulo" |
| 3 | alte `stripOuterParens` | „unbalanced brackets" |
| 4 | `char(n)` als Text | „a text literal against char(n)", „a column cast that can change the value", LIKE-Fall |
| 4 | `bpchar` als Texttyp | „a bpchar literal against varchar" |
| 4 | unmarkiertes String-Literal als Zahl-Partner | „a widened column against an untyped string literal" |
| 4 | Zahl passt immer | „a literal cast that rounds or fails", `70000::smallint` |
| 5 | Gleitkomma-Familie offen | „a literal cast that rounds or fails" |
| 5 | `LIKE` ohne Text-Vorbehalt | „LIKE folds only a text pattern" |
| 5 | Typnamen ohne Schreibweise | „a type name in another spelling", „in capitals" |
| 5 | Array-Elemente jeden Typs | „an ANY array of bpchar elements" |
| 5 | Ganzzahl-Breite ignoriert | „a column cast that can change the value" |
| 5 | Vergleich in Argumentliste/`BETWEEN` | „a comparison inside an argument list or on a BETWEEN level" |
| 5 | T-SQL-Beleg ignoriert | „where the text shows T-SQL quoting elsewhere", `ViewQueryCanonicalisationTest` (MSSQL-Bein) |
| 5 | Typmodifikator mitgestrichen | „a cast with a type modifier" (beide Specs) |

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
   **Begründete Abweichung (P9):** das Cast-Paar
   `(email ~~ '%@%'::text)` gegen `email like '%@%'` braucht jetzt eine
   Textspalte `email` — bei `citext` bedeutet derselbe Text etwas anderes.
   Keine Zusicherung „bleibt verschieden" fällt weg.
7. Die vier Altbestands-Paare aus P8 sind Funde — Kommentar mit wandernder
   Reichweite (CHECK und Sicht), Dollar-Quoting, Cast an einer Spalte,
   Literal im Sichten-Rumpf —, je mit Gegenprobe.
8. Ein Cast fällt nur als unmittelbarer Operand eines Vergleichs und nur,
   wenn die Tabelle dieser Seite seinen Typ belegt (P9) — insbesondere
   PostgreSQLs `(status)::text` bei `varchar`. Ein Cast, der den Wert ändern
   oder die umgebende Operation umtypen kann, bleibt ein Fund — die vier
   gegen PostgreSQL gemessenen Paare aus P9 eingeschlossen. Ohne
   Tabellenkontext fällt kein Cast. Das Paar aus AK 7
   (`(price::integer > 5)` gegen `price > 5`) bleibt ein Fund, solange
   `price` kein Ganzzahltyp ist.

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
- **`schema_compare` (MCP) bereinigt keine Reverse-Markierung.** Anders als
  der CLI-Runner (`CompareOperandNormalizer`) vergleicht der synchrone
  MCP-Handler die Schemanamen roh; zwei Reverse-Artefakte aus verschiedenen
  Dialekten tragen verschiedene Markierungen
  (`__dmigrate_reverse__:postgresql:…` gegen `…:mysql:…`) und ergeben damit
  einen `SCHEMA_NAME_CHANGED`-Fund. Aus dem Code gelesen, nicht durch einen
  Test gepinnt; nicht Teil dieses Slices.
- **Eine Partitionierungs-Änderung hat keinen MCP-Fund.** `TableDiff` trägt
  `partitioning`, die Projektion von `schema_compare` kennt dafür keinen Code:
  eine Tabelle, die sich nur darin unterscheidet, ergibt `status: different`
  ohne Eintrag in `findings`. Beim Bau von P2b gefunden, nicht Teil dieses
  Slices.
- **Casts an Spalten sind wieder Funde.** Die engere Cast-Regel aus P8 faltet
  `(status)::text` nicht mehr — PostgreSQL schreibt diesen Cast für jede
  `varchar`-Spalte in einem CHECK, 1.7.1 hat ihn gefaltet. Ob der Vergleich den
  Spaltentyp aus dem Schema heranziehen soll, um einen wertgleichen Cast an
  einer Spalte zu erkennen, ist nicht entschieden (Eigner). Dasselbe gilt für
  `(0)::double precision`, `'…'::bpchar` und `'…'::date`.
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
