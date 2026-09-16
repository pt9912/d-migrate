# Compare: Restfehlalarme und Projektionslücken aus der Konsumentenmessung

> **Status:** Entwurf mit Scope (2026-09-16). Gemeldet gegen `1.7.1`, im Code
> nachgemessen.
> **Vorbedingung / Gate:** Die **drei** Grenzfragen (Schlüsselwort-Case,
> `= ANY(ARRAY[…])` gegen `IN (…)`, `RESTRICT` gegen implizit) gehören dem Eigner
> und werden **hier nicht** entschieden — sie stehen am Ende als Verweis auf
> [`../in-progress/compare-falsch-positive-cross-dialekt.md`](../in-progress/compare-falsch-positive-cross-dialekt.md).
> **Aktivierung:** Move nach `../in-progress/` beim ersten Implementierungs-Commit.

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
(1.7.1, `viewChanged`); der Constraint-Zweig wurde nicht mitgenommen.

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

### 4 — MySQLs Charset-Introducer macht das Schema ungültig (gemessen)

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
aus. Beides zusammen deutet auf **eine** Stelle im Reader, nicht auf zwei.

**Offen und Teil des Pakets:** ob der Reader den Introducer streichen soll (dann
verschwindet beides) oder ob er ihn bewahrt und die Analyse ihn kennen muss.
Das ist eine Entscheidung am Modell, nicht am Text — der Plan nimmt sie nicht
vorweg.

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

**(b) Der gemeldete Fall ist keine Schreibweise.** Nachgemessen an präparierten
Dateien:

```
# BEIDE Zeilen als CHECK-Constraint gemessen — auf dem CHECK-Pfad, wo die
# Faltung aktiv ist. Ueber den INDEX-Pfad waeren sie beide DIFFERENT, weil
# dort (a) gar nicht faltet; das ist genau der Befund von (a).
status = ANY (ARRAY['NEW'::text, 'PAID'::text])  <->  status = ANY (ARRAY['NEW','PAID'])
  → IDENTICAL      (der Cast faellt — Schreibweise, auf dem CHECK-Pfad)

status = ANY (ARRAY['NEW','PAID'])                <->  status IN ('NEW','PAID')
  → DIFFERENT      (der Rest ist eine Umschreibung — auf JEDEM Pfad)
```

Die zweite Zeile traegt die Aussage: selbst **mit** aktiver Faltung bleibt
`= ANY(ARRAY[…])` gegen `IN (…)` verschieden. Die erste zeigt nur, dass die
vorhandene Faltung Casts strippt — **nicht**, dass der Index-Pfad das täte.

`= ANY(ARRAY[…])` und `IN (…)` sind **nicht** dieselbe Schreibweise, sondern
zwei Formen desselben Prädikats. Sie gleichzusetzen ist eine
**Bedeutungs**-Entscheidung — dieselbe Klasse wie die Grenzfragen unten,
nicht ein fehlender Zweig. Der gemeldete `ix_order_open`-Fall wird von (a)
allein also **nicht** geschlossen.

**Folge für den Zuschnitt:** (a) ist ein Paket, (b) ist eine Eigner-Frage.

### 6 — Der Identity-`sequenceName` ist PG-Buchhaltung (verifiziert, Zuordnung korrigiert)

Der Konsument meldete `Identity(mode=BY_DEFAULT, sequenceName=public.customer_id_seq, …)`
gegen `sequenceName=null` als Fehlalarm bei PG↔MySQL. **Die Zuordnung „MySQL" war
falsch:** nachgesehen stammt der Name aus dem **PostgreSQL**-Reverse —

```kotlin
PostgresTypeMapping.kt:95   sequenceName = input.generatedSequenceName
MysqlTypeMapping.kt:42      ColumnGeneration.Identity(legacySerialSyntax = true)   // kein name
```

MySQL setzt bei `AUTO_INCREMENT` **nie** einen Sequenznamen. Der Posten ist damit
weder ein MySQL- noch ein Reader-Thema: der Vergleich wertet ein Feld, das nur
**eine** Seite führt und das beschreibt, **wie** der Server die Erzeugung
organisiert — nicht, **was** die Spalte ist. Dieselbe Klasse wie `sourceDialect`
(AP2) und `engine` (AP3) im Compare-Slice.

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

