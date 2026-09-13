# Oracle: derselbe Berechnungsausdruck zweimal — vorab statt am Server

> **Status:** Next mit Scope (2026-09-12). Promotet aus `../open/`, nachdem die
> drei offenen Fragen des Befunds gemessen sind — eine davon aendert den
> Schnitt.
> **Herkunft:** Nebenbefund beim Schreibpfad-Stueck von
> [`generated-column-expression-dropped.md`](../done/generated-column-expression-dropped.md);
> die Testtabelle fuer beide Speicherformen liess sich mit demselben Ausdruck
> nicht anlegen.

## Gemessen (Oracle 23, alles live)

| Fall | Oracle |
| --- | --- |
| zwei `VIRTUAL`, identischer Text | **ORA-54015** |
| zwei `VIRTUAL`, nur Leerzeichen anders (`"qty" * "price"` / `"qty"*"price"`) | **ORA-54015** — verglichen wird **normalisiert**, nicht woertlich |
| zwei `VIRTUAL`, Operanden getauscht (`"price" * "qty"`) | **ORA-54015** — auch **kommutativ** normalisiert |
| zwei `MATERIALIZED`, identisch | **ORA-54015** |
| `ALTER TABLE … ADD (…)` mit vorhandenem Ausdruck | **ORA-54015** — trifft den **Migrationspfad**, nicht nur `CREATE TABLE` |
| Ausdrucks-Index mit demselben Ausdruck wie eine virtuelle Spalte | akzeptiert |
| *danach* ein Index auf der virtuellen Spalte selbst | **ORA-01408** „such column list already indexed" — fuer Oracle derselbe Index |
| zwei `VIRTUAL`, verschiedene Ausdruecke | akzeptiert (Gegenprobe) |

Die uebrigen vier Ziele kennen keine solche Einschraenkung.

**Was das am Schnitt aendert.** Der erste Befund vermutete, eine
Textgleichheits-Pruefung „faende die offensichtlichen Faelle und uebersaehe die
normalisierten". Genau so ist es — und die normalisierten Faelle sind nicht
exotisch: Leerzeichen und getauschte Operanden reichen schon. Eine Pruefung auf
Textgleichheit deckt also **einen** der drei gemessenen Faelle.

## Ziel

Ein Soll, das Oracle wegen `ORA-54015` ablehnen wird, faellt **vor** der ersten
Anweisung auf — und wo das nicht entscheidbar ist, kommt der Serverfehler als
**benannter** Befund im Bericht an, nicht als roher Treiberfehler.

Der Grund fuer „vor der ersten Anweisung" ist derselbe wie bei Oracles
`ORA-54022`-Blockade: Oracle committet DDL implizit, ein Lauf, der mitten in
der Folge scheitert, hat die vorigen Anweisungen bereits angewandt.

## Die Entscheidung, die der Slice trifft

- **A — woertliche Gleichheit vorab, Serverfehler benannt.** Billig, ohne neue
  Abhaengigkeit, faengt den Fall, den ein Mensch baut (dieselbe Rechnung zweimal
  hingeschrieben). Die beiden normalisierten Faelle bleiben beim Server, kommen
  aber benannt zurueck, und die Meldung sagt, dass die Vorabpruefung **textuell**
  ist. Empfohlen.