Der einzige **Reader**-Posten ist **4** (MySQL). Posten 6 sitzt **nicht** dort —
er ist ein Vergleichs-Thema (die Naht ist nur nicht verdrahtet), und sein Fix
liegt in `:hexagon:application`. Alles uebrige sitzt im Comparator oder in der
Projektion.

## Ziel

Drei **verschiedene** Ausgänge, je nach Posten — sie in einen Satz zu zwingen
wäre die erste Ungenauigkeit:

- **Fehlalarme hören auf** — bei 3 in **einem Bein von dreien**, bei 6 ganz,
  bei 5 zur Hälfte. Dieselbe Sache in zwei Schreibweisen ist keine Änderung;
  was keine Schreibweise ist, bleibt ein Fund:
  - **3**: PG↔MSSQL schliesst die Klammer-Faltung. Die zwei MySQL-Beine
    brauchen zusaetzlich die Schlüsselwort-Case-Entscheidung (Abgrenzung).
  - **5**: die Schreibweise-Differenz schliesst der fehlende Zweig; die
    **Umschreibung** (`= ANY(ARRAY[…])` gegen `IN (…)`) bleibt (Abgrenzung).
  - **6**: schliesst ganz.
- **Funde sagen, was sich geändert hat** (1) und folgen **einem** Pfad-Schema
  (2) — sie werden nicht weniger, sie werden brauchbar.
- **Das Modell ist gültig** (4): ein MySQL-Reverse mit einem solchen CHECK läßt
  sich validieren und vergleichen, statt an `E012` zu scheitern.

## Abgrenzung (nicht in diesem Slice)

- **Schlüsselwort-Case** (`sum` gegen `SUM`). Der Konsument weist zu Recht
  darauf hin, dass die dokumentierte Ausnahme **MySQL-spezifisch** formuliert
  ist („Kleinschreibung, Schemaqualifikation, gliedernde Klammern" — so steht es
  in der Erwartungsmatrix des Konsumenten; im Repo gibt es diese Datei nicht);
  der PG↔MSSQL-Fall fällt nicht darunter, und dort differiert nur
  `sum`/`SUM` plus Whitespace. Das Falten von **Schlüsselwörtern** wäre eng —
  das Falten von **Bezeichnern** wäre falsch (`"MyCol"` ≠ `mycol` in
  PostgreSQL). Die Grenze zu ziehen ist eine Eigner-Entscheidung; sie gehört
  zum Compare-Slice, nicht als Nebenfix hierher.
- **`= ANY(ARRAY[…])` gegen `IN (…)`** (Posten 5, Teil b). Zwei Formen desselben
  Prädikats, und keine ist Schreibweise der anderen — sie gleichzusetzen ist eine
  Bedeutungs-Entscheidung. Gehört zu den Grenzfragen, nicht in einen Nebenfix.
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
Test pinnt den CHECK-Fall, den Fall **blanker** Seite und den Index-Fund (der
nach P5 stehen bleibt und seinen `where` zeigen muss).

### P2 — Ein Pfad-Schema fuer alle Funde

**Der „Pfad" ist kein Feld.** `DiffDiagnostic` traegt gar keinen
(`DiffDiagnostic.kt:12-18`); der MCP-Fund zieht ihn per **Regex aus dem
Meldungstext** (`SchemaCompareHandler.kt:303-312`), und das CLI-Dokument fuehrt
nur `code`/`severity`/`message` (`SchemaCompareProjection.kt:35`,
`spec/cli-spec.md:657`). Das Paket aendert also einen **Meldungstext** (und
die Regex-Vertraeglichkeit dazu) — nicht ein Feld. Wer ein Feld erwartet, baut
am falschen Ende.

**Und „die uebrigen Funde" sind mehrere Domaenen.** Neben `tables.…` stehen
`views.`/`sequences.`/`custom_types.`/`functions.`/`procedures.`/`triggers.`
(`SchemaCompareHandler.kt:259-276`). Ein Schema heisst: alle folgen demselben
Aufbau — die Richtung ist Teil des Pakets.

**DoD:** Ein Test sammelt die Praefixe aller Fund-Arten eines nicht-trivialen
Vergleichs und verlangt **ein** Schema; die MCP-Regex liest den neuen Text.

### P3 — Zusammengesetzte Ausdrücke kanonisieren