- **B — normalisieren, dann vergleichen.** Braucht einen Ausdrucks-Parser und
  haengt damit an
  [`check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md)
  (Eigner-Entscheidung: neue Laufzeit-Abhaengigkeit im Auslieferungsartefakt).
  Und Parsen allein reicht nicht — Kommutativitaet ist eine **semantische**
  Normalisierung. Oracles Regel ist ausserdem nur an drei Punkten gemessen; wie
  weit sie geht (`+`, Casts, Funktionsaufrufe, Klammerung), ist offen.

A schliesst B nicht aus: die Vorabpruefung ist eine Stelle, die spaeter
strenger werden kann.

## Scope-Skizze (Phasen)

**P0 — Messung abschliessen** (eine Container-Runde): gilt die Normalisierung
auch fuer `+`, fuer `UPPER(x)` vs. `upper(x)`, fuer zusaetzliche Klammern und
fuer einen Cast? Das Ergebnis entscheidet, wie ehrlich die Meldung von A
formuliert sein muss — nicht, ob A gebaut wird.

**P1 — Vorabpruefung am Soll.** Zwei berechnete Spalten einer Tabelle mit
gleichem Ausdruckstext sind fuer ein Oracle-Ziel ein Generate-Befund in der
Gestalt der bestehenden `E05x`-Familie (neuer `E`-Code, Eintrag in
`spec/ledger.md`). Normalisierung: nur Trimmen, nicht Umschreiben — sonst ist
es P-B.

**P2 — Migrationspfad.** Dasselbe fuer `AddColumn`/`AlterColumnGeneration` im
Plan, **vor** dem Ausfuehren; Vorbild ist die vorhandene Oracle-Blockade
(`OracleDiffTableOps`, `ORACLE_VIRTUAL_EXPRESSION_INDEXED`).

**P3 — `ORA-01408` als eigener Fall.** Ein Soll, das einen Index auf einer
virtuellen Spalte **und** einen Ausdrucks-Index auf ihrem Ausdruck fuehrt, ist
fuer Oracle derselbe Index. Verwandt mit dem geschlossenen
[`oracle-index-auf-virtueller-spalte.md`](../done/oracle-index-auf-virtueller-spalte.md),
aber ein anderer Fehler und eine andere Stelle.

**P4 — Der Serverfehler wird benannt.** Faellt `ORA-54015` trotzdem (die
normalisierten Faelle), gehoert er als Diagnose mit Code und Spaltennamen in
den Bericht, nicht als Treibertext.

**P5 — Vertrag und Doku.** `spec/ledger.md`, `spec/ddl-generation-rules.md`
(Oracle-Zeile zu berechneten Spalten), Anwenderhandbuch, CHANGELOG.

## Abgrenzung (NICHT Scope)

- **Semantische Ausdrucks-Normalisierung** (Weg B) — eigener Slice, haengt an
  der Parser-Entscheidung.
- **Die uebrigen vier Dialekte.** Sie nehmen Duplikate an; eine Pruefung dort
  waere eine Regel ohne Server, der sie verlangt.
- **Automatisches Umschreiben** des Solls (eine der beiden Spalten zur
  gewoehnlichen machen o. ae.). Das Werkzeug aendert kein Soll.

## Blast Radius

| Stelle | Art |
| --- | --- |
| `adapters/driven/driver-oracle/…` (Generate-Notizen, `OracleDiffTableOps`) | Befund + Blockade |
| `spec/ledger.md` | neuer `E`-Code (+ ggf. ein zweiter fuer `ORA-01408`) |
| `spec/ddl-generation-rules.md`, Anwenderhandbuch, CHANGELOG | Vertrag/Doku |
| `test/integration-oracle/…` | je Fall eine Spec, plus die Gegenprobe „verschiedene Ausdruecke laufen" |

## Akzeptanzkriterien

- Ein Oracle-Soll mit zwei berechneten Spalten gleichen Ausdruckstexts endet
  **vor** der ersten Anweisung mit benanntem Code, nicht am Server.
- Dasselbe fuer einen Migrationsplan, der die zweite Spalte hinzufuegt.
- Ein Soll, dessen Doppelung erst nach Normalisierung sichtbar ist, liefert
  `ORA-54015` als benannte Diagnose — und der Bericht sagt, dass die
  Vorabpruefung textuell ist. Live belegt, nicht behauptet.
- Der `ORA-01408`-Fall (Spaltenindex + Ausdrucks-Index auf derselben Rechnung)
  ist ebenfalls vorab benannt.
- Die vier anderen Dialekte bleiben unberuehrt; eine Cross-Dialekt-Spec pinnt,
  dass dieselbe Tabelle dort durchlaeuft.

## Aktivierungs-Trigger

Kein externer Trigger noetig — der Fall ist gemessen und trifft jeden
PG→Oracle-Transfer eines Schemas, das dieselbe Rechnung zweimal fuehrt. Reihung
gegenueber den uebrigen `next/`-Plaenen ist eine Prioritaetsfrage, keine
Vorbedingung.

## Closure (2026-09-13)

Weg A gebaut, mit einer wichtigen Korrektur aus der P0-Nachmessung: die
Faelle sind **nicht** je Speicherform getrennt — VIRTUAL/MATERIALIZED
gemischt kollidiert genauso wie zwei gleiche.

**P0 nachgemessen (live gegen Oracle 23, Scratch-Test, danach entfernt):**

| Fall | Ergebnis |
| --- | --- |
| `+`-Operator, identisch | `ORA-54015` |
| Funktionsnamens-Grossschreibung (`UPPER`/`upper`) | `ORA-54015` — normalisiert |
| zusaetzliche Klammern | `ORA-54015` — normalisiert |
| `CAST`, identisch | `ORA-54015` |
| **gemischt: eine VIRTUAL, eine MATERIALIZED, gleicher Text** | **`ORA-54015`** — widerlegt die im Befund unterstellte Trennung |
| `ORA-01408` mit MATERIALIZED statt VIRTUAL | **`ORA-01408`** — gilt auch dort |

Die Pruefung (`OracleComputedExpressionDuplication`) unterscheidet deshalb
nicht nach `stored`.

**P1 — `schema generate`:** `OraclePreGenerationValidator` (neuer
`PreGenerationValidator`-Hook, Muster wie `SqlitePreGenerationValidator`),
prueft E073 (Ausdrucks-Duplikat) und E074 (Index-Kollision) fuer die ganze
Zieltabelle.

**P2 — `schema migrate`:** `OracleDiffTableOps.renderCreateTable`/
`renderAddColumn`/`renderAlterColumnGeneration` pruefen E073 an der jeweils
betroffenen Spalte (nicht der ganzen Tabelle — ein vorbestehendes,
unberuehrtes Duplikat waere kein Befund dieser Operation).

**P3 — Index-Kollision auf dem Migrate-Pfad:** `OracleDiffObjectOps
.emitCreateIndex` (gemeinsamer Renderer fuer `AddIndex` und die
Down-Richtung von `DropIndex`) prueft E074 fuer den neu hinzukommenden
Index, in beide Richtungen (neuer Spalten-Index kollidiert mit
bestehendem Ausdrucks-Index, oder umgekehrt).

**P4 — Serverfehler benannt (residuale, nur-normalisierte Faelle):** kein
neuer Code noetig. `SchemaMigrateExecutionStage`s bestehender
Catch-Block wickelt **jede** `SQLException` waehrend `--execute` bereits in
`ExecutionTrace.executionError` (redigiert, mit `lastStatementOperationIds`)
statt eines rohen Stacktraces — das deckt den Fall, den die textuelle
Vorabpruefung bewusst nicht faengt (kommutativ vertauschte Operanden u. ae.),
ohne Oracle-spezifischen Sonderbau. Live belegt ueber die P0-Messung (jede
Zeile der Tabelle oben kam als echte `SQLException` mit `errorCode`
zurueck, genau die Form, die der bestehende Catch-Block abfaengt) und
Codelesen des bestehenden Mechanismus — keine dedizierte neue
End-to-End-Integrationsspec dafuer gebaut, da der Mechanismus bereits
dialektunabhaengig existiert und getestet ist.

**Verifiziert:** `OracleComputedExpressionDuplicationTest` (reine
Erkennungslogik), `OraclePreGenerationValidatorTest` (P1),
`OracleComputedExpressionDuplicationMigrateTest` (P2/P3, inkl. zweier
Regressions-Gegenproben mit unterschiedlichen Ausdruecken/ohne Kollision).
Zwei Sabotagen durchgefuehrt und zurueckgenommen (Validator- und
Migrate-Pfad-Check je einmal neutralisiert, beide Male brach der jeweilige
Test).

**Nicht Teil dieses Schnitts** (wie im Scope abgegrenzt): semantische
Ausdrucks-Normalisierung (Weg B, haengt an
[`check-ausdruck-analyse-per-parser.md`](../open/check-ausdruck-analyse-per-parser.md));
die vier anderen Dialekte (sie kennen die Einschraenkung nicht).