`OR`/`AND`-Komposition und die gruppierenden Klammern um Operanden. **Die enge
Fassung gilt weiter:** nur Klammern, die *keine* Bedeutung tragen, und nur
Wortstellung, die nichts umstellt. Der Wächter aus 1.7.1 bleibt: eine
Kanonisierung, die zu viel gleichsetzt, versteckt echte Unterschiede.

**DoD:** Der **PG↔MSSQL**-Leg des gemeldeten Vergleichs meldet
`ck_order_ship_after_place` nicht mehr. Die zwei MySQL-Beine bleiben es
**zunächst** — sie brauchen die Schlüsselwort-Case-Entscheidung (Abgrenzung).
Die Grenzfall-Tests aus `ExpressionCanonicalisationTest` bleiben grün.

### P4 — Der MySQL-Reader traegt keinen Dialekt-Anhang ins Modell

Die Stelle sitzt im **Reader**, nicht in der Kanonisierung: was MySQL in
`CHECK_CLAUSE` liefert, kommt als `_utf8mb4'…'` (und offenbar mit
backslash-escapten Quotes) ins neutrale Modell — und macht es ungueltig
(`E012`, s. Abschnitt 4). Eine Kanonisierung im Vergleich wuerde daran nichts
aendern: der Fehlalarm ist die **Folge**, nicht die Ursache.

**Zu entscheiden im Paket:** streicht der Reader den Introducer (dann
verschwindet beides), oder bewahrt er den Server-Text und die Ausdrucks-Analyse
muss ihn kennen? Die zweite Variante ist teurer und beruehrt `E012`.

**DoD:** PG↔MySQL meldet `ck_customer_email_shape` nicht mehr **und** ein
MySQL-Reverse mit einem solchen CHECK ist validierbar (`schema validate` ohne
`E012`). PG↔MSSQL war vorher sauber und bleibt es.

### P5 — Das Index-Prädikat in die Faltung aufnehmen

`RawTextFolding.index` bekommt denselben `canonicalizeRawExpressions`-Zweig wie
`constraint` und `viewQuery`. Das ist die eigentliche Lücke — kein fehlender
Sonderfall, sondern ein **fehlender Zweig**.

**Dieses Paket schließt den gemeldeten `ix_order_open`-Fall NICHT** (s. Posten 5,
Teil b): dort steht eine Umschreibung, keine Schreibweise. Es schließt die
Schreibweise-Differenzen, die heute alle gemeldet werden.

**DoD:** Ein Index-Prädikat mit einer reinen Schreibweise-Differenz (Quoting,
Whitespace, Cast) meldet nichts mehr; ein Prädikat mit einer **echten**
Änderung bleibt ein Fund; `= ANY(ARRAY[…])` gegen `IN (…)` bleibt es
**zunächst auch** — bis zur Eigner-Entscheidung.

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
keinen Dialekt, der MCP-Compare baut den Comparator ohne
(`McpRuntimeRegistries.kt:125`). In den Compare-Pfad gehoert deshalb nur der
**Namens**-Teil; `stored` bleibt sichtbar — laut `TargetProjection.kt:20` heisst
`null` dort ausdruecklich **strikter** Vergleich.

Und die Projektions-Bindung beachten (s. Posten 6): kein zweiter Mechanismus
neben der vorhandenen Naht.

**DoD:** PG↔MySQL meldet die Identity nicht mehr; MySQL↔MySQL mit
unterschiedlichem **Modus** weiterhin schon; ein Oracle-Reverse
(`namesIdentitySequences = false`) bleibt unverändert. Der Migrate-Pfad und der
Fingerabdruck ändern sich **nicht** — ein Test pinnt das.

### P7 — Spec und ADR nachziehen

P3 und P5 aendern genau die Menge, die `spec/cli-spec.md:667` **normativ
aufzaehlt** — und die Spec sagt dort ausdruecklich, dass „umgestellte
Konjunktionen" ein Unterschied **bleiben**. P5 nimmt zusaetzlich die
Index-Praedikate auf, die die Spec an dieser Stelle gar nicht nennt (sie spricht
von CHECK/EXCLUDE und Sichten).

Der Repo-eigene Fahrplan fuer diese Linie steht in
`compare-falsch-positive-cross-dialekt.md:274-282` und verlangt **Eigner-Entscheidung,
ADR, Spec-Update, Testumzug** — in dieser Reihenfolge. Das Paket zieht den
Spec-Teil nach; die Eigner-Entscheidung holt es **vorher** ein.

**DoD:** Ein **ADR** haelt den Linienwechsel fest (der Fahrplan verlangt ihn
ausdruecklich, `compare-falsch-positive-cross-dialekt.md:277`); `spec/cli-spec.md`
nennt die erweiterte Faltungsmenge; die drei Grenzfragen stehen dort als solche;
`make docs-check` und `make doc-immutable RANGE=origin/main..HEAD` gruen.

## Akzeptanzkriterien

1. Im gemeldeten Repro: **6** meldet nichts mehr, **3** in seinem
   PG↔MSSQL-Bein, **5** seine Schreibweise-Differenzen; 1 nennt Vorher und
   Nachher; 2 folgt dem Pfad-Schema der übrigen Funde; 4 ist validierbar.
   Was bleibt, bleibt **bewusst** (drei Grenzfragen) — ein Abnehmer, der die
   ganze Dreier-Matrix erwartet, erwartet zu viel.
2. Jeder Test fällt nachweislich, wenn man seinen Fix zurücknimmt.
3. Die Pfad-Präfixe aller Fund-Arten folgen **einem** Schema.
4. Die Grenzfall-Tests aus 1.7.1 bleiben unverändert grün — insbesondere
   `("Quantity" > 0)` gegen `(quantity > 0)` und die Literal-Schutzfälle.

## Verifikation

1. **Jedes Paket dort bauen, wo es sitzt** — der erste Entwurf nannte die
   falschen Module:

   | Paket | Modul | womit |
   | ----- | ----- | ----- |
   | P1 | `:adapters:driving:cli` (Signatur) **und** `:adapters:driving:mcp` (`details`) | `make docker-check` |
   | P2 | `:hexagon:application` (Meldungstext) **und** `:adapters:driving:mcp` (Regex) | `make docker-check` |
   | P3, P5 | `:hexagon:core` | `make docker-check` |
   | P4 | `:adapters:driven:driver-mysql` | `make docker-check` |
   | P6 | `:hexagon:application` | `make docker-check` |
   | P4-DoD (Reverse validierbar) | — | `make integration` (`-PintegrationTests`) |

   Ohne `-PintegrationTests` ueberspringen sich die Integrations-Tasks
   **lautlos** und Gradle meldet trotzdem `BUILD SUCCESSFUL`.

2. **Sabotage je Paket** — Fix zuruecknehmen, Fehlschlag sehen, zuruecksetzen.
   Und die Ruecknahme danach **verifizieren**: die Ausgabe lesen, nicht annehmen.

3. **Der Konsumenten-Repro ist die Abnahme**: PG-Reverse gegen
   MSSQL/MySQL/SQLite-Reverse, dieselbe Dreier-Matrix. `examples/mcp-e2e` faehrt
   sie **nicht** (es vergleicht Quelle gegen je einen Reverse).

4. **Der Harness mit gepinnten Erwartungen ist ein anderer**:
   `examples/sample-db/expected/pagila-smoke.compare.txt` plus Byte-Diff-Abbruch
   (`examples/sample-db/scripts/smoke.sh:182`; die Baseline steht in Zeile 30).
   `examples/mcp-e2e` **pinnt nicht** — sein README
   sagt das ausdruecklich („der Harness pinnt sie nicht, er zeigt sie").

5. `make docs-check` (P7 fasst `spec/` an) und `make solid-suppression-gate`.

## Was der Slice bewusst nicht tut

Er entscheidet **keine** Grenzfrage. **Drei** sind oben benannt — **eine**
davon ist im Compare-Slice begründet (`RESTRICT` gegen implizit,
`compare-falsch-positive-cross-dialekt.md:119`), die anderen zwei haben dort
**keinen** Anker: der Schlüsselwort-Case ist im Repo nirgends normativ gefasst,
und `= ANY(ARRAY[…])` steht nur als Beispiel in
[`../open/check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md).
Wer sie entscheiden will, braucht fuer beide einen eigenen Ort. Dieser Slice behebt nur, was unstrittig
falsch ist — ein fehlendes Vorher/Nachher (P1), zwei Pfad-Schemata (P2), einen
fehlenden Faltungs-Zweig (P5), einen Dialekt-Anhang im Modell (P4) und ein
Herkunfts-Feld, das als Schema-Eigenschaft gewertet wird (P6).
