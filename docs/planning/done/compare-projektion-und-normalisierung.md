# Compare: Restfehlalarme und Projektionslücken aus der Konsumentenmessung

> **Status:** **Done — graduiert 2026-09-17.** Aktiv seit 2026-09-16, sechs
> Bauabschnitte, fünf Review- und Verifikationsrunden. Noch nicht released: die
> Wirkung steht in `CHANGELOG.md` unter `[Unreleased]` (Stand `main`,
> 1.8.0-SNAPSHOT). Die Closure mit Paket → Commit steht am Ende, jeder offene
> Punkt mit seinem Ort unter „Restflächen" direkt unter diesem Kopf.
> **Geliefert — erster Bauabschnitt:** P8 (Altbestand, `50ee1bd00`), P5
> (`3b30d9f8a`), P3 (`ba263c737`), P6 (`10eb5a1df`), P2a (`a723584ff`), P2b
> (`b1de205f9`), P1 (`e08e217fb`), Spec-Teil von P7 (`26ff678ed`); der
> ADR-Teil von P7 mit ADR 0056 (`c9737f909`; `make doc-immutable` im frischen
> `--no-local`-Klon geprüft, s. `../done/doc-immutable-lokal-still-gruen.md`).
> **Geliefert — zweiter Bauabschnitt** (Review und Verifikation, s. dort):
> P9 in neuer Fassung samt Rückzug, Schlüsselwörtern und Lexik (A–D, H;
> `13e397475`), MCP-Werte und Index-Pfad (E, F; `58510584d`), Absicherung (G;
> `522722ad3`), Doku und Plan (I) und die Abnahme am nachgebauten
> Konsumenten-Repro (J, Verifikation 3; beide `2913ab7fd`) samt
> `make sample-db-smoke` (Verifikation 4; Harness-Fix `0f39332d9`), Gates
> `d14f7021b`.
> **Geliefert — dritter Bauabschnitt** (Eigner-Entscheidungen, Review und
> Verifikation Runde 2, s. dort): Faltungsgrenzen H1, M1, L1, INFO 5, INFO 6
> (`175800393`), P10 (`b3e583522`), P11 samt M3 (`2601d1631`), MCP-Funde L2,
> INFO 3, INFO 4 (`ff4d56082`), Handbuch L3 (`f8c819f6c`), PostGIS-Mount der
> Sample-DB (`47f8a8641`); der Repro mit dem Schema des Konsumenten über MCP
> und CLI, `make sample-db-smoke` und `make sample-db-spatial-smoke`.
> **Geliefert — vierter Bauabschnitt** (Eigner-Entscheidungen F1–F4, Review
> und Verifikation Runde 3, s. dort): P10 zurückgenommen und als
> Reverse-Präferenz `serial`/`identity` gebaut (F3, `5a9eaf0a2`), Name und
> Version kein Fund, sobald eine Seite ein Reverse ist (M1, `0e0e1cb24`),
> eigene Artefakt-Art `COMPARE` in einer Form samt typisiertem Publisher
> (F1, L1, I1, I5, Verifier M1; `694b88776`), Doku-Befunde (L2, L3, I2, I3,
> I4; `e3116c34c`), Handbuch-Hinweis zu `schema migrate` (`fe6b3d270`),
> MySQL-Integrationsfall (`542008cbd`); der Repro mit dem Schema des
> Konsumenten ohne und mit Präferenz und `make sample-db-smoke`.
> **Geliefert — fünfter Bauabschnitt** (Review und Verifikation Runde 4,
> E2E-Harnesses, s. dort): Reverse-Präferenzen streng, mit Herkunft und
> einmal beim Start (L-3, INFO; `49a3b3d4c`), Reverse-Report der MCP-Lese-Jobs
> (M-2; `f345e00f7`), Spec, Handbuch, Ticket und CHANGELOG (M-1, L-1, L-2,
> INFO; `a1a02b919`), Roundtrip-Wächter und 5x5-Compare-Matrix über MCP
> samt Workflow (`c7bfe88dd`). ADR 0057 ist seit `f6bab1514` `accepted`.
> **Geliefert — sechster Bauabschnitt** (Korrekturabschnitt 6, Review und
> Verifikation Runde 5, s. dort): eigene Artefakt-Art `REVERSE_REPORT`
> (`df098292c`), Ergebnis zuletzt abgelegt (`c803dda52`), `R202` an der
> Stelle der Deklaration (`453722a34`), Harness-Blocker, Pin-Schutz,
> Selbstprobe und sehender Sequenz-Wächter samt rot sichtbarem Workflow
> (`328479ba4`, `61ab4aaae`), Spec, Handbücher und CHANGELOG (`d5e0c5f9b`).
> **Offen in diesem Slice:** nichts. Die zwei Eigner-Fragen
> (Schlüsselwort-Case, `RESTRICT`) und jeder Punkt der Liste „braucht nach der
> Graduation einen Ort" haben bei der Graduation einen Ort bekommen (s.
> „Restflächen"). Posten 4 (MySQL-Introducer) liegt im Reader-Slice; die
> Harnesses weisen ihn als bekannten Zustand aus.
> Gemeldet gegen `1.7.1`. **Belegart je Posten:** nachgemessen sind 1, 2, 3, 5, 6
> **und** 4 — bei 4 hat die Nachmessung nur eine andere *Art* ergeben als die
> Meldung nahelegte (Reader statt Kanonisierung), nicht eine andere Tatsache.
> **Vorbedingung / Gate:** Die **zwei** verbliebenen Grenzfragen
> (Schlüsselwort-Case, `RESTRICT` gegen implizit) gehören dem Eigner und werden
> **hier nicht** entschieden; `RESTRICT` ist in
> [`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md)
> verankert, der Schlüsselwort-Case hatte bei der Aktivierung keinen Ort; seit
> 2026-09-17 stehen beide im
> [Toleranzprofil](../next/compare-toleranzprofil.md) (K1, K4). Die dritte
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
> **Der Widerspruch stand bei der Aktivierung im Repo — mit P7 erledigt:** 1.7.1
> faltete in `schema compare` bereits CHECK- und Sichten-Text
> (`canonicalizeRawExpressions = true`, damals je ein Aufruf in
> `SchemaCompareWiring` und `McpRuntimeRegistries`; seit P11 über
> `SchemaCompareSemantics`), und `spec/cli-spec.md` beschrieb das — während
> `ADR 0053` weiter „`schema compare` bleibt streng" sagte. P7 hat nicht nur
> die Erweiterung abgeschlossen, sondern auch diesen Altbestand (ADR 0056).
> **Aktiviert** am 2026-09-16 (Move aus `../next/`).
> **Graduiert** am 2026-09-17 (Move nach `../done/`).

## Restflächen (2026-09-17)

**Nichts davon ist Bauschuld dieses Slices.** Jeder Punkt ist entweder eine
Eigner- oder ADR-Frage, ein vorbestehender Befund, der beim Bauen sichtbar
wurde, oder eine Grenze, die der Slice bewusst zieht. Jeder hat bei der
Graduation einen Ort bekommen; die Liste „Offen" weiter unten bleibt als Stand vor
der Graduation stehen.

**Eigner- und ADR-Fragen**

| Punkt | Ort |
| --- | --- |
| Schlüsselwort-Case (`sum` gegen `SUM`) | [Toleranzprofil](../next/compare-toleranzprofil.md), Kandidat K1 |
| `RESTRICT` gegen implizit | Toleranzprofil K4; Umbrella [`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md) |
| Identity-Modus gegen MySQL und SQLite (Fähigkeitsunterschied, ADR 0057) | Toleranzprofil K2 |
| `= ANY(ARRAY[…])` gegen `IN (…)` | keine offene Frage: [ADR 0055](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md) entscheidet sie; als Toleranz Kandidat K3 (bräuchte eine Statusänderung an 0055 und 0056) |
| F4 — die übrigen server-vergebenen Namen (Messauftrag, ADR 0057) | [`../open/compare-serververgebene-namen-messauftrag.md`](../open/compare-serververgebene-namen-messauftrag.md) |
| MCP `schema_compare` validiert nicht (CLI: Exit 3) | [`../open/mcp-schema-compare-validiert-nicht.md`](../open/mcp-schema-compare-validiert-nicht.md) |

**Vergleich und MCP-Vertrag**

| Punkt | Ort |
| --- | --- |
| Unbenannte Indizes werden über das rohe Prädikat zugeordnet (P5, Grenze) | [`../open/compare-unbenannte-indizes-kanonische-zuordnung.md`](../open/compare-unbenannte-indizes-kanonische-zuordnung.md) |
| Partitionierungs-Änderung ohne MCP-Fund (gepinnt in `ObjectDiffFieldsCompletenessTest`) | [`../open/compare-different-ohne-sichtbaren-fund.md`](../open/compare-different-ohne-sichtbaren-fund.md), Punkt 1 |
| „0 change(s)" bei `DIFFERENT` (nur Name/Version, zwei handgeschriebene Schemata) | ebenda, Punkt 2 |
| Index `diffs` (und `profiles`) in `mcp serve` leer | [`../open/mcp-verdrahtungsluecken.md`](../open/mcp-verdrahtungsluecken.md), Punkt 1 |
| `job_input`-Upload über das Tool-Schema nicht erreichbar | ebenda, Punkt 2 |
| `artifact_upload_init` nimmt mehr Arten an, als die Spec nennt | [`../open/mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md), Befund 3 |
| `schema_list` filtert `jobId` gegen die Job-URI | ebenda, Befund 4 |

**Reverse und Präferenzen**

| Punkt | Ort |
| --- | --- |
| Präferenz `serial`/`identity` pro MCP-Aufruf | [`../open/reverse-praeferenzen-oberflaechen-restflaechen.md`](../open/reverse-praeferenzen-oberflaechen-restflaechen.md), Punkt 1 |
| `R202` nennt ohne gesetztes Flag auch in der CLI nur den Konfigurationsschlüssel — bewusst so gelassen (spec-konform) | ebenda, Punkt 2 |
| `data transfer` gibt keine Reader-Notes aus | ebenda, Punkt 3 |
| `schema migrate` gegen MySQL plant ein wirkungsloses `MODIFY COLUMN` (zwei Ursachen, ADR 0027) | [`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md), Nachtrag MySQL |
| Reverse-Umfang CLI (`--include-*`) gegen MCP (immer) | [`../open/reverse-umfang-cli-gegen-mcp.md`](../open/reverse-umfang-cli-gegen-mcp.md) |

**Reader und Generator** (Reader-Slice
[`reader-treue.md`](../next/reader-treue.md))

| Punkt | Ort |
| --- | --- |
| Posten 4 — MySQL-Introducer macht das Schema ungültig (`E012`), samt der `E012`-Grenze im Anwenderhandbuch (`docs/user/anwenderhandbuch.md:2198`) | Reader-Slice C1/P6 |
| Typ einer berechneten Spalte in SQL Server | Reader-Slice D1 |
| `numeric` ohne Präzision als `float` gelesen | Reader-Slice D2 |
| `varchar` ohne Länge und `inet`/`interval` als `text` | Reader-Slice D3 |
| MySQL-Generator rendert `GENERATED ALWAYS` ohne Warnung | Reader-Slice D4 |
| Matrix `APPLY-FAIL` SQLite → SQL Server (`Msg 2714`, `fk_0`-Namen) | Reader-Slice D5 |
| Matrix `APPLY-FAIL` SQL Server → PostgreSQL/MySQL (T-SQL-Quoting, `E053`) | Reader-Slice D6 |
| Matrix `APPLY-FAIL` SQLite → MySQL (`ERROR 1170`) | [`pk-constraint-prefix-length.md`](../next/pk-constraint-prefix-length.md), Nachtrag (andere Ursache: längenloser Text) |
| Native Typ-Seeds und Silent-Loss-Check der Compare-Matrix | Reader-Slice, „Verifikation", Punkt 5 |

**Gates, CI und Test-Infrastruktur**

| Punkt | Ort |
| --- | --- |
| Dreizehn Workflows mit job-weitem `continue-on-error` (sechs Sample-DB-Cross-Smokes, sieben weitere) | [`../open/ci-verdeckte-fehlschlaege.md`](../open/ci-verdeckte-fehlschlaege.md), Teil 1 |
| `integration.yml` ohne `--continue` | ebenda, Teil 2 |
| Der Workflow der Compare-Matrix ist bei der Graduation noch nie in CI gelaufen | ebenda, Teil 3 (Beobachtungspunkt) |
| Das FTS-Testimage pinnt das Paket `mssql-server` nicht | [`../open/mssql-testimage-2025-cu1-startet-nicht.md`](../open/mssql-testimage-2025-cu1-startet-nicht.md), Nachtrag |
| `make doc-immutable` friert `superseded`-ADRs nicht ein (P7-DoD 3 eingeschränkt) | [`../done/doc-immutable-lokal-still-gruen.md`](doc-immutable-lokal-still-gruen.md), Nachtrag — geschlossen 2026-09-17 |
| Oracle in Repro und Harnesses nicht gefahren | [`../open/mcp-e2e-oracle-nicht-gefahren.md`](../open/mcp-e2e-oracle-nicht-gefahren.md) |
| Usage-Fehler enden mit Exit 1 statt 2 (Review Runde 5, M-3) | [`../open/cli-usage-fehler-exit-1-statt-2.md`](../open/cli-usage-fehler-exit-1-statt-2.md) |
| Kein statisches Gate für Shell, Kotlin und YAML; zwei vorbestehende shellcheck-Befunde in `examples/mcp-e2e/scripts/smoke-scope-matrix.sh` | [`semgrep-scoped-packs.md`](../next/semgrep-scoped-packs.md), Nachtrag |

**Bewusst ohne eigenen Ort** — dokumentierte Grenzen, keine offene Arbeit:

- Die `notation`-Heuristik der Harness-Wächter erkennt kein Paar, dessen
  Seiten verschiedene Signaturformate tragen (beide Seiten kommen aus
  demselben Renderer); der `metadata`-Wächter ist in der Matrix strukturell
  blind, der Roundtrip sichert ihn. Beides steht im README und im Kopf des
  Harness.
- Ein `varchar` ohne Länge bleibt ein Fund, wo PostgreSQL `(spalte)::text`
  schreibt — der Preis der M1-Korrektur in P9; die Modellfrage dahinter ist
  Reader-Slice D3.
- Die MySQL-View-Formatierung bleibt ein Fund (Abgrenzung).
- Die 1.7.1-Zählung PG↔MSSQL (23 Funde hier, 22 beim Konsumenten) ist nicht
  untersucht; sie betrifft nur die Vergleichsbasis, nicht den gebauten Stand.
- Die Zwischencommits `49a3b3d4c` und `f345e00f7` sind nicht einzeln gebaut,
  nur der Endstand — ein Hinweis für `git bisect`.

**Und die `Datei:Zeile`-Anker im Text sind Entwurfs- bzw. Bauabschnittsstand.**
Wer einen Beleg nachfährt, sucht über den Symbolnamen; nachgezogen ist bei der
Graduation nur der `E012`-Anker des Anwenderhandbuchs.

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
[Reader-Slice](../next/reader-treue-1-matrix-abnahme.md) — dieselbe Messung, dieselbe
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
  PostgreSQL). Die Grenze zu ziehen ist eine Eigner-Entscheidung; **sie hatte
  bei der Aktivierung keinen Ort** — seit 2026-09-17 Kandidat K1 im
  [Toleranzprofil](../next/compare-toleranzprofil.md). Und „nirgends normativ gefasst" stimmt nur
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
mit seinem Paket in den [Reader-Slice](../next/reader-treue-1-matrix-abnahme.md)
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
[`SchemaCompareWiring.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareWiring.kt)
(CLI) und `McpRuntimeRegistries.kt` (MCP), damals je ein eigener
Comparator-Aufruf (seit P11 beide über `SchemaCompareSemantics`). Das Paket nennt **beide** und
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
Der asynchrone Pfad (`schema_compare_start`) blieb in diesem Schritt
unberührt; seit P11 vergleicht er über dieselbe Stelle.

**Das DoD trägt in einer Lesart nicht.** „PG↔Oracle bleibt nachweislich
unverändert" ist mit der Fähigkeits-Naht nur in der Lesart „meldet keine
Änderung" erreichbar: jeder Dialekt, der PG↔MySQL faltet (PostgreSQL oder
Oracle, beide `namesIdentitySequences = false`), blendet bei PG↔Oracle
**beide** Namen aus. Die Lesart „meldet wie heute einen Fund" bräuchte eine
zweite Regel neben der Naht („nur falten, wenn genau eine Seite …"), die das
Paket ausdrücklich ausschließt. Gebaut und gepinnt ist deshalb: PG↔Oracle
meldet den Sequenznamen **nicht** mehr — beide Namen hat ein Server vergeben;
ein abweichender **Modus** bleibt ein Fund.

**DoD (auf das Gebaute gezogen, zweiter Bauabschnitt):** PG↔MySQL meldet den
Sequenznamen nicht mehr; MySQL↔MySQL mit unterschiedlichem **Modus**
weiterhin schon; PG↔Oracle meldet die beiden server-vergebenen Namen **nicht**
mehr — die ursprüngliche Lesart „bleibt unverändert ein Fund" ist mit der
Fähigkeits-Naht nicht erreichbar (s. oben), und eine zweite Regel daneben
schließt das Paket aus; übergeben wird der Dialekt der ersten Seite, deren
Reverse den Namen als Buchhaltung liest. Der Migrate-Pfad und der
Fingerabdruck ändern sich **nicht** — gepinnt, seit G auch am verdrahteten
Comparator.
**Grenze aus dem Repro — seit dem vierten Bauabschnitt über eine
Reverse-Präferenz lösbar:** P6 blendet nur den **Namen** aus. Ein
PG-`IDENTITY` trägt `legacy_serial_syntax: false`, MySQLs `BIGINT
AUTO_INCREMENT` las der Reader immer als `legacy_serial_syntax: true` —
PG↔MySQL meldete solche Spalten deshalb nach P6 weiter; geschlossen war der
Posten nur für PG-`serial`-Spalten. Die erste Antwort (P10, Faltung im
Vergleich) ist zurückgenommen; jetzt entscheidet der Anwender am Reverse
(`--mysql-autoincrement-syntax identity`, s. P10), und mit dieser Deklaration
schließt Posten 6 auch für `IDENTITY`.

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
     haelt: Text variabler Laenge auf `text`/`varchar`/`character varying`
     (seit dem dritten Bauabschnitt nur **mit Laengenangabe**, Review-Befund
     M1);
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
     Gleitkomma ↔ `double precision`/`float8`/`float` (seit dem dritten
     Bauabschnitt nur für eine **ganze** Zahl, Review-Befund H1).
   - Ohne Tabellenkontext, bei unbekannter Spalte oder unbekanntem Typ: nicht
     falten.

**Beim Bau enger gezogen** (je ein gemessener oder nachgelesener Grund, alles
in `ColumnCastFoldTest` gepinnt):

- Der **Partner eines Spalten-Casts** behaelt seinen Typ: bei Text ein
  String-Literal, ein Text-Cast, eine Textspalte oder ein Text-Array; bei
  Zahlen ein Zahl-Literal, ein Cast auf einen **exakten** Zahltyp (Ganzzahl,
  `numeric`/`decimal`) oder eine Spalte eines exakten Zahltyps. Ein unmarkiertes
  String-Literal nimmt den Typ seines Gegenuebers an —
  `(qty)::bigint = '3000000000'` gelingt, `qty = '3000000000'` scheitert
  (gemessen).
- **Ganzzahl-Literale:** eine Zahl nur, wenn sie in den Zieltyp passt
  (`70000::smallint` scheitert); ein String-Literal nur auf **genau** den Typ
  der Spalte (`'70000'` scheitert an `smallint`, `'70000'::integer` nicht).
  Ein `identifier` gilt als breiteste Ganzzahl (seine Breite ist je Dialekt
  verschieden) — nur `bigint` faellt dort als Spalten-Cast.
- **Gleitkomma:** eine Zahl nur auf `double precision` (seit dem dritten
  Bauabschnitt nur eine ganze Zahl, s. unten) — so vergleicht
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

**Grenze: ein verlustbehafteter Reader — die erste Fassung dieses Absatzes ist
widerlegt (Review Runde 2, H1 und M1).** Der Vergleich nimmt den Spaltentyp aus
dem Modell. Wo ein Reader zwei PostgreSQL-Typen auf einen neutralen faltet, ist
schon der Spaltentyp-Vergleich blind. Die erste Fassung behauptete, **durch den
Cast** entstehe dort keine Falsch-Gleichsetzung, weil PostgreSQL in genau
diesen Faellen einen Spalten- oder Doppel-Cast schreibe. Das stimmt nicht:
- `numeric` ohne Praezision liest der Reverse als `float`. PostgreSQL schreibt
  dort `(nu > 0.5)` ohne Cast, bei `double precision` aber
  `(x > (0.5)::double precision)`; die Literal-Regel strich den Cast, und zwei
  Datenbanken mit verschiedenem Verhalten waren `IDENTICAL` (1.7.1:
  `DIFFERENT`; Ende-zu-Ende nachgestellt, s. dritter Bauabschnitt).
  **Korrigiert:** an Gleitkomma faellt nur der Cast einer ganzen Zahl — die
  bekommt auch an `numeric` einen Cast (`(0)::numeric`), der nicht faellt.
- Einen Typ, den er nicht kennt (`inet`, `interval`), liest der Reverse als
  `text` ohne Laenge (`R301`). `(ip)::text` aendert dort den Wert
  (`10.0.0.1/32`), die Spalten-Cast-Regel strich ihn. **Korrigiert:** der
  Spalten-Cast auf Text faellt nur an einer Textspalte mit Laengenangabe
  (PostgreSQL schreibt `(spalte)::text` bei `varchar(n)`, nie bei `text`).
  Preis: ein `varchar` ohne Laenge — im Modell nicht von `text` zu
  unterscheiden — bleibt ein Fund, wo PostgreSQL `(spalte)::text` schreibt.

`timetz` auf `time` bleibt, wie beschrieben, beim doppelten Cast
(`('…'::time)::time with time zone`), den die Regel nicht faltet. `citext`
liest der Reader als eigenen Typ; er faltet nicht. Dass der Reader `numeric`
ohne Praezision als `float` liest, ist selbst ein Reader-Verlust (s. „Offen").

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

### P10 — `legacy_serial_syntax`: zurückgenommen, eine Reverse-Präferenz löst es (F3)

**Erste Fassung (dritter Bauabschnitt, `b3e583522`) — zurückgenommen.** Sie
wertete das Flag in `schema compare` nicht, sobald eine Seite aus einem
Dialekt stammte, der `SERIAL` und `IDENTITY` nicht unterscheidet
(`DialectCapabilities.distinguishesSerialFromIdentity`,
`capabilitySerialSyntaxCanonicalizer`, `compareSerialDialect`). Der Architekt
fragte im ADR-Entwurf 0057 (F3), ob das zu
[`spec/dialect-preference-mechanism.md`](../../../spec/dialect-preference-mechanism.md)
passt: inhärente Reverse-Mehrdeutigkeiten löst eine deklarierte Präferenz am
Reverse, „nie im nachgelagerten Vergleich".

**Eigner-Entscheidung (2026-09-17): unverträglich.** Ob MySQLs
`BIGINT AUTO_INCREMENT` (und SQLites `AUTOINCREMENT` unter der 64-Bit-Breite)
eine `SERIAL`- oder eine IDENTITY-Spalte meint, ist genau so eine
Mehrdeutigkeit. Die Faltung entfällt vollständig, die Fähigkeit mit ihr — sie
trägt danach nichts mehr (kein zweiter Nutzer; der Generator fragt
`rendersAutoIncrementAsIdentity`).

**Gebaut (vierter Bauabschnitt).**
- **Port:** `SchemaReadOptions.autoIncrementSyntax` (`SERIAL` als Default,
  byte-identischer Reverse) und `ReversePreferences`, das die Werte je
  gelesenem Dialekt auswählt (`:hexagon:ports-read`). Das Feld ist
  dialekt-neutral; der Aufrufer wählt den Wert des Dialekts, den er liest.
- **Reader:** MySQL (`MysqlTypeMapping`, `bigint`-Zweig) und SQLite
  (`SqliteTypeMapping`, nur unter `BIGINTEGER_IDENTITY`) lassen das Flag unter
  `IDENTITY` weg und bestätigen das mit `R205` (`AutoIncrementSyntaxNote`,
  `:adapters:driven:driver-common`). `INT AUTO_INCREMENT` und SQLite unter der
  32-Bit-Breite tragen kein Flag; die Präferenz wirkt dort nicht. Die
  Fingerabdruck-Kanonisierer rufen ohne Präferenz auf und bleiben unverändert.
- **Oberfläche:** `schema reverse --mysql-autoincrement-syntax` und
  `--sqlite-autoincrement-syntax` (`serial`|`identity`), Konfiguration
  `reverse.mysql.autoincrement_syntax` und `reverse.sqlite.autoincrement_syntax`;
  Flag > Datei > Default (`ReverseAutoIncrementSyntaxResolver`). Das
  nachsichtige Lesen des `reverse:`-Blocks teilen sich beide Präferenzen
  (`ReverseConfigBlock`); `ReversePreferencesResolver` bündelt sie.
- **Reichweite der Konfiguration:** auch die `db:`-Operanden von
  `schema compare` (`SchemaCompareWiring`) und `mcp serve`
  (`McpServeWiring` → `McpCoreJobWorkerFactory`: `schema_reverse_start` und
  `schema_compare_start` mit Verbindungen) lesen den `reverse:`-Block — sonst
  verglichen sich die mit Präferenz geschriebene Datei und die Datenbank
  verschieden. Das gilt seitdem auch für `reverse.sqlite.autoincrement_width`,
  die beide vorher nicht sahen.
- **MCP — geprüft und entschieden:** der MCP-Reverse muss die Präferenz sehen,
  sonst kann ein MCP-Abnehmer sie nicht deklarieren und PG↔MySQL meldet über
  MCP immer die IDENTITY-Spalten. Getragen wird sie von der
  Konfigurationsdatei des Servers (`--connection-config` bzw. `--config`);
  ein Tool-Argument als Pendant zum Flag pro Aufruf ist **nicht** gebaut
  (s. „Offen").
- **Nicht:** `data transfer` bekommt kein Flag — der Transfer wertet
  `legacy_serial_syntax` nicht aus (die Breite behält ihr Flag dort).
  `schema migrate` und `schema rollback` lesen den Ist-Stand weiter ohne
  Präferenz (s. „Offen").

**Wirkung.** Ohne Deklaration meldet PG↔MySQL eine IDENTITY-Spalte wieder als
Unterschied in `legacy_serial_syntax`; mit `identity` entfällt er, weil der
Reverse ihn nicht mehr erzeugt. Der Modus bleibt ein Fund (K2 im
Toleranzprofil).

**DoD — erfüllt:** Default unverändert (Mapping, Reader, Wiring-Optionen,
`make sample-db-smoke`), `identity` setzt das Flag nicht und meldet `R205`
(MySQL/SQLite, Mapping und Reader, `integration-mysql`), Präzedenz
Flag > Datei > Default (`ReverseAutoIncrementSyntaxResolverTest`,
`SchemaReverseWiringTest`), die Konfiguration erreicht `db:`-Operanden
(`SchemaCompareWiringTest`) und `mcp serve` (`McpServeWiringTest`,
`McpCoreJobWorkerFactoryTest`), und PG-IDENTITY gegen MySQL ist ohne
Präferenz ein Fund, mit ihr keiner (`CompareGenerationProjectionTest`,
`SchemaCompareCommandSemanticsTest`, `SchemaCompareRuntimeSemanticsTest`,
`MysqlSchemaReaderIntegrationTest`). Sabotage F3a–h im Protokoll des vierten
Bauabschnitts.

### P11 — Eine Semantik für `schema compare` in allen drei Oberflächen

**Nachgetragen im dritten Bauabschnitt (Eigner-Entscheidung 2026-09-16).**
(a) `schema_compare_start` bekommt dieselbe Faltung und dieselbe
Generations-Projektion wie `schema_compare`; was der Job veröffentlicht, ist zu
prüfen. (b) **Beide** MCP-Wege entfernen die Reverse-Markierung wie die CLI —
bis dahin ergab jedes Paar zweier Reverses aus verschiedenen Dialekten über MCP
`SCHEMA_NAME_CHANGED` (vom Verifier gemessen).

**Gebaut.**
- **Eine Stelle:** `SchemaCompareSemantics` (`:hexagon:application`) —
  `side(schema)` entfernt die Markierung und liest den Dialekt daraus,
  `compare(source, target)` baut den Comparator (Faltung,
  Generations-Projektion), `undecided` liefert `W137`. Die CLI-Verdrahtung,
  `McpRuntimeRegistries` und `McpCoreJobWorkerFactory` verweisen alle auf
  `SchemaCompareSemantics::compare`; vorher standen dort drei eigene
  Comparator-Aufrufe, von denen einer (der Job) wortgleich verglich. Der
  CLI-Runner behält seinen Operand-Normalizer (Exit 7 mit der Referenz des
  Operanden) und nimmt `W137` aus derselben Quelle.
- **MCP:** `SchemaCompareOutcome` (Status und ungekürzte Funde samt `W137`)
  trägt Werkzeug und Job. `schema_compare` entfernt die Markierung; eine
  unvollständige Markierung wird ein `VALIDATION_ERROR` an
  `left.schemaRef`/`right.schemaRef` (CLI: Exit 7).
- **Was der Job veröffentlichte — und die Entscheidung dazu.** Der Job schrieb
  `gson.toJson(SchemaDiff)`: den internen Vergleichsbaum mit Kotlin-Feldnamen
  (`tablesAdded` …) und Typwerten ohne Diskriminator; in `spec/` stand dazu
  nichts. Entschieden im Bau: P1 und P2b gelten dort. Das Artefakt ist jetzt
  ein Objekt `{status, summary, findings}` mit denselben Einträgen wie die
  Antwort von `schema_compare`, ungekürzt — „eine Semantik" heißt auch, dass
  ein Abnehmer beider Wege dieselben Funde liest, und der rohe Baum war genau
  die interne Darstellung, die Paket E aus den `details` entfernt hat. Dafür
  ist `SchemaCompareJobWorker` im Ergebnistyp generisch (`<R : Any>`) — eine
  geteilte Signatur, gebaut einmal ohne `MODULES`. **Seit dem vierten
  Bauabschnitt (F1)** unter der eigenen Art `COMPARE` statt `diff`, in
  derselben Form wie das Überlauf-Artefakt von `schema_compare`, und der
  Publisher ist über `R` typisiert (I1).
- **Vertrag:** `spec/mcp-server.md` (beide Wege, Markierung,
  `VALIDATION_ERROR`, Form des Artefakts), CHANGELOG (Vertragswechsel für
  Abnehmer des Artefakts).

**Nicht Teil von P11:** MCP `schema_compare` validiert die Schemata nicht, die
CLI endet bei `E012` mit Exit 3 — offene Frage (s. „Offen").

**Der ADR sagt dazu noch „nicht entschieden".** ADR 0056 hält unter
„Konsequenzen" fest, ob `schema_compare_start` zum Geltungsbereich gehört,
entscheide er nicht. Das hat jetzt der Eigner entschieden, und die Spec trägt
es; der ADR ist eingefroren und bleibt unverändert. Ob die Erweiterung einen
eigenen ADR braucht, ist eine Architektur-Frage (s. „Offen").

**DoD — erfüllt:** In allen drei Oberflächen ist ein Paar mit reiner
Schreibweise-Differenz (Cast, Quoting, Klammern) identisch, eine echte
Änderung ein Fund; zwei Reverses aus verschiedenen Dialekten ergeben weder
einen Namens- noch (P6, P10) einen Identity-Fund (der P10-Teil gilt seit dem
vierten Bauabschnitt nur mit der Präferenz `identity`); zwei handgeschriebene
Schemata bleiben streng; der Job trägt `W137` wie das Werkzeug. Gepinnt an den
echten Verdrahtungen (`SchemaCompareCommandSemanticsTest`: der Befehl;
`SchemaCompareRuntimeSemanticsTest`: die Registry und die Job-Fabrik);
Sabotage W1–W3, CENTRAL und P11b rot.

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
[`compare-falsch-positive-cross-dialekt.md`](compare-falsch-positive-cross-dialekt.md)
(Abschnitt „Was ein Schnitt braeuchte") und verlangt
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
vergleicht). `CHANGELOG.md` führt beides unter `[Unreleased]`. **Korrektur
(zweiter Bauabschnitt, Review):** die Aussage „`docs/user/` ist nicht
betroffen" war falsch. Das Anwenderhandbuch sagte im Abschnitt zu
wiederholt geplanten Sichten und CHECKs, `schema compare` behandle „einen
Textunterschied weiterhin als Unterschied" — seit 1.7.0 für CHECK und Sicht,
seit P5 auch für das Index-Prädikat nicht mehr wahr. Die Stelle beschreibt
jetzt den Ist-Zustand (keine Herkunft, kein Sandkasten, aber die
Dialekt-Schreibweise ist gleichgesetzt).

**DoD:**
1. `ADR 0053` trägt `status: superseded by ADR-00NN`; der neue ADR nennt die
   übersteuerte Entscheidung und die neue Grenze und steht in
   [`docs/adr/README.md`](../../adr/README.md).
2. `spec/cli-spec.md` nennt die erweiterte Faltungsmenge (P3/P5) **und** das
   Pfad-Schema (P2b); die verbliebenen Grenzfragen stehen dort als solche.
3. `make docs-check` und `make doc-immutable RANGE=origin/main..HEAD` grün.
   **Und das ist falsifizierbar:** wer 0053 ohne Statusänderung im Kern anfasst,
   macht `doc-immutable` rot; ein bloss danebengestellter ADR lässt beide Gates
   grün und ist damit **nicht** die Erfüllung dieses DoD. **Eingeschränkt
   (Verifikation Runde 4):** das gilt nur für eine Range, deren Basis 0053 noch
   als `accepted` führt. d-check friert `superseded`-ADRs nicht ein; eine
   spätere Kernänderung an 0053 fiele keinem Gate auf (s. „Offen").

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
**Korrektur (dritter Bauabschnitt, Review L1):** „hinter Komma oder
Schluesselwort" war zu weit. `level` und `zone` sind in PostgreSQL nicht
reserviert (`level [1]` ist ein Index), und hinter `[` oder `,` oeffnet `[`
in PostgreSQL ein geschachteltes Array (`ARRAY[[1,2],[3,4]]`) — beides galt
als Beleg fuer T-SQL. Jetzt belegt nur ein `[` hinter einem in PostgreSQL
**reservierten** Wort (Kategorien R und T von `pg_get_keywords()`), hinter
einem Operator, `(` oder `.` Quoting; hinter `[` und `,` ist es zweideutig.
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

**E — MCP-`details` in der Schreibweise des Dokuments (Review L4).** Die
Werte gingen durch Kotlins `toString()` (`AFTER`, `[INSERT, UPDATE]`, `ENUM`,
`DEFINER`, `Text(maxLength=254)`). Gebaut: `CompareValueText`
(`:hexagon:application`) — Aufzaehlungswerte klein wie im Dokument, Listen als
`[a, b]` (Trigger-Ereignisse in Dokument-Reihenfolge), die strukturierten
Spaltenwerte in der Kurzform, die der CLI-Bericht schon trug. Die CLI
delegiert ihre bisherigen Helfer dorthin; **dabei angeglichen:** die Aktionen
eines Spalten-Fremdschluessels heissen im CLI-Bericht jetzt `on_delete=`
(vorher `onDelete=`, anders als die Constraint-Kurzform), und `kind:` eines
geaenderten Typs steht klein. Die Meldungstexte der MCP-Funde nutzen dieselben
Formen. Format in `spec/mcp-server.md`. Tests: `CompareValueTextTest`, drei
neue Faelle in `SchemaCompareFindingDetailsTest` (darunter: kein Fund traegt
eine Kotlin-Darstellung).

**F — Pfad-Grammatik (Review L5).** Ein unbenannter Index stand im Pfad mit
`IndexColumn.toString()` — `expr:`-Praefix, rohes SQL mit Punkt, ` DESC`,
`(n)` —, waehrend die Spec „Schluessel kommagetrennt" sagte; der Punkt brach
das Muster aus `SchemaCompareFindingPathTest`. Festgelegt (Code und Spec):
der Abschnitt ist `IndexDefinition.keyLabels` kommagetrennt — Spaltennamen
wortgleich, ein Ausdruck als Bezeichner-Kurzform (`lower_t_email`), ohne
Richtung und Praefix; gebaut in `SchemaFindingPath.indexSegment`. Ein Ort,
keine Identitaet: zwei unbenannte Indizes mit denselben Schluesseln teilen
ihn, `details` trennt sie. `.expression` steht in der Grammatik nur noch unter
`generation`. Test mit Ausdrucks-Schluessel im nicht-trivialen Vergleich.

**Sabotage E/F** (`make docker-test MODULES=":adapters:driving:mcp"`, ein
Lauf): `beforeAfter` wieder ueber `toString()` → drei Faelle der
Dokument-Schreibweise rot; Index-Abschnitt wieder ueber
`columns.joinToString` → „every path follows the one schema" und der
Ausdrucks-Fall rot (5 von 1226); Ruecknahme bestaetigt.

**G — Absicherung (Verifikation 1, Review L6).**

- **AK 4 an der echten Stelle.** Die Sabotage `canonicalizeRawExpressions =
  true` in `SchemaMigrateWiring` blieb im ersten Bauabschnitt gruen (1039
  Tests) — kein Test sah die Comparatoren, die der Befehl verdrahtet. Sie
  stehen jetzt in `SchemaMigrateComparators` (die Verdrahtung verweist
  darauf), und `SchemaMigrateComparatorsTest` prueft sie zweifach: **durch
  den Befehl** (`schema migrate` Datei gegen Datei, PostgreSQL: ein CHECK und
  ein Index-Praedikat, die `schema compare` gleichsetzt, erscheinen im Plan;
  die Kontrolle mit derselben Datei plant nichts) und **am Objekt** (beide
  Comparatoren melden eine reine Schreibweise-Differenz, der strikte auch
  einen abweichenden Identity-Sequenznamen — keine Erzeugungs-Projektion von
  `schema compare`). Das Testpaar ist bewusst kein Wertevorrat: `status = 'x'`
  faltet der ziel-bewusste Vergleich als Enum-CHECK (ADR 0055), zu Recht.
- **Vollstaendigkeit `ObjectDiffFields`.** `ObjectDiffFieldsCompletenessTest`
  zaehlt per Java-Reflection (ohne `kotlin-reflect`) die `ValueChange`-Felder
  der sechs Diff-Klassen und verlangt je Feld einen Dokument-Schluessel und
  einen Fund; dasselbe fuer `ColumnDiff` und `TableDiff`. Bei `TableDiff` ist
  `partitioning` als **bekannte Luecke** ausgenommen (s. „Offen") und
  zusaetzlich gepinnt — kommt ein Fund dazu, faellt der Test auf.
- **P2b-DoD mit dem echten Comparator.** `SchemaCompareFindingPathTest` stellt
  zwei Schemata, die sich in jeder meldbaren Art unterscheiden, durch
  `SchemaComparator(canonicalizeRawExpressions = true)` und die
  `W137`-Diagnose und verlangt dieselbe Grammatik und die Feld-Enden. Nicht
  darunter: `TABLE_COLUMN_UNIQUE_*` und `TABLE_COLUMN_REFERENCES_CHANGED` —
  der Comparator fuehrt einspaltiges UNIQUE und einspaltige Fremdschluessel
  als Constraint; diese Codes entstehen nur aus einem handgebauten Diff.

**Sabotage G** (`make docker-test MODULES=":adapters:driving:cli
:adapters:driving:mcp"`, ein Lauf): S9 wiederholt (Faltung in beiden
Migrate-Comparatoren) → der Befehls- und der Objekttest rot; die
Erzeugungs-Projektion im strikten Comparator → der Sequenzname-Fall rot; ein
Feld aus `ObjectDiffFields` (`cache`) und der Metadaten-Fund entfernt → die
drei Vollstaendigkeitsfaelle rot; der Spalten-Pfad falsch gebaut → auch die
beiden Faelle mit echtem Comparator rot (16 von 2278); Ruecknahme bestaetigt.

**I — Doku und Plan.** Das Anwenderhandbuch beschreibt `schema compare`
jetzt im Ist-Zustand (s. P7, Korrektur); Status-Kopf, P6-DoD, „Offen" und die
README-Zeile in `in-progress/` sind nachgezogen; der CHANGELOG beschreibt A,
B, C, E und F. Der Verifier-Hinweis zum Backslash ist am echten MySQL
bestaetigt (s. „Konsumenten-Repro") und steht als Notiz beim Reader-Slice
(C1/P6).

**Gates des zweiten Bauabschnitts:** `make docker-check` je betroffenem
Modul und einmal **ohne** `MODULES` (alle Integrationsmodule kompiliert,
12 110 Tests, 0 Fehler), `make integration
INTEGRATION_TASKS=":test:e2e-cli:test"` (mit `-PintegrationTests`, gruen),
`make docs-check`, `make solid-suppression-gate` vor jedem Commit,
`make doc-immutable RANGE=origin/main..HEAD` (kein ADR im Bereich).

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

### Konsumenten-Repro (Verifikation 3) und Pagila-Baseline (Verifikation 4)

> **Stand zweiter Bauabschnitt, mit nachgebautem Schema und nur über die CLI.**
> Der Konsument misst über MCP und mit seinem eigenen Schema (Verifier-Befund
> M2); die Wiederholung damit steht im dritten Bauabschnitt unter
> „Konsumenten-Repro mit dem Schema des Konsumenten". Die Tabelle hier bleibt
> als Messung des zweiten Stands stehen.

Das Schema des Konsumenten liegt nicht im Repo. Nachgebaut ist eines mit genau
den gemeldeten Konstrukten: `ck_order_ship_after_place` (`OR`/`IS NULL`),
`ck_customer_email_shape` (`LIKE`), ein CHECK mit Werteliste auf einer
`varchar`-Spalte, `ix_order_open` mit Prädikat, Identity-Spalten (eine davon
als PostgreSQL-`serial`, `customer.id`), `order_item.line_total` berechnet und
ein `numeric`-CHECK `> 0`.

<details><summary>Schema <code>source.yaml</code></summary>

```yaml
schema_format: "1.0"
name: consumer_repro
version: 1.0.0
tables:
  customer:
    columns:
      # bigserial: PostgreSQL fuehrt die Spalte als Serial (legacy_serial_syntax)
      id:
        type: biginteger
        required: true
        generation: { type: identity, mode: by_default, legacy_serial_syntax: true }
      email: { type: text, max_length: 254, required: true, unique: true, unique_constraint: uq_customer_email }
    primary_key: [id]
    constraints:
      - name: ck_customer_email_shape
        type: check
        expression: "email LIKE '%@%'"
  order:
    columns:
      id:
        type: biginteger
        required: true
        generation: { type: identity, mode: by_default }
      customer_id: { type: biginteger, required: true }
      status: { type: text, max_length: 20, required: true }
      placed_at: { type: datetime, required: true }
      shipped_at: { type: datetime }
    primary_key: [id]
    indices:
      - name: ix_order_open
        columns: [status]
        where: "status IN ('NEW', 'PAID')"
    constraints:
      - name: fk_order_customer
        type: foreign_key
        columns: [customer_id]
        references: { table: customer, columns: [id] }
      - name: ck_order_status
        type: check
        expression: "status IN ('NEW', 'PAID', 'SHIPPED', 'CANCELLED')"
      - name: ck_order_ship_after_place
        type: check
        expression: "shipped_at IS NULL OR shipped_at >= placed_at"
  order_item:
    columns:
      id:
        type: biginteger
        required: true
        generation: { type: identity, mode: by_default }
      order_id: { type: biginteger, required: true }
      quantity: { type: integer, required: true }
      unit_price: { type: decimal, precision: 12, scale: 2, required: true }
      line_total:
        type: decimal
        precision: 14
        scale: 2
        generation:
          type: computed
          expression: "quantity * unit_price"
          stored: true
    primary_key: [id]
    constraints:
      - name: fk_order_item_order
        type: foreign_key
        columns: [order_id]
        references: { table: order, columns: [id] }
      - name: ck_order_item_quantity
        type: check
        expression: "quantity > 0"
      - name: ck_order_item_price
        type: check
        expression: "unit_price > 0"
```

</details>

**Ablauf** (Stand `522722ad3`, Image `make docker-build`, Server aus
`make mcp-e2e-up`: PostgreSQL 18.6, MySQL 9.7.2, SQL Server 2025, SQLite 3.45
als Datei). Aus `examples/mcp-e2e/`, mit `R` als Arbeitsverzeichnis, das
`source.yaml` enthaelt, und den Variablen aus `.env`:

```bash
set -a; . ./.env; set +a; export MCP_E2E_DMIGRATE_USER="$(id -u):$(id -g)"
dmi() { docker compose run --rm -T -v "$R:/repro" dmigrate --config /work/.d-migrate.yaml "$@"; }
# je Dialekt d und Verbindung c: (postgresql, mcp_e2e_pg) (mysql, mcp_e2e_my)
# (mssql, mcp_e2e_ms) (sqlite, mcp_e2e_sqlite)
dmi schema generate --source /repro/source.yaml --target "$d" --output "/repro/generated_$d.sql" --deterministic
#   Ziel leeren und generated_$d.sql mit dem Client des Dialekts anwenden —
#   wie in scripts/smoke-cross-dialect-roundtrip.sh (psql, mysql, sqlcmd, sqlite3)
dmi schema reverse --source "$c" --output "/repro/reversed_$d.yaml"
# je Paar (a, b): postgresql/mssql, postgresql/mysql, mssql/mysql, postgresql/sqlite
dmi schema compare --source "file:/repro/reversed_$a.yaml" --target "file:/repro/reversed_$b.yaml"
docker run --rm -v "$R:/repro" ghcr.io/pt9912/d-migrate:1.7.1 schema compare \
  --source "file:/repro/reversed_$a.yaml" --target "file:/repro/reversed_$b.yaml"
```

**Die MySQL-Beine sind ohne den Reader-Fix nicht vergleichbar.** MySQL legt die
CHECKs mit dem Zeichensatz der Sitzung als Introducer und mit
Backslash-Escape ab (`CHECK_CLAUSE` = `` (`email` like _latin1\'%@%\') ``,
Hex `…5C27…`); der Reverse übernimmt beides, und `schema compare` endet für
PG↔MySQL und MSSQL↔MySQL mit **Exit 3** (`E012`, `_latin1` als Spalte) — in
1.7.1 genauso. Das ist Posten 4 (Reader-Slice C1/P6). Um den Comparator
trotzdem auf diesen Beinen zu messen, sind im MySQL-Reverse Introducer und
`\'` per Skript entfernt (**simuliert**, nicht gebaut: YAML laden, in jedem
Text `_<zeichensatz>\'` und `\'` durch `'` ersetzen, schreiben). Der
Befund zum Backslash steht als Notiz beim Reader-Slice (P6): ohne das
Aufloesen von `\'` zoege sich die Faltung für jeden solchen CHECK zurück.

**Ergebnis** — je Paar die Funde an CHECK, Index und Identity; „1.7.1" und
„jetzt" auf denselben Reverse-Artefakten:

| Paar | Fund | 1.7.1 | jetzt | Zuordnung |
| ---- | ---- | ----- | ----- | --------- |
| PG↔MSSQL | `ck_order_ship_after_place` | Fund | — | **behoben** (P3) |
| PG↔MSSQL | `ck_customer_email_shape` `((email)::text ~~ '%@%'::text)` gegen `email like '%@%'` | — | — | gleich; nach P8 allein waere es ein Fund gewesen — P9 haelt es gleich |
| PG↔MSSQL | `ck_order_item_price` `(unit_price > (0)::numeric)` gegen `unit_price>(0)` | — | — | gleich (P9, `numeric`) |
| PG↔MSSQL | `ix_order_open` `= ANY (…)` gegen `IN (…)` | Fund ohne Werte | Fund mit beiden Prädikaten | **bewusst** (ADR 0055); P1 zeigt die Werte |
| PG↔MSSQL | `ck_order_status` `= ANY (…)` gegen `OR`-Kette | Fund ohne Werte | Fund mit Werten | **bewusst** (ADR 0055) |
| PG↔MSSQL | drei Identity-Spalten, `by_default` gegen `always` | Fund | Fund | **bewusst** (P6: der Modus bleibt); **neu** gegenüber der Meldung: SQL Server kennt kein `BY DEFAULT` (`W140`) — s. „Offen" |
| PG↔MSSQL | `line_total` Typ `decimal(14,2)` gegen `decimal(23,2)` | Fund | Fund | **neu**, nicht dieser Slice (SQL Server leitet den Typ ab) — s. „Offen" |
| PG↔MSSQL | `W137` an `line_total` | Diagnose, Pfad `order_item.line_total` | Diagnose, Pfad im Pfad-Schema | P2a |
| PG↔MySQL | alle | Exit 3 (`E012`) | Exit 3 (`E012`) | Posten 4, Reader-Slice C1/P6 |
| PG↔MySQL* | `customer.id` (PG `serial`), Sequenzname | Fund | — | **behoben** (P6) |
| PG↔MySQL* | `order.id`, `order_item.id` (PG `IDENTITY`) | Fund | Fund | **nicht neu** (Korrektur, Verifier M1): 1.7.1 meldete Name **und** Flag im selben Fund; P6 blendet nur den Namen aus, `legacy_serial_syntax` `false` gegen `true` blieb — seit dem vierten Bauabschnitt mit der Präferenz `identity` geschlossen (P10) |
| PG↔MySQL* | `ck_customer_email_shape` | — | — | gleich (P9 + simulierter Reader-Fix) |
| PG↔MySQL* | `ck_order_ship_after_place` | Fund ohne Werte | Fund mit Werten | **bewusst**: Schlüsselwort-Case (`is null`/`or`), die Klammern faltet P3 |
| PG↔MySQL* | `ck_order_status` `= ANY` gegen `IN` | Fund | Fund | **bewusst** (ADR 0055) |
| PG↔MySQL* | `ix_order_open` entfernt | Fund | Fund | **bewusst**: MySQL kennt kein Teilindex-Prädikat (`generate`: `E057`) |
| MSSQL↔MySQL | alle | Exit 3 (`E012`) | Exit 3 (`E012`) | Posten 4 |
| MSSQL↔MySQL* | drei Identity-Spalten, `always` gegen `by_default` | Fund | Fund | **bewusst** (Modus) / **neu** (`W140`) |
| MSSQL↔MySQL* | `ck_order_ship_after_place` | Fund | Fund | **bewusst**: Schlüsselwort-Case |
| MSSQL↔MySQL* | `ck_order_status` `OR`-Kette gegen `IN` | Fund | Fund | **bewusst** (ADR 0055) |
| MSSQL↔MySQL* | `ix_order_open` entfernt | Fund | Fund | **bewusst** (`E057`) |
| MSSQL↔MySQL* | `line_total` Typ | Fund | Fund | **neu**, nicht dieser Slice |
| PG↔SQLite | `ck_order_ship_after_place` | Fund | — | **behoben** (P3) |
| PG↔SQLite | `ck_customer_email_shape` `~~` gegen `LIKE` | Fund ohne Werte | Fund mit Werten | **bewusst**: Schlüsselwort-Case (`like` gegen `LIKE`) |
| PG↔SQLite | `ix_order_open`, `ck_order_status` | Fund | Fund | **bewusst** (ADR 0055) |
| PG↔SQLite | Spaltentypen, Identity als `identifier(auto)` | Fund | Fund | Nullfall: SQLites Typaffinität, nicht dieser Slice |

`*` = mit simuliertem Reader-Fix. **AK 1 im echten Repro (Stand zweiter
Bauabschnitt):** Posten 6 schloss für `serial`-Spalten, nicht für `IDENTITY`
(seit dem vierten Bauabschnitt mit der Präferenz `identity` auch dort); Posten 3 schließt
sein PG↔MSSQL-Bein (und das PG↔SQLite-Bein); Posten 5 zeigt keine reine
Schreibweise-Differenz mehr — was bleibt, ist die Umschreibung (ADR 0055) bzw.
das fehlende Prädikat auf MySQL; Posten 1 nennt Vorher und Nachher, Posten 2
folgt dem Pfad-Schema. Die MySQL-Beine setzen den Reader-Fix voraus.

**Verifikation 4 — `make sample-db-smoke`** (nach allen Aenderungen, Image von
`522722ad3`): Pagila PG→PG, 22 Tabellen, Zeilenzahlen gleich,
`schema compare` gleich der gepinnten Baseline — **`Status: IDENTICAL`**.
**Beim Lauf gefunden, nicht aus diesem Slice:** der Smoke startete gar nicht.
`examples/sample-db/docker-compose.yml` mountete das Volume fuer
PostgreSQL 18.6 noch auf `/var/lib/postgresql/data`; das 18er-Image bricht
damit ab — auch mit leerem Volume („there appears to be PostgreSQL data in
/var/lib/postgresql/data (unused mount/volume)"). `examples/mcp-e2e` hatte
denselben Fix schon. Korrigiert ist nur der `postgres`-Dienst (gemessen);
`postgis` (`postgis/postgis:18-3.6`) mountet genauso und trifft vermutlich
dasselbe, ist aber nicht gefahren worden (s. „Offen").

### Dritter Bauabschnitt — Eigner-Entscheidungen, Review und Verifikation, Runde 2 (2026-09-17)

Grundlage: vier Eigner-Entscheidungen vom 2026-09-16, ein zweites Review
(gemessen gegen PostgreSQL 16.15/18.6 und die gebauten Klassen) und eine zweite
Verifikation (Sabotage R1–R7, Repro über MCP). Gebaut sind P10 und P11 (oben)
und die Befunde unten; Commits `175800393` (Faltung), `b3e583522` (P10),
`2601d1631` (P11), `ff4d56082` (MCP-Funde), `f8c819f6c` (Handbuch),
`47f8a8641` (Sample-DB).

**Eigner-Entscheidungen.** P10 und P11 sind gebaut (s. dort). **Der
Identity-Modus gegen SQL Server bleibt ein Fund** — ein
Fähigkeitsunterschied (SQL Server kennt kein `BY DEFAULT`, `W140`); die Grenze
steht in `spec/cli-spec.md`. **Der Typ einer berechneten Spalte in SQL Server**
wandert in den Reader-Slice (s. „Offen"); hier nicht gebaut.

**Review-Befunde (Runde 2).**
- **H1 — Gleitkomma-Literal-Cast:** korrigiert, nachgemessen und Ende-zu-Ende
  nachgestellt (die Sonde des Reviews, `numeric` gegen `double precision`:
  vorher `IDENTICAL`, jetzt `DIFFERENT` wie 1.7.1). Die Passage „Grenze bei
  verlustbehafteten Readern" in P9 ist korrigiert.
- **M1 — R301-Rückfall:** korrigiert. Das Modell unterscheidet `varchar` ohne
  Länge nicht von `text` (beide `text` ohne `max_length`); die engste Regel,
  die `(status)::text` bei `varchar(n)` weiter schließt, ist „nur mit
  Längenangabe" (`text(n)`, `email`). Sonde `inet` gegen `text`: jetzt
  `DIFFERENT` — **auch 1.7.1 setzte das Paar gleich** (es strich jeden Cast).
- **L1 — `[`-Heuristik:** korrigiert (s. B, Korrektur). Die Wortliste
  „kein Index möglich" ist die der in PostgreSQL reservierten Wörter, für
  jedes Wort gegen PostgreSQL 18.6 gemessen (`select <wort> [1]`); `[` hinter
  `[`/`,` ist zweideutig. `FROM [orders] [o]` bleibt gleich (gepinnt).
- **L2 — MCP-Listen:** Sichten-Spalten als `[a, b]`, eine leere Seite als
  `[]` (der Sonderzweig mit „ohne Werte" ist weg).
- **L3 — Handbuch:** 3.4 beschreibt jetzt aufgabenorientiert, was bei zwei
  Reverses nicht gemeldet wird (Schreibweise, Sequenzname, serial-Flag,
  Markierung) und was bleibt; im Migrate-Abschnitt stehen die Semikola.
- **INFO 1 / Verifier S6:** gepinnt — verschiedene Spaltenmengen und -typen
  links/rechts am Comparator (`ColumnCastFoldTest`, „Die Spaltentypen dieser
  Seite").
- **INFO 3:** der Vollständigkeitstest prüft `ColumnDiff` und `TableDiff` je
  Feld mit Pfad, samt den listen- und map-wertigen Feldern.
- **INFO 4:** der CHANGELOG-Satz zu `on_delete=` am Spalten-Fremdschlüssel
  ist gestrichen (unsichtbar: einspaltige FKs laufen als Constraint);
  `CompareValueText` — KDoc ehrlich gemacht (Maps und andere Objekte bleiben
  `toString()`, die Felder gehen ohne `details` raus). Keine neue Form für
  Maps: deren Werte wären wieder Kotlin-Objekte.
- **INFO 5 (umgesetzt, ohne Verhaltensänderung):** Vergleichsart als Enum;
  Vergleichsoperatoren (`SqlLexis`) und Junktoren (`SqlKeywords`) aus einer
  Quelle für `ColumnCasts`, `CastOperands`, `SqlScopes`, `OperandParens`.
  `SqlKeywords.precedesOperand` bleibt eigene Liste (andere Frage).
- **INFO 6:** `' '::"char"` und `trim("both" from x)` sind Funde. Über den
  Befund hinaus: jede Quotierung hinter `::` bleibt stehen, `"char"`/`"bit"`
  bleiben überall quotiert (auch in `CAST(… AS "char")`; gemessen:
  `'101'::bit` ist `1`, `'101'::"bit"` bleibt `101`), `for`/`placing` sind
  Schlüsselwörter.
- **INFO 9:** `spec/cli-spec.md` beschreibt jetzt jeden Vergleich mit
  `ANY`/`SOME`/`ALL` wie der Code (gegen PG 18.6 gemessen: `<> ALL` und
  `~~ ANY` tragen dieselben `::text`-Elemente).
- **`postgis`-Mount:** korrigiert (`47f8a8641`), `make sample-db-spatial-smoke`
  grün (s. unten).

**Verifier-Befunde (Runde 2).**
- **M1:** mit P10 erledigt; AK 1 ist ehrlich formuliert, das Etikett „neu" in
  der Tabelle des zweiten Bauabschnitts korrigiert.
- **M2:** Repro wiederholt — mit dem Schema des Konsumenten, über MCP (beide
  Werkzeuge) und die CLI (unten).
- **M3:** gepinnt an den echten Verdrahtungen (`SchemaCompareCommandSemanticsTest`,
  `SchemaCompareRuntimeSemanticsTest`, auch für den Job); W1/W2 wiederholt,
  dazu W3 und die zentrale Stelle — rot.
- **L1:** Identifier-Ausnahme, `::character` gegen `char(n)` und
  links/rechts verschiedene Spaltentypen gepinnt; S6, S12, S13, S14
  wiederholt — rot.
- **L2:** Status-Kopf, P6 und „Offen" sind nachgezogen (Zeilenverweise durch
  Namen ersetzt, Hashes ergänzt).
- **L4:** `spec/cli-spec.md` nennt „exakte Zahltypen" und die
  Identifier-Sonderregel.
- **S18:** `kind:` klein jetzt auch durch den CLI-Befehl gepinnt.

**Sabotage-Protokoll dritter Bauabschnitt.** Die Verifier-Läufe S6/S12/S13/S14
sind hier nach dem Befund definiert (die Logs der Verifikation nennen die
Eingriffe nicht): S6 vertauscht die Seiten der Spaltentypen, S14 nimmt für
beide Seiten das Ist, S12 macht `identifier` zur `integer`-Breite (a) bzw.
lässt das String-Literal an ihm zu (b), S13 nimmt `character`/`char` unter die
`bpchar`-Typen. W1/W2 („`canonicalizeRawExpressions = false` an der
Verdrahtung") gibt es seit P11 an der Verdrahtung nicht mehr; wiederholt ist
der gleichwertige Eingriff — ein strikter Comparator an der Verdrahtung —,
dazu dieselbe Abschaltung an der einen Stelle (CENTRAL). Jeder Lauf mit
Rücknahme per Archiv und Prüfsumme; die Mehrmodul-Läufe mit `--continue`
direkt über `docker build --target build` (das Make-Target kennt die Option
nicht).

| Lauf | Sabotage | rot (Auswahl, alle erwartet) |
| ---- | -------- | ---- |
| C1 (`:hexagon:core`, 7 von 1483) | H1 zurück | „a decimal literal cast to double precision" |
| C1 | L1a: Schlüsselwort statt reserviert | „a word PostgreSQL does not reserve can be a column" |
| C1 | I6a: `::`-Regel aus | „a quoted type name after `::`" |
| C1 | S12a: `identifier` als `integer` | „an identifier is not narrower than bigint" |
| C1 | S13: `character`/`char` als `bpchar` | „character without a length is character(1)" |
| C1 | S6: Seiten vertauscht | „the cast is decided with the column set/type of its own side" (2) |
| C2 (`:hexagon:core`, 7 von 1483) | M1 zurück | „a column cast to text on a text column without a length" |
| C2 | L1b: `[`/`,` als Beleg | „a bracket after `[` or `,` is a nested array" |
| C2 | I6b: `char`/`bit` entpackt | „`char` and `bit` stay quoted everywhere" |
| C2 | I6c: trim-Wörter entfernt | „the argument words of trim and overlay" |
| C2 | S12b: String-Literal am `identifier` | „an identifier is not narrower than bigint" |
| C2 | S14: beide Seiten Ist | „… of its own side" (2) |
| X1 (cli, 4 von 1050) | W1: strikter Comparator in `SchemaCompareWiring` | Befehl: Schreibweise, P6/P10; `SchemaCompareWiringTest` |
| X1 | S18: `kind:` über `toString()` | „the kind of a changed custom type stands lowercase" |
| X1b (mcp, 6 von 1242) | W3: strikter Comparator in der Job-Fabrik | Laufzeit: Schreibweise, zwei Reverses (Oberfläche Job) |
| X1b | L2: alter Sonderzweig | zwei Sichten-Fälle in `SchemaCompareHandlerTest` |
| X1b | I3: Index-Pfad falsch | Vollständigkeit je Feld; ein Pfad-Fall |
| X2 (app 5, mcp 3, cli 1) | W2: strikter Comparator in `McpRuntimeRegistries` | `SchemaCompareRuntimeIdentityTest`; Laufzeit: Schreibweise (Werkzeug) |
| X2 | P10a: Serial-Dialekt nie | vier P10-Fälle; CLI P6/P10 |
| X2 | P10c: Serial-Teil im Migrate-Seam | „migrate's seam … keep the flag" |
| X3 (app 4, mcp 3, cli 2) | CENTRAL: Faltung in `SchemaCompareSemantics` aus | Schreibweise in CLI und beiden MCP-Oberflächen |
| X3 | P11b: Markierung nicht entfernt | „a half marker is rejected"; zwei Reverses |
| X3 | P10b: MySQL-Fähigkeit `true` | „only PostgreSQL's reverse tells the two apart"; Paarungsfälle |

Der erste X1-Lauf erreichte die MCP-Tests nicht (eine Sabotage-Zeile war
länger als Detekt erlaubt); X1b wiederholt sie. Nach allen Läufen gilt die
Prüfsumme des Arbeitsstands; der volle Bau danach ist grün.

**Gates dritter Bauabschnitt:** `make docker-check` für `:hexagon:core` (1483
Tests) und für ports-common, application, cli, mcp und die fünf Treiber; einmal
**ohne** `MODULES` (alle Integrationsmodule kompiliert, 12 149 Tests, 0
Fehler); `make integration INTEGRATION_TASKS=":test:e2e-cli:test"` (mit
`-PintegrationTests`, gelaufen, grün — deckt den Job-Pfad
`schema_compare_start` durch den MCP-Client); `make docs-check` (330 Dateien,
0 Befunde); `make solid-suppression-gate` vor jedem Commit;
`make doc-immutable RANGE=origin/main..HEAD` (vor der Übergabe gelaufen,
0 Befunde).

#### Konsumenten-Repro mit dem Schema des Konsumenten (Verifier M2)

**Schema:** `roundtrip-repro-postgres.sql` aus dem Verzeichnis `scripts` des
Konsumenten-Repos (nur gelesen; Obermenge von dessen `repro_schema.sql` um die
Typ-Tabelle `type_probe` mit zwei Geometriespalten). **Weg wie beim
Konsumenten:** PostgreSQL 18.6 mit PostGIS 3.6 im eigenen Schema `postgis`
(wie dessen `postgres-init/01-postgis.sh`; in `public` läse der MCP-Reverse
~1000 PostGIS-Funktionen mit) seeden → zurücklesen → für MySQL 9.7.2, SQL
Server 2025 und SQLite (SpatiaLite) erzeugen → nativ anwenden (SQLite im
Werkzeug-Image des Konsumenten) → zurücklesen → paarweise vergleichen. Stack:
`make mcp-e2e-up`, der `postgres`-Dienst per Override auf das PostGIS-Image
mit eigenem Volume (danach entfernt), `make mcp-e2e-down`. **Oracle fährt
nicht mit** — der Oracle-Dienst des Harness ist ein fremder Container.
**MCP** über `mcp serve --transport stdio` im gebauten Image
(`schema_reverse_start` → `schema_compare` und `schema_compare_start`),
Vergleich mit 1.7.1 auf denselben Datenbanken; **CLI** über die eigenen
Reverses (`schema reverse` ohne `--include-*` liest keine Sichten und
Routinen — deshalb fehlt dort `VIEW_REMOVED`). Skripte:
`scratchpad/repro3/` der Sitzung.

**MCP — Anzahl der `findings` (Status jeweils `different`):**

| Paar | `schema_compare` 1.7.1 | `schema_compare` jetzt | `schema_compare_start` jetzt | weggefallen |
| ---- | ---: | ---: | ---: | ---- |
| PG↔MSSQL | 23 | 17 | 17, dieselben Einträge | `SCHEMA_NAME_CHANGED` (P11b); fünf Identity-Sequenznamen (P6) |
| PG↔MySQL | 20 | 19 | 19, dieselben Einträge | `SCHEMA_NAME_CHANGED` |
| PG↔SQLite | 36 | 35 | 35, dieselben Einträge | `SCHEMA_NAME_CHANGED` |
| MSSQL↔MySQL | 19 | 18 | 18, dieselben Einträge | `SCHEMA_NAME_CHANGED` |

Der Konsument pinnt für 1.7.1 in seinem Stack 22/20/36 (MSSQL/MySQL/SQLite);
die Abweichung von eins bei MSSQL ist nicht untersucht. `schema_compare_start`
lieferte in 1.7.1 den rohen `SchemaDiff` (je Paar mit dem Namens-Unterschied
in `schemaMetadata` und allen Sequenznamen als Erzeugungs-Änderung); jetzt
`{status, summary, findings}` mit genau den Einträgen von `schema_compare`
(P11a).

**Was bleibt, und warum:**

| Paar | Fund | Zuordnung |
| ---- | ---- | ---- |
| PG↔MySQL, MSSQL↔MySQL | fünf Identity-Spalten, `mode=always` gegen `by_default` | **Modus-Unterschied, bewusst:** der Konsument schreibt `GENERATED ALWAYS`; MySQL kennt nur `AUTO_INCREMENT`, der Generator rendert `ALWAYS` dorthin (ohne Warnung), der Reverse liest `by_default`. Sequenzname und `legacy_serial_syntax` zählen nicht mehr (P6, P10, in `details` weiter sichtbar; Stand dritter Lauf — seit dem vierten zählt das Flag wieder, bis der MySQL-Reverse `identity` liest). Dieselbe Klasse wie `W140` (s. „Offen") |
| PG↔MSSQL | keine Identity-Funde mehr | beide `always`; der Name zählt nicht (P6) |
| PG↔MSSQL, PG↔MySQL, PG↔SQLite | `orders_total_check` entfernt | der Generator rendert `(total >= (0)::numeric)` nicht (`E053`), nicht dieser Slice |
| PG↔MSSQL, MSSQL↔MySQL | `orders_customer_id_fkey` `on_delete=restrict` gegen keine Angabe | Eigner-Frage `RESTRICT` (Abgrenzung) |
| PG↔MSSQL, MSSQL↔MySQL | `ck_orders_status` (Enum als CHECK) | ADR 0055 |
| alle | Typen (`enum`, Arrays, JSON, XML, Zeitzone, Geometrie-Subtyp), `line_total`, `order_summary`, `uq_*` | Fähigkeits- und Generator-Unterschiede, nicht dieser Slice; SQLite: Nullfall |

**CLI (Exit 1 in allen Paaren, jetzt und 1.7.1):** gegenüber 1.7.1 fallen in
PG↔MSSQL die fünf Identity-Zeilen weg (P6); PG↔MySQL und MSSQL↔MySQL behalten
sie wegen des Modus. Sonst ändert sich nur die Kurzform der Constraints (P1).
**Exit 3 (`E012`) tritt mit diesem Schema nicht auf:** der MySQL-Generator
rendert den einzigen CHECK mit Cast nicht (`E053`), die übrigen tragen kein
String-Literal und damit keinen Introducer (`CHECK_CLAUSE` gemessen). Der
simulierte Reader-Fix ändert das MySQL-Reverse deshalb nicht (nur die
YAML-Schreibweise); die MySQL-Beine sind auch ohne ihn vergleichbar.

**P10 an echten Reverses — das Schema des zweiten Bauabschnitts** (Stand
dritter Bauabschnitt; seit der Rücknahme der Faltung gilt das Ergebnis für
`order.id`/`order_item.id` nur noch mit der Präferenz `identity`, s. vierter
Lauf) (Identity
mit `BY DEFAULT`, Reverse-Dateien von damals, neues Image): PG↔MySQL* meldet
statt drei Identity-Spalten (1.7.1) **keine** mehr (`customer.id` über P6,
`order.id`/`order_item.id` über P10); PG↔MSSQL behält sie — dort ist es der
Modus (`W140`).

**Sonden des Reviews (Ende-zu-Ende, CLI):** `numeric` gegen
`double precision` mit `> 0.5` — 1.7.1 `DIFFERENT`, jetzt `DIFFERENT`
(vorher `IDENTICAL`); `inet`/`interval` gegen `text` — 1.7.1 `IDENTICAL`, jetzt
`DIFFERENT`.

**`make sample-db-smoke`** (Image dieses Stands): Pagila PG→PG, 22 Tabellen,
Zeilenzahlen gleich, `schema compare` gleich der Baseline.
**`make sample-db-spatial-smoke`**: grün (VA1–VA4, 5d). Erst nach dem
Mount-Fix: der alte Pfad bricht auch mit frischem Volume ab (gemessen);
zusätzlich trug das vorhandene Volume `sample-db-postgis-data` noch einen
PostgreSQL-16-Cluster an seiner Wurzel, den das 18er-Image verweigert — es ist
neu angelegt.

### Vierter Bauabschnitt — Eigner-Entscheidungen F1–F4, Review und Verifikation, Runde 3 (2026-09-17)

Grundlage: die Antworten des Eigners auf die Fragen F1–F4 des ADR-Entwurfs 0057
(den Entwurf pflegt der Architekt), ein drittes Review und eine dritte
Verifikation (Sabotage und Repro über MCP). Commits `5a9eaf0a2` (F3),
`0e0e1cb24` (M1), `694b88776` (F1, L1, I1, I5, Verifier M1), `e3116c34c`
(L2, L3, I2, I3, I4), `fe6b3d270` (Handbuch: `schema migrate` mit
`identity`), `542008cbd` (MySQL-Integrationsfall ohne
PostgreSQL-Fähigkeiten).

**Eigner-Entscheidungen.**
- **F3 — P10 zurück, stattdessen eine Präferenz.** Gebaut, s. P10.
- **F1 — eigene Artefakt-Art.** `ArtifactKind.COMPARE` (Name nach den
  vorhandenen Arten: ein Substantiv für das Ergebnis). `DIFF` bleibt als
  Filterwert und für gespeicherte oder hochgeladene Artefakte; der Server
  erzeugt es nicht mehr. `artifact_list` kennt `COMPARE` (Tool-Schema,
  Golden per `make golden-update`); `artifact_upload_init` nimmt `COMPARE`
  nicht an. Die Arten stehen in keinem Resource-Schema; die Discovery-Tabelle
  in `spec/mcp-server.md` zählt sie jetzt auf.
- **F2/F4** betreffen den ADR. Aus F4 folgt hier nur der Messauftrag unter
  „Offen".

**Review-Befunde (Runde 3).**
- **M1 — Platzhalter bei „Reverse gegen handgeschriebenes Schema":** behoben.
  `CompareSide.reverseGenerated`; `SchemaCompareSemantics.compare` lässt die
  Metadaten weg, sobald eine Seite die Markierung trägt — für alle drei
  Oberflächen an einer Stelle; der CLI-Runner baut seine Seiten jetzt über
  `SchemaCompareSemantics.side` wie beide MCP-Oberflächen. Die Tests prüfen
  die Werte (`SchemaCompareSemanticsTest` neu; Befehl: kein Platzhalter,
  `name: shop -> shop2`; beide MCP-Oberflächen: kein Fund, kein Platzhalter in
  der Antwort, bei zwei handgeschriebenen Schemata `before`/`after`).
  **Wohin der Platzhalter sonst dringt:** nirgends nach außen —
  `schema migrate` führt ihn nur intern (`DiffEndpoint.schemaName`, nicht
  gerendert), Planer und Fingerabdruck werten `name`/`version` nicht. Die
  alten Ausgaben des MCP-E2E-Harness (`examples/mcp-e2e/out/`, nicht
  versioniert) zeigen ihn noch; sie stammen von 1.7.x.
- **L1 — Überlauf-Artefakt:** eine Form (`{status, summary, findings}`) und
  die Art `COMPARE` für beide Compare-Artefakte. `executionMeta` gehört
  **nicht** hinein: es beschreibt einen Aufruf, der Job hat keinen, und
  dasselbe Ergebnis soll auf beiden Wegen dieselben Bytes ergeben.
  **Beim Bau gefunden:** das Überlauf-Artefakt entstand nur über die
  Byte-Grenze — bei mehr Funden als `maxInlineFindings` und kleiner Antwort
  stand `truncated: true` ohne `diffArtifactRef`, entgegen dem Ausgabeschema,
  und die Funde jenseits der Grenze waren nirgends abrufbar. Jetzt entsteht
  es auch über die Anzahl (`SchemaCompareOverflowArtifactTest`).
- **L2, L3, I3, I4:** Spec, Handbuch und CHANGELOG präzisiert.
- **I1 — `SchemaCompareJobWorker<R>` ohne Typsicherheit:** der Port ist
  generisch (`JobArtifactPublisher<in P>`), der Compare-Worker nimmt einen
  `JobArtifactPublisher<R>`, und die MCP-Seite hat je Job einen typisierten
  Publisher (`McpJobArtifacts`) statt einer Laufzeit-Verzweigung. Die alte
  Probe „Publisher lehnt einen `String` ab" ist damit ein Kompilierfehler
  und als Test entfallen.
- **I2:** in der KDoc von `SqlLexis.isPostgresReserved` festgehalten
  (Liste = PostgreSQL 16/18; `system_user` ist in 14 und 15 ein Name).
- **I5:** `McpOperationalScenarioTest` liest das Job-Artefakt über
  `resources/read` (Art `COMPARE`) und `artifact_chunk_get` (genau die
  Schlüssel `status`, `summary`, `findings`).
- **I6:** P6-Text und der Verweis „(s. Rückgabe)" sind nachgezogen.

**Verifier-Befunde (Runde 3).**
- **M1 — „dieselben Funde, ungekürzt" war nicht gepinnt:**
  `SchemaCompareRuntimeSemanticsTest` vergleicht an einem Paar mit vier
  Funden (jeder mit `details`) die vollen Listen zwischen Werkzeug und Job,
  und das Überlauf-Artefakt des Werkzeugs mit dem Job-Artefakt.
  `findings.take(1)` in `SchemaCompareOutcome.artifact()` ist jetzt rot
  (V1 unten).
- **L1, L2:** mit F3 neu formuliert (AK 1, P10, Spec, Handbuch).
- **Info (CHANGELOG „halbe Markierung"):** nach Werkzeug
  (`VALIDATION_ERROR`) und Job (`FAILED`, `RUNNER_ERROR`) getrennt, ebenso in
  `spec/mcp-server.md`.

**Sabotage-Protokoll vierter Bauabschnitt.** Jeder Lauf mit `--continue`
direkt über `docker build --target build`, Rücknahme per Archiv und
Prüfsumme (alle „restore OK"). Lauf F3-A war nur teilweise aussagekräftig:
zwei Sabotagen ließen einen Parameter ungenutzt, Detekt hielt `driver-mysql`
und `mcp` vor den Tests an; F3a/F3e sind in A2 detektneutral wiederholt.

| Lauf | Sabotage | rot |
| ---- | -------- | --- |
| F3-A (app 2, cli 5; mysql/mcp: Detekt) | F3c: Datei schlägt Flag | `ReverseAutoIncrementSyntaxResolverTest` „a flag beats the config", `SchemaReverseWiringTest` „the flag beats the file" |
| F3-A | F3f: Vergleich faltet das Flag wieder | `CompareGenerationProjectionTest` (2), `SchemaCompareCommandSemanticsTest` „read as serial" |
| F3-A | F3g: `db:`-Operand ohne Präferenz | `SchemaCompareWiringTest` „a db: operand is read with the reverse preferences" |
| F3-A | F3e: Job-Fabrik ohne Präferenz (cli) | `McpServeWiringTest` |
| F3-A2 (mysql 2, mcp 2) | F3a2: MySQL-Mapping behält das Flag unter `identity` | `MysqlTypeMappingTest` „drops the serial flag", `MysqlSchemaReaderTest` (bigint) |
| F3-A2 | F3e2: Job-Fabrik ohne Präferenz | `McpCoreJobWorkerFactoryTest` „reads with the server's declared reverse preferences" |
| F3-A2 | F3f (mcp) | `SchemaCompareRuntimeSemanticsTest` „the serial flag of MySQL's reverse is one" |
| F3-B (sqlite 2, mcp 1, cli 4) | F3b: SQLite setzt das Flag immer | `SqliteTypeMappingTest`, `SqliteSchemaReaderTest`, `McpCoreJobWorkerFactoryTest`, `McpServeWiringTest`, `SchemaCompareWiringTest` |
| F3-B | F3d: Runner liest den Wert des falschen Dialekts | `SchemaReverseWiringTest` (2) |
| F3-C (cli 1) | F3h2: `mcp serve` ohne seine Konfigurationsdatei | `McpServeWiringTest` |
| M1-A (app 3, mcp 1, cli 1) | M1a: Metadaten nicht weggelassen | `SchemaCompareSemanticsTest` (2), beide Oberflächen „no placeholder leaks" |
| M1-A | M1b: Markierung nur mit bekanntem Dialekt | `SchemaCompareSemanticsTest` „a marker with a dialect this version does not know" |
| M1-B (cli 1) | M1c: CLI-Seite ohne Markierung | `SchemaCompareCommandSemanticsTest` „no placeholder leaks" |
| F1-A (mcp 13) | F1a: Job-Artefakt unter `DIFF` | `McpCoreJobWorkerFactoryTest` (2), jeder Job-Fall in `SchemaCompareRuntimeSemanticsTest` |
| F1-A | F1e: Upload nimmt `COMPARE` an | `ArtifactUploadInitHandlerPolicyPathTest` „COMPARE erzeugt nur der Server" |
| F1-B (mcp 5) | F1b: Überlauf-Artefakt unter `DIFF` | `SchemaCompareOverflowArtifactTest` (2, „expected COMPARE but was DIFF"), Laufzeit „overflow artefact is the job's artefact" |
| F1-B | V1: `findings.take(1)` im Artefakt | Laufzeit „the job publishes the tool's findings in full", „name and version carry the values" |
| F1-C (mcp 3) | F1c: Überlauf als nacktes Array | `SchemaCompareOverflowArtifactTest` (2), Laufzeit „overflow artefact" |
| F1-D (mcp 2) | F1d: Überlauf nur über die Bytes | `SchemaCompareOverflowArtifactTest` „more findings than maxInlineFindings", Laufzeit „overflow artefact" |
| F1-I1 (Kompilat) | I1: Compare-Worker mit Schema-Publisher | `compileKotlin`: „Return type mismatch: expected 'SchemaDefinition', actual 'SchemaCompareOutcome'" |
| INT (`make integration`, e2e-cli und integration-mysql) | F1a durch den MCP-Client | `McpOperationalScenarioTest` „expected:<COMPARE> but was:<DIFF>" (140 Tests, 1 rot); `integration-mysql` im selben Lauf grün |

**Gates vierter Bauabschnitt:** `make docker-check` je Thema mit
`--continue` (F3: ports-common, ports-read, driver-common, die fünf Treiber,
application, cli, mcp; M1: application, cli, mcp; F1: core, application, cli,
mcp), `make golden-update` (zwei Zeilen: `COMPARE` in beiden
`artifact_list`-Aufzählungen), einmal **ohne** `MODULES` (alle
Integrationsmodule kompiliert, 12 188 Tests, 0 Fehler),
`make integration` für `:test:e2e-cli`, `:test:integration-mysql` und
`:test:integration-sqlite` (mit `-PintegrationTests`; alle drei ausgeführt,
grün; Kontroll-Lauf in der Sabotage-Tabelle), `make docs-check` (331 Dateien, 0 Befunde), `make solid-suppression-gate`
vor jedem Commit, `make sample-db-smoke` (Pagila PG→PG, 22 Tabellen,
Zeilenzahlen gleich, `schema compare` gleich der Baseline; der Reverse ist ohne
Präferenz unverändert), `make doc-immutable RANGE=origin/main..HEAD` vor der
Übergabe. **Präzisiert (Verifikation Runde 4):** die 331 Dateien zählte der
Lauf im Arbeits-Repo samt dem damals ungetrackten ADR-Entwurf 0057; ein
frischer Klon desselben Stands prüft 330. `make doc-immutable` im Arbeits-Repo
ist kein Beleg (s. `../done/doc-immutable-lokal-still-gruen.md`); belastbar
ist der Lauf im frischen `--no-local`-Klon des Reviews Runde 4 (Stand
`d3ef2d77d`, Ranges `e3116c34c..HEAD` und `076d6c955..HEAD`, je 328 Dateien,
0 Befunde). **CI an `e3116c34c`:** `Build & Test` grün, `Integration Tests`
rot — `MssqlFullTextEnvironmentIntegrationTest` (der Container
`d-migrate-mssql-fts:local` startete nicht, ein Ausreißer); Gradle brach
damit ab, bevor `:test:integration-mysql:test` lief. Dessen neuer Fall wäre
dort deterministisch rot gewesen (`No DialectCapabilityProvider for
POSTGRESQL`) und ist in `542008cbd` behoben; lokal lief er grün. Inzwischen
hat der Eigner bis `f6bab1514` gepusht; dort sind `Build & Test`,
`Integration Tests`, `Per-Module Coverage` und `Dependency Submission` grün. **Beim ersten Integrationslauf gefunden:** der neue
MySQL-Vergleichsfall nahm eine PostgreSQL-Markierung, und dieser Klassenpfad
führt nur den MySQL-Treiber (`No DialectCapabilityProvider for POSTGRESQL`);
der Fall vergleicht jetzt gegen ein handgeschriebenes IDENTITY-Soll, die
Namens-Projektion bleibt bei den Unit-Tests.
Detekt zählte `SchemaCompareHandlerTest` über die Grenze (`LargeClass`); die
Überlauf-Fälle stehen jetzt in `SchemaCompareOverflowArtifactTest`, ohne
`@Suppress`.

#### Konsumenten-Repro, vierter Lauf — ohne und mit Präferenz `identity`

**Weg wie im dritten Lauf** (Schema des Konsumenten, PostgreSQL 18.6 mit
PostGIS 3.6, MySQL 9.7.2, SQL Server 2025, SQLite mit SpatiaLite; Oracle nicht),
Image `make docker-build IMAGE_TAG=dev` auf `e3116c34c`, Skripte
`scratchpad/repro3/*4*` der Sitzung, Ausgabe `out4/`. Die Präferenz steht
**für die CLI** am MySQL-Reverse (`--mysql-autoincrement-syntax identity`) und
**für MCP** in der Konfigurationsdatei des Servers
(`--connection-config` mit `reverse.mysql.autoincrement_syntax: identity`).
Danach `make mcp-e2e-down` (der fremde `mcp-e2e-oracle-1` blieb unberührt).

**Reverse.** Ohne Präferenz sind alle vier CLI-Reverses **byte-identisch** zum
dritten Lauf (altes Image). Mit `identity` fehlt im MySQL-Reverse genau fünfmal
`legacy_serial_syntax: true`, der Report trägt fünfmal `R205`; der MCP-Reverse
mit der Server-Konfiguration unterscheidet sich vom Lauf ohne in denselben fünf
Zeilen.

**MCP — Anzahl der `findings`** (Status jeweils `different`):

| Paar | 1.7.1 | dritter Lauf | jetzt, ohne | jetzt, mit `identity` | `schema_compare_start` | Art des Job-Artefakts |
| ---- | ---: | ---: | ---: | ---: | ---- | ---- |
| PG↔MSSQL | 23 | 17 | 17 | 17 | dieselben Einträge samt `details` (beide Läufe) | `COMPARE` |
| PG↔MySQL | 20 | 19 | 19 | 19 | dieselben Einträge samt `details` | `COMPARE` |
| PG↔SQLite | 36 | 35 | 35 | 35 | dieselben Einträge samt `details` | `COMPARE` |
| MSSQL↔MySQL | 19 | 18 | 18 | 18 | dieselben Einträge samt `details` | `COMPARE` |

Das Job-Artefakt trägt genau `status`, `summary` und `findings`; kein Fund ist
`SCHEMA_NAME_CHANGED`/`SCHEMA_VERSION_CHANGED`, und keine Antwort und kein
Artefakt enthält den Platzhalter der Markierung.

**Was die Präferenz ändert — die Identity-Funde je Oberfläche:**

| Paar | Oberfläche | ohne Präferenz | mit `identity` |
| ---- | ---- | ---- | ---- |
| PG↔MySQL | CLI, `schema_compare`, `schema_compare_start` | 5 × `identity(mode=always,sequence=…) -> identity(mode=by_default,legacy_serial_syntax=true)` | 5 × `identity(mode=always,sequence=…) -> identity(mode=by_default)` — der **Modus** bleibt (K2), das Flag ist weg |
| MSSQL↔MySQL | alle drei | 5 × `identity(mode=always) -> identity(mode=by_default,legacy_serial_syntax=true)` | 5 × `identity(mode=always) -> identity(mode=by_default)` |
| PG↔SQLite | alle drei | 5 × Identity gegen keine Erzeugung | unverändert — SQLite liest unter der Breite `32` `identifier(auto)`, die Präferenz wirkt dort nicht |
| PG↔MSSQL | alle drei | keine Identity-Funde | keine |

Die Anzahl ändert sich mit dem Schema des Konsumenten nicht: dessen Spalten
sind `GENERATED ALWAYS`, und der Modus bleibt ein Unterschied.

**Sonde `BY DEFAULT`** (der PostgreSQL-Reverse mit `mode: by_default`, per
Skript gesetzt; so liest der Reverse eine `BY DEFAULT`-Spalte), CLI gegen den
MySQL-Reverse: ohne Präferenz fünf Erzeugungs-Funde, die sich nur im Flag
unterscheiden; mit `identity` **keiner** (es bleibt der berechnete
`line_total`).

**`schema migrate` gegen dieselbe MySQL-Datenbank** (`--plan-only`, Soll = der
MySQL-Reverse): ohne Präferenz keine Operation; mit `identity` fünf
`AlterColumnGeneration`, gerendert als wirkungsloses
``ALTER TABLE … MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT``.
**Korrektur (Review Runde 4, M-1):** die Ursache war hier nur halb benannt
(„der Ist-Stand wird ohne Präferenz gelesen"). Ein handgeschriebenes Soll ohne
`legacy_serial_syntax` plant dieselbe Operation ohne jede Präferenz, schon mit
1.7.1 — die zielbewusste Naht wertet das Flag gegen MySQL. Beide Ursachen
getrennt im fünften Bauabschnitt und im offenen Ticket.

**CLI** (Exit 1 in allen Paaren, jetzt und 1.7.1): dieselben Identity-Zeilen wie
oben; kein Bericht enthält den Platzhalter.

### Fünfter Bauabschnitt — Review und Verifikation Runde 4, E2E-Harnesses (2026-09-17)

Grundlage: ein viertes Review und eine vierte Verifikation (Sonden gegen
`d3ef2d77d`) und der Eigner-Auftrag vom 2026-09-17, beide E2E-Harnesses im
Compare-Slice zu bauen. ADR 0057 ist inzwischen `accepted` (`f6bab1514`) und
bleibt unberührt, ebenso 0053 und 0056. Commits `49a3b3d4c` (Präferenzen),
`f345e00f7` (Reverse-Report), `a1a02b919` (Spec, Handbuch, Ticket,
CHANGELOG), `c7bfe88dd` (Harnesses).

**Review-Befunde (Runde 4).**
- **M-1 — die Ursache des wirkungslosen `ALTER` war falsch benannt:**
  korrigiert im Handbuch (Reverse-Abschnitt: beide Ursachen getrennt, der Rat
  „ohne `identity` lesen" nur für zurückgelesene Dateien, für ein
  handgeschriebenes Soll gegen MySQL `legacy_serial_syntax: true`), im
  vierten Lauf oben, unter „Offen" und im offenen Ticket (Analyse, ADR 0027
  Entscheidung 3, Spannung zur Präferenz-Spec). **Nicht gebaut.** Gemessen
  gegen MySQL 9.7.2, `schema migrate --plan-only`: handgeschriebenes Soll ohne
  Flag — eine `AlterColumnGeneration`, **mit 1.7.1 genauso**; mit Flag — keine
  Operation; Reverse ohne Präferenz — keine; Reverse mit `identity` — eine.
- **M-2 — über MCP war die Präferenz stumm:** behoben. Wo sie hingehört,
  war zu prüfen: `job_status_get` trägt keine Hinweise (`ManagedJob` hat kein
  Feld dafür, `spec/job-contract.md` sieht keines vor), das Schema-Artefakt
  trägt laut CLI-Spec keine Notes. Die CLI trennt Schema-Dokument und
  Reverse-Report — also bekommen die Lese-Jobs **den Reverse-Report als
  zweites Artefakt**: dieselbe Form wie `schema reverse` (`source` mit
  `kind: connection`, `summary`, `notes`, `skipped_objects`), Art `OTHER`
  (seit dem sechsten Bauabschnitt die eigene Art `REVERSE_REPORT`),
  `application/x-yaml`, ohne Index. `schema_reverse_start`: Schema, dann
  Report. `schema_compare_start`: Compare-Artefakt, dann je aus einer
  Verbindung gelesene Seite ihr Report, Quelle vor Ziel (das Compare-Artefakt
  bleibt, wie ADR 0057 es festlegt). Nebenbei sichtbar geworden: übersprungene
  Objekte des Readers verwarf der Server ebenso. Port-Änderungen:
  `SchemaReverseJobWorker` liest ein `SchemaReadResult` und hat einen
  `reportPublisher`, `SchemaCompareJobWorker` lädt `LoadedCompareSide`,
  `ReverseSourceKind.CONNECTION` — eine geteilte Signatur, deshalb einmal ohne
  `MODULES` gebaut. Spec (`spec/mcp-server.md`, „Artefakte der Lese-Jobs",
  `spec/dialect-preference-mechanism.md`, „Nicht stumm"), Handbuch
  (MCP-Beispiel, Compare-Abschnitt), CHANGELOG (Added).
- **L-1 — `diffs`-Index:** `spec/mcp-server.md` beschreibt den Ist-Zustand
  (gefunden über `job_status_get` und `artifact_list`; `diff_list` ist in
  `mcp serve` leer); der Rest steht unter „Offen".
- **L-2 — CHANGELOG:** die Reichweite der Konfiguration steht unter
  „Changed", mit dem gemessenen Umkehr-Beispiel (Konfiguration mit Breite 64,
  `schema compare db:<sqlite>`: gegen einen Reverse mit Breite 64 bisher
  `DIFFERENT`/Exit 1, jetzt `IDENTICAL`/Exit 0; gegen einen mit Breite 32
  bisher `IDENTICAL`, jetzt `DIFFERENT`; beides gegen 1.7.1 und das neue Image
  gemessen).
- **L-3 — Tippfehler in `autoincrement_syntax`:** behoben, und die Breite
  gleichgezogen — sie fiel bei einem unbekannten Wert **ebenso still** auf
  `32`. Ein vorhandener, aber nicht erkannter Wert wirft
  `InvalidReversePreference`; `schema reverse` und `schema compare` (mit
  `db:`-Operand, jetzt in Phase 1) enden mit Exit 7, `data transfer` mit
  Exit 7, `mcp serve` startet nicht (Exit 2, wie die übrigen
  Konfigurationsfehler). Ein fehlender Block, eine fehlende oder kaputte Datei
  bleiben „nicht erklärt"; ein gesetztes Flag verdeckt den Wert seines
  Dialekts. Spec: `connection-config-spec.md` (Kommentar des Blocks),
  `dialect-preference-mechanism.md` (Abschnitt 2, für Lese- und
  Schreib-Präferenzen), `cli-spec.md` (Exit-Codes). **Über den Befund
  hinaus:** `data transfer` fing die gleichwertige
  `OracleEmptyStringResolver.InvalidPreference` nie ab — mit 1.7.1 gemessen:
  Java-Stacktrace und Exit 1 statt der spezifizierten Exit 7. Jetzt fängt
  dieselbe Stelle beide (CHANGELOG, Fixed).
- **INFO:** `spec/mcp-server.md` präzisiert (bei einem hochgeladenen Schema
  entscheidet das Werkzeug, das die Datei erzeugt hat); die halbe Markierung
  steht in `cli-spec.md` (Flagtabelle von `schema reverse` und
  „Reverse-Markierung"; gemessen: `--version` allein → `schema compare`
  Exit 7, `--name` → die Datei gilt als handgeschrieben); CHANGELOG nennt den
  Exit-Code-Wechsel 1 → 0; Handbuch: SQLite nur mit Breite 64; `R205`
  **und** `R204` nennen die Stelle der Deklaration (`PreferenceSource` in
  `SchemaReadOptions`, `DeclaredPreference` in `driver-common`; gemessen:
  `--mysql-autoincrement-syntax identity` → „per declared preference
  (--mysql-autoincrement-syntax identity)"); `mcp serve` löst die Präferenzen
  einmal beim Start auf (Spec) und reicht dieselben an beide Serve-Zweige
  (Test mit In-Memory- und `--server-state`-Zweig).

**Verifikation Runde 4.** Den CI-Stand von `e3116c34c`, die Belege des
vierten Bauabschnitts und P7-DoD 3 halten die jeweiligen Stellen oben fest;
der veraltete `E012`-Anker ist hier und im Reader-Slice nachgezogen.

**Abweichungen vom Auftrag.** `data transfer` bekommt die Herkunft der Breite
nicht: der Transfer verwirft die Reader-Notes ohnehin, die Herkunft hätte dort
keine sichtbare Wirkung (s. „Offen"). Das Handbuch-Beispiel zu `schema_list`
ist mitkorrigiert (die bloße `jobId` findet nichts, gemessen). Die
Zwischencommits `49a3b3d4c` und `f345e00f7` sind nicht einzeln gebaut, nur der
Endstand.

**Sabotage-Protokoll fünfter Bauabschnitt.** Je Gruppe ein Lauf mit
`--continue` über `docker build --target build`, Rücknahme per Archiv und
Prüfsumme (alle Dateien „OK").

| Lauf | Sabotage | rot |
| ---- | -------- | --- |
| S1 (app 4, mcp 2, cli 5, driver-common 2, mysql 2, sqlite 1) | M2a: Reverse-Worker veröffentlicht den Report nicht | `SchemaReverseJobWorkerTest` (2), `JobWorkerScenarioTest`, `McpCoreJobWorkerFactoryTest` „publishes the read report" |
| S1 | M2d: Compare-Worker veröffentlicht Ziel vor Quelle | `SchemaCompareJobWorkerTest` „source before target" |
| S1 | M2b: Fabrik verwirft das Leseergebnis der Verbindungsseite | `McpCoreJobWorkerFactoryTest` „one read report per connection side" |
| S1 | L3a: unbekannter Syntax-Wert still `serial` | `ReverseAutoIncrementSyntaxResolverTest`, `SchemaReverseWiringTest` (Exit 7), `SchemaCompareWiringTest`, `McpServeWiringTest` (Exit 2) |
| S1 | SRCa: Note nennt immer den Schlüssel | `AutoIncrementSyntaxNoteTest` (2), `MysqlTypeMappingTest`, `MysqlSchemaReaderTest`, `SqliteSchemaReaderTest` |
| S1 | SERVEa: In-Memory-Zweig mit Default-Präferenzen | `McpServeWiringTest` „in-memory branch" |
| S2 (mcp 2, cli 7, sqlite 1) | M2c: Report ohne Notes | `McpCoreJobWorkerFactoryTest` (2, `R205`) |
| S2 | L3b: unbekannte Breite still `32` | `ReverseAutoincrementResolverTest`, `ReverseAutoIncrementSyntaxResolverTest`, `DataTransferWiringTest`, `SchemaReverseWiringTest` |
| S2 | SRCb: Resolver meldet immer `CONFIG` | `ReverseAutoIncrementSyntaxResolverTest` „flags reach both parts", `SchemaReverseWiringTest` „where each preference was declared" |
| S2 | SRCd: SQLite-Reader ohne Herkunft | `SqliteSchemaReaderTest` „declared by flag" |
| S2 | SERVEb: `--server-state`-Zweig mit Default-Präferenzen | `McpServeWiringTest` „--server-state branch" |
| S3 (ports-read 1, mysql 1, cli 5) | L3c: `schema reverse` schluckt den Fehler | `SchemaReverseWiringTest` (Exit 7) |
| S3 | L3d: `mcp serve` schluckt den Fehler | `McpServeWiringTest` (Exit 2) |
| S3 | L3e: `data transfer` fängt nur die Lese-Präferenz | `DataTransferWiringTest` (Oracle-Wert) |
| S3 | L3f: `schema compare` fällt still auf Defaults | `SchemaCompareWiringTest` |
| S3 | SRCc: `applyTo` verliert die Herkunft | `ReversePreferencesTest`, `SchemaReverseWiringTest` |
| S3 | SRCe: MySQL-Reader ohne Herkunft | `MysqlSchemaReaderTest` |
| INT (`make integration`, e2e-cli) | M2a durch den MCP-Client | `McpOperationalScenarioTest` „expected:<2> but was:<1>" (2 Tests, 1 rot) |
| H1 (Harness) | Roundtrip gegen das Image `1.7.1` | Wächter `metadata` (Platzhalter), `notation` (CHECKs ohne Ausdruck, Index-Prädikat), `sequence` (Identity) |
| H2 (Harness) | Compare-Matrix gegen das Image `1.7.1` | 31 Abweichungen: Markierung als `SCHEMA_NAME_CHANGED`, Werkzeug ≠ Job, Art nicht `COMPARE`, Form, Version, Zellen; die Erwartungsdatei blieb unverändert. **Korrigiert (Verifikation Runde 5):** „kein Reverse-Report" war **nicht** unter den 31 — die Meldung ging in einer Subshell verloren (Blocker des sechsten Bauabschnitts); die Neumessung steht dort |

**Gates fünfter Bauabschnitt:** `make docker-check` für ports-read (224
Tests), driver-common (543), driver-mysql (846), driver-sqlite (754),
application (1908 — hier stand zuerst 1907, der volle Lauf zählt 1908), mcp (1252) und cli (1080), einmal **ohne** `MODULES`
(12 209 Tests, 0 Fehler, `integration-mysql` und `e2e-cli` kompiliert),
`make integration` für `:test:e2e-cli`, `:test:integration-mysql` und
`:test:integration-sqlite` (mit `-PintegrationTests`, 97 Tasks ausgeführt,
grün; Kontroll-Lauf INT), `make docs-check` (331 Dateien, 0 Befunde — im Arbeits-Repo und im frischen Klon gleich),
`make solid-suppression-gate` vor jedem Commit, `make semgrep`, für die
Skripte `bash -n` und shellcheck (per Container, kein Repo-Gate — das Repo hat
keins für Shell), `make doc-immutable` im frischen `--no-local`-Klon
(Ranges `f6bab1514..HEAD` = `origin/main..HEAD` und `e3116c34c..HEAD`, je 329
Dateien, 0 Befunde).

#### E2E-Harnesses (Eigner-Auftrag 2026-09-17)

Vorbild ist das Konsumenten-Repo (nur gelesen): versionsgebundene
Erwartungen mit `--update-expectations`, versionsunabhängige Wächter, eine
5x5-Matrix mit Funden und Codes je Zelle. Die Fixtures sind eigene. Gemeinsam
genutzt: `examples/mcp-e2e/scripts/lib/dialects.sh` (Stack, Leeren,
Anwenden je Dialekt) und `lib/compare-guards.sh` (Wächter über eine
vereinheitlichte Fundliste aus CLI-JSON und MCP-Funden).

**Roundtrip** (`smoke-cross-dialect-roundtrip.sh`, `make mcp-e2e-roundtrip`):
Fixture um CHECK mit `OR`/`IS NULL`, Werteliste an `varchar`,
`numeric > 0`, LIKE-CHECK, Index mit Prädikat und eine Identity-Spalte
erweitert. Die Wächter arbeiten auf `schema compare --output-format json` und
zielen auf Klassen: `notation` (CHECK-, Index- und Fremdschlüssel-Fund, der
ohne Leerraum, Quoting, Klammern und ausdrückliches `no_action` gleich ist —
er ersetzt den alten `_fkey`-Grep, der den Fremdschlüssel der Fixture nie
traf), `metadata` (Name/Version gegen einen Reverse) und `sequence`
(Erzeugung, die nur am Sequenznamen hängt). MySQL scheitert an den neuen
String-Literal-CHECKs mit dem bekannten Introducer (`E012`, Exit 3); der Lauf
erkennt genau diesen Grund und weist den Dialekt als „ungültig" aus. Stand
2026-09-17 (zwei vollständige Läufe gleich): PostgreSQL 1 Fund, MySQL ungültig,
SQL Server 5, SQLite 6, Oracle nicht gefahren (README-Tabelle).

**Compare-Matrix** (Stand dieses Abschnitts; der sechste Bauabschnitt setzt
die Präferenz `identity` am Server und pinnt PostgreSQL → MySQL neu)
(`smoke-compare-matrix.sh`, `make mcp-e2e-compare-matrix`,
Oracle mit `…-oracle`): eigene Fixture `fixtures/compare-matrix.yaml`, jeder
Dialekt einmal Quelle; die Ziel-DDL erzeugt die CLI aus dem Schema, das der
MCP-Reverse abgelegt hat, angewendet mit dem Client des Dialekts; verglichen
mit `schema_compare` **und** `schema_compare_start` in einer stdio-Sitzung je
Quelle. Erwartungen in `expected/compare-matrix.env` (versionsgebunden,
`EXPECT_VERSION`); nie gepinnt: Werkzeug = Job, Art `COMPARE` und Form des
Job-Artefakts, die drei Wächter, Reverse-Report je Reverse-Job. Zellen ohne
Messung sind als Zustand gepinnt (`INVALID`/`E012-introducer`,
`APPLY-FAIL`/Fehlerklasse des Servers). Native Seeds: `fixtures/seeds/<dialekt>.sql`
wird angewendet, wenn es die Datei gibt (heute keine). Stand 2026-09-17
(`d-migrate:dev` 1.8.0-SNAPSHOT; zwei Prüfläufe identisch):

| Quelle \ Ziel | PostgreSQL | MySQL | SQL Server | SQLite |
| ------------- | ---------- | ----- | ---------- | ------ |
| PostgreSQL | — | 7 | 5 | 13 |
| MySQL | `INVALID` | — | `INVALID` | `INVALID` |
| SQL Server | `APPLY-FAIL` (`syntax error at or near "["`) | `APPLY-FAIL` (`ERROR 1064`) | — | 9 |
| SQLite | 3 | `APPLY-FAIL` (`ERROR 1170`) | `APPLY-FAIL` (`Msg 2714`) | — |

Codes: PostgreSQL → MySQL `TABLE_COLUMN_GENERATION_CHANGED:2
TABLE_CONSTRAINT_CHANGED:1 TABLE_CONSTRAINT_REMOVED:3 TABLE_INDEX_REMOVED:1`;
→ SQL Server `TABLE_COLUMN_GENERATION_CHANGED:2 TABLE_CONSTRAINT_REMOVED:3`;
→ SQLite `TABLE_COLUMN_GENERATION_CHANGED:2 TABLE_COLUMN_TYPE_CHANGED:8
TABLE_CONSTRAINT_REMOVED:3`; SQL Server → SQLite
`TABLE_COLUMN_GENERATION_CHANGED:1 TABLE_COLUMN_TYPE_CHANGED:8`; SQLite →
PostgreSQL `TABLE_CONSTRAINT_CHANGED:2 W137:1`. Jeder Fund ist erklärt (README
des Harness): der Generator rendert PostgreSQL-Casts in CHECK und Berechnung
nicht (`E053`), MySQL kennt kein Index-Prädikat (`E057`), SQL Server kein
`BY DEFAULT` (`W140`), MySQL ohne deklarierte Präferenz mit
`legacy_serial_syntax`, Schlüsselwort-Schreibweise, ADR 0055, SQLite-Typaffinität,
`W137`. Kein Wächter schlägt an; `OR`/`IS NULL`, `numeric > 0`, das
Index-Prädikat und der Sequenzname melden nirgends etwas. Die
`APPLY-FAIL`-Zellen sind Reader- und Generator-Befunde (s. „Offen").

**Workflow:** `.github/workflows/mcp-e2e-compare-matrix.yml` („MCP-E2E
Compare-Matrix (Best-Effort)") — `workflow_dispatch`, wöchentlich montags
03:17 UTC, Push auf `main` in `examples/mcp-e2e/**`, `make/mcp-e2e.mk` und
die Workflow-Datei; `continue-on-error`, Checkout per SHA gepinnt wie die
Sample-DB-Cross-Smokes, ohne Oracle; kein PR-Gate (seit dem sechsten
Bauabschnitt rot sichtbar, mit Artefakt-Upload und weiteren Pfaden, s. dort).
**Oracle** ist in beiden Harnesses nicht gefahren (s. „Offen"). Nach den Läufen `make mcp-e2e-down`;
der fremde `mcp-e2e-oracle-1` blieb unberührt.

### Sechster Bauabschnitt — Korrekturabschnitt 6: Review und Verifikation Runde 5 (2026-09-17)

Grundlage: das fünfte Review und die fünfte Verifikation (gegen `2299f0cf5`)
und zwei Eigner-Entscheidungen vom 2026-09-17. ADR 0053, 0056 und 0057 bleiben
unberührt. Commits `df098292c` (Artefakt-Art), `c803dda52`
(Ablagereihenfolge), `453722a34` (`R202`), `328479ba4` und `61ab4aaae`
(Harnesses, Workflow), `d5e0c5f9b` (Spec, Handbücher, CHANGELOG).

**Eigner-Entscheidungen.**
- **Der Reverse-Report hat die eigene Art `REVERSE_REPORT`** statt `OTHER` —
  Begründung wie bei `COMPARE`: Abnehmer filtern per Art. Gebaut: das Enum
  (`hexagon:core`), der Publisher, `artifact_list` (Filter `kind` und Feld
  `artifactKind` leiten ihre Werte jetzt aus dem Enum ab; Golden per
  `make golden-update`), der Upload ist wie bei `COMPARE` ausgeschlossen.
  Spec (`spec/mcp-server.md`), CHANGELOG (Added nennt die Art; unter
  „Changed" die geänderte Artefaktzahl der Lese-Jobs und der zusätzliche
  Wert der Art-Liste), Anwenderhandbuch, API-Referenz (die `artifacts` der
  Lese-Jobs je Job). `:test:e2e-cli` prüft Art und Filter durch den
  MCP-Client, die Matrix die Art je Artefakt. Geteiltes Enum, deshalb einmal
  ohne `MODULES` gebaut.
- **Der Workflow der Matrix wird rot sichtbar:** kein job-weites
  `continue-on-error`, kein Pflicht-Check; Upload von `out/compare-matrix`
  mit `if: always()` (`actions/upload-artifact` per SHA, übernommen aus
  `build.yml`); Pfadfilter um `Makefile`, `make/**` und `Dockerfile`; der Name
  trägt kein „(Best-Effort)" mehr. Die Geschwister (Sample-DB-Cross-Smokes)
  sind unverändert (s. „Offen").

**Blocker (AK 11) — der Report-Wächter der Matrix schlug nie an.**
`note_failure` lief in `mcp_reverse`, und `mcp_reverse` läuft in einer
Kommandosubstitution: der Eintrag ins Array ging mit der Subshell verloren.
Behoben über Dateien (`.failures` nie pinnbar, `.deviations` pinnbar);
mehrzeilige Meldungen werden zu einer Zeile gefaltet (`61ab4aaae`, gefunden
an der jq-Sabotage, die 30 statt 10 Abweichungen zählte). Der Roundtrip und
`lib/*.sh` tragen das Muster nicht — dort hält keine Subshell Zustand. HC
wiederholt: rot; mit zurückgenommenem Fix grün (Sabotage BF).

**Harness-Härtung (Review M-2, LOW-1; Verifier).**
- `--update-expectations` pinnt keine Rückschritte: ein Generate-Exit weder
  `0` noch `8` (oder eine fehlende DDL-Datei) ist `GEN-FAIL`, auch für die
  Fixture der Quelle; `apply:unbekannt` ist nie pinnbar; die
  Erwartungsdatei wird nur ohne nicht pinnbare Abweichung geschrieben;
  sqlite3-Fehler („Parse error near line N: …", sqlite3 3.45.1) sind eine
  Fehlerklasse, ohne Zeilennummer.
- Ein jq-Fehler in den Wächtern ist in beiden Skripten ein Fehlschlag.
- Formänderungen scheitern laut: der Roundtrip nimmt die Fundzahl aus dem
  JSON-Dokument (Summe von `summary`); die Vereinheitlichung prüft sich
  selbst (CLI: jede Zahl in `summary` ist die Länge der Liste in `diff`,
  geänderte Tabellen und Spalten tragen nur bekannte Schlüssel; MCP:
  `different` hat Funde, `identical` keine, Änderungsfunde tragen
  `details`); `sequence` prüft die Struktur `identity(k=v,…)`, ein
  unbekannter Schlüssel ist der neue Verstoß `form`; der bekannte
  Introducer-Befund zählt nur für MySQL-Zeichensätze. Gegen die Proben der
  Verifikation: umbenannte Tabellenschlüssel, `sequence_name=` und eine
  Zählabweichung scheitern laut, `_tmp` zählt nicht mehr. **Grenze:** die
  `notation`-Heuristik erkennt kein Paar, dessen Seiten verschiedene
  Signaturformate tragen — beide Seiten kommen aus demselben Renderer.
- Die Wächter laufen in der Matrix auf Werkzeug **und** Job; ein
  Compare-Job zweier Schemata nennt genau ein Artefakt, ein Reverse-Job
  genau `SCHEMA` und `REVERSE_REPORT`; der MySQL-Report bestätigt `R205`.
- `lib/*.sh` tragen `# shellcheck shell=bash` (SC2148).

**Sequenz-Wächter der Matrix (Verifier HB) — sehend gemacht.** Der Befund
stimmte: mit den Defaults unterschied sich jede PostgreSQL-Identity-Spalte
gegen MySQL zusätzlich in `legacy_serial_syntax`, gegen SQL Server im Modus
(`W140`), gegen SQLite fehlte die Erzeugung — der Wächter konnte nirgends
anschlagen. Der Matrix-Server liest jetzt
`reverse.mysql.autoincrement_syntax: identity` (der Lauf schreibt die
Konfiguration nach `out/compare-matrix/`); die Fixture trägt `cm_order.id`
bereits mit `BY DEFAULT`. PostgreSQL → MySQL ist damit die Zelle, in der die
Spalte sich nur im Sequenznamen unterscheiden kann. Neu gepinnt:
PostgreSQL → MySQL 7 → 6 (der Identity-Fund entfällt). HB wiederholt: die
Matrix wird rot (`sequence` an `tables.cm_order.columns.id.generation`,
Werkzeug und Job, dazu die Zelle 6 → 7). Eine zweite, **strukturelle**
Blindheit bleibt: zwei Reverses tragen nach dem Entfernen der Markierung
denselben Platzhalter, `metadata` kann in der Matrix nie anschlagen — das
sichert der Roundtrip (HB dort rot, `metadata` in drei Dialekten). README und
Harness-Kopf benennen beides.

**Review-Befunde (Runde 5).**
- **M-3 — ungültige Flag-Werte:** gemessen am neuen Image:
  `schema reverse --mysql-autoincrement-syntax identiy` und
  `--sqlite-autoincrement-width 16` enden mit **Exit 1** (Clikt-Usage-Fehler
  über `Main.kt`), `spec/cli-spec.md` und der Troubleshooting-Leitfaden sagen
  2. Vorbestehend und global, **nicht hier gebaut** (s. „Offen"). Korrigiert
  ist der neue Satz in `spec/dialect-preference-mechanism.md` (Lese-Flags: der
  Usage-Fehler der Exit-Code-Tabelle; das Schreib-Flag: Exit 7, wie
  `cli-spec.md` es für `--oracle-empty-string` sagt) und die KDoc von
  `ReverseAutoIncrementSyntaxResolver` (verweist auf die Tabelle, statt einen
  Code zu nennen).
- **LOW-2:** CHANGELOG — `data transfer` liest `autoincrement_syntax` nicht
  (ein Tippfehler dort bleibt ohne Wirkung); nur die Breite führt dort zu
  Exit 7.
- **LOW-3:** Administrationshandbuch — `reverse:` liest `mcp serve` einmal
  beim Start (Änderung erst nach Neustart); ein unbekannter Wert verhindert
  den Start (Exit 2, auch in der Liste der Boot-Validierung), mit Hinweis für
  Betreiber, deren Konfiguration bisher einen stillen Tippfehler trug.
- **LOW-4:** `spec/mcp-server.md` beschreibt den Vertrag des Index `diffs`
  (führt das Compare-Artefakt mit beiden Verweisen); die Lücke in
  `mcp serve` steht unter „Offen".
- **INFO:** `R202` rät über `DeclaredPreference.advise` an der Stelle der
  Deklaration, ohne gesetztes Flag zum Konfigurationsschlüssel (im Harness
  gemessen: der SQLite-Report über MCP nennt kein Flag mehr). Die Lese-Jobs
  legen die Reports **vor** dem Ergebnis ab, das Ergebnis trägt sich als
  Letztes in seinen Index ein; der Vertrag für einen gescheiterten Job (keine
  `artifacts`, kein Index-Eintrag, Abgelegtes bis `expiresAt`) steht in
  `spec/mcp-server.md`. Die Felder des Reverse-Reports sind in
  `spec/cli-spec.md` definiert, auf das `mcp-server.md` mit „dieselbe Form"
  verweist.

**Verifikation Runde 5 — Korrekturen.** H2 ist oben korrigiert und neu
gemessen (Tabelle unten); „application (1907)" heißt 1908; der Satz, der
Koordinator ziehe die Reader-Posten nach, ist ersetzt — sie stehen als D1–D3
im Reader-Slice. Der Reader-Slice nennt die aktuelle `E012`-Stelle des
Handbuchs und unter „Verifikation" (Punkt 5) die Erweiterung der Matrix um
native Typ-Seeds und einen Silent-Loss-Check als Abnahme seiner Pakete.

**Abweichungen vom Auftrag.**
- `R202` nennt ohne gesetztes Flag nur den Konfigurationsschlüssel — auch in
  `schema reverse`, das ein Flag hätte. Die Quelle (`PreferenceSource`) kennt
  nur „Flag" oder „Konfiguration", wie bei `R204`/`R205`; wer die
  CLI-Oberfläche dort wieder nennen will, braucht eine Oberflächenangabe im
  Port.
- Die Wrapper-Images der Harness-Sabotage (W7, WK, WF) sind per
  `docker build` auf `d-migrate:dev` gebaut (Sandkasten, kein Repo-Bau); die
  Image-Sabotagen HC und HB per `make docker-build` im Klon.

**Sabotage-Protokoll sechster Bauabschnitt.** Unit-Läufe im frischen Klon
(`make docker-test` mit `--continue`), Rücknahme per `git checkout` und
Prüfsumme (alle „restore OK"); Harness-Läufe gegen `d-migrate:dev`
(= Code-Stand von HEAD) bzw. die genannten Images, die Erwartungsdatei blieb
in jedem Lauf unverändert.

| Lauf | Sabotage | rot |
| ---- | -------- | --- |
| SAB1 (application 1910/3, driver-sqlite 755/1; mcp dort am Detekt der Sabotage G1 gescheitert, s. SAB1b) | O1: Reverse-Worker legt das Schema vor dem Report ab | `SchemaReverseJobWorkerTest` (2) |
| SAB1 | O2: Compare-Worker legt das Ergebnis vor den Reports ab | `SchemaCompareJobWorkerTest` „published last" |
| SAB1 | R1: `R202` nennt immer das Flag | `SqliteTypeMappingTest` „config key without a flag" |
| SAB1b (mcp 1252/4) | K1: Report-Art zurück auf `OTHER` | `McpCoreJobWorkerFactoryTest` (2) |
| SAB1b | U1: Upload nimmt `REVERSE_REPORT` an | `ArtifactUploadInitHandlerPolicyPathTest` |
| SAB1b | G1: Tool-Schema ohne `REVERSE_REPORT` | `McpToolSchemasGoldenTest` |
| SAB2 (driver-common 544/1, driver-sqlite 755/1) | A1: `advise` rät ohne Flag zum Flag | `AutoIncrementSyntaxNoteTest`, `SqliteTypeMappingTest` |
| INT (`make integration`, e2e-cli 140/1) | K1 durch den MCP-Client | `McpOperationalScenarioTest` „expected:<REVERSE_REPORT> but was:<OTHER>" |
| HC (Harness, Image ohne Report) | der Reverse-Job legt keinen Report ab | Matrix rot, 18 Abweichungen (9 Reverse-Jobs × Artefaktzahl und Report) |
| BF (Harness) | Blocker-Fix zurück (Array statt Datei), Image wie HC | Matrix **grün** (Exit 0, „OK") trotz 18 `FAIL`-Zeilen — der Blocker, reproduziert |
| HB (Harness, Image ohne P6 und ohne die Markierungsregel) | der Vergleich wertet Sequenzname, Name und Version | Matrix rot (`sequence` PostgreSQL → MySQL an Werkzeug und Job, Zelle 6 → 7); Roundtrip rot (`metadata` in drei Dialekten, `sequence` bei PostgreSQL) |
| W7 (Harness, Wrapper) | `schema generate` SQLite → PostgreSQL endet mit Exit 7, Lauf mit `--update-expectations` | Zelle `GEN-FAIL`, „Erwartungen NICHT geschrieben", rot; die Erwartungsdatei (Kopie) unverändert |
| WK (Harness, Wrapper) | der Server meldet den Report unter `OTHER` | Matrix rot, 9× „hat nicht die Art REVERSE_REPORT" |
| WF (Harness, Wrapper) | Schlüssel umbenannt (CLI `constraints_changed`, MCP `details`) | Roundtrip rot (Selbstprobe: „geänderte Tabelle ohne bekannte Änderung"); Matrix rot (Selbstprobe, Werkzeug ≠ Job) |
| JQ (Harness) | das jq-Programm der Wächter ist kaputt | Matrix rot, 10 Abweichungen (5 Zellen × Werkzeug und Job „nicht auswertbar"); Roundtrip rot |
| H2′ (Harness, Image `1.7.1`) | Neumessung von H2 nach dem Blocker-Fix | 43 nicht pinnbare und 11 Erwartungs-Abweichungen: Reverse ohne Report 18 (9 × 2), Art nicht `COMPARE` 5, Form 5, Werkzeug ≠ Job 5, Wächter am Werkzeug 5 (Selbstprobe 3 — `1.7.1` trug keine `details` —, `metadata` 2), am Job 5 (keine Fundliste); Version 1, fünf Zellen × 2. Mit `--update-expectations` nichts geschrieben |

**Gates sechster Bauabschnitt:** `make docker-check` für core (1483 Tests),
application (1910), driver-common (544), driver-sqlite (755) und mcp (1252),
einmal **ohne** `MODULES` (12 213 Tests, 0 Fehler, alle Integrationsmodule
kompiliert); `make golden-update`; `make integration` für `:test:e2e-cli`
(Task ausgeführt, grün; Kontroll-Lauf INT); `make docs-check` (331 Dateien,
0 Befunde); `make solid-suppression-gate` vor jedem Commit; `bash -n` und
shellcheck (Container) für die vier Harness-Dateien — `smoke-scope-matrix.sh`
trägt zwei vorbestehende Befunde (SC1091, SC2155) und ist nicht angefasst;
`make doc-immutable` im frischen `--no-local`-Klon (`f6bab1514..HEAD`).

**Harness-Stand** (HEAD `61ab4aaae`, `d-migrate:dev` 1.8.0-SNAPSHOT, je Harness
zwei Läufe, identisch und gleich den Läufen auf `d5e0c5f9b`): Roundtrip
PostgreSQL 1, MySQL ungültig (`E012`), SQL Server 5, SQLite 6 — unverändert,
jetzt aus dem JSON gezählt; Matrix wie in der README, PostgreSQL → MySQL 6
(`TABLE_COLUMN_GENERATION_CHANGED:1 TABLE_CONSTRAINT_CHANGED:1
TABLE_CONSTRAINT_REMOVED:3 TABLE_INDEX_REMOVED:1`), alle übrigen Zellen wie im
fünften Abschnitt. Danach `make mcp-e2e-down`; `mcp-e2e-oracle-1` blieb
unberührt, kein Oracle-Opt-in.

## Akzeptanzkriterien

1. Im gemeldeten Repro: **6** — der Sequenzname (P6) zählt nicht mehr, in CLI
   und beiden MCP-Oberflächen; `legacy_serial_syntax` entfällt, sobald der
   MySQL-Reverse mit `identity` liest (P10, Eigner-Entscheidung F3) — ohne
   Deklaration bleibt es ein Unterschied. **Ehrlich eingeschränkt:** mit dem
   Schema des Konsumenten (`GENERATED ALWAYS`) meldet PG↔MySQL die
   Identity-Spalten mit und ohne Präferenz weiter, wegen des **Modus** — MySQL
   kennt nur `AUTO_INCREMENT` und liest es als `by_default`; ein
   Fähigkeitsunterschied wie `W140`, kein Fehlalarm dieses Slices. Mit
   `BY DEFAULT` (Sonde des vierten Laufs, ohne Reader-Fix, Exit 1) meldet
   PG↔MySQL mit `identity` keine Identity-Spalte mehr, ohne fünf; PG↔MSSQL
   hat keine Identity-Funde. **3** in seinem PG↔MSSQL-Bein, **5** seine
   Schreibweise-Differenzen (Index-Prädikat **und** Listen-Komma) — beides am
   nachgebauten Schema gemessen, das Schema des Konsumenten trägt diese
   Konstrukte nicht mehr; 1 nennt Vorher und Nachher; 2 folgt dem
   Pfad-Schema der übrigen Funde; die Reverse-Markierung ist in keiner
   Oberfläche ein Fund. Was bleibt, bleibt **bewusst** — die zwei
   Grenzfragen, die ADR-entschiedene Umschreibung und die Modus-Grenze; ein
   Abnehmer, der die ganze Dreier-Matrix erwartet, erwartet zu viel.
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
   `price` kein Ganzzahltyp ist. Wo der PostgreSQL-Reverse zwei Typen auf
   einen faltet, fällt kein Cast, der sie unterschiede:
   `(0.5)::double precision` und `(spalte)::text` an einer Textspalte ohne
   Länge bleiben Funde (Review Runde 2, H1/M1).
9. `legacy_serial_syntax` bleibt in `schema compare` ein Unterschied (P10,
   vierter Bauabschnitt). Ob ein MySQL-Reverse (`BIGINT AUTO_INCREMENT`) oder
   ein SQLite-Reverse unter der 64-Bit-Breite das Flag setzt, entscheidet eine
   deklarierte Reverse-Präferenz: `serial` als Default (byte-identischer
   Reverse), `identity` ohne das Flag und mit `R205`; Flag > Datei > Default;
   die Konfiguration sehen auch `db:`-Operanden von `schema compare` und
   `mcp serve` (dort einmal beim Start, in beiden Serve-Zweigen). Ein
   vorhandener, aber nicht erkannter Wert ist ein Konfigurationsfehler (CLI
   Exit 7, `mcp serve` Exit 2); `R204`/`R205` nennen die Stelle der
   Deklaration. Migrate und Fingerabdruck werten das Flag weiter.
10. `schema compare` hat **eine** Semantik in CLI, `schema_compare` und
    `schema_compare_start` (P11): dieselbe Faltung, dieselbe
    Erzeugungs-Projektion, und trägt eine Seite die Reverse-Markierung, sind
    Name und Version kein Fund — ohne dass ein Platzhalter nach außen dringt
    (M1, Review Runde 3). Der Job veröffentlicht dieselben Funde wie das
    Werkzeug, **ungekürzt** (volle Listen samt `details` verglichen), unter
    der eigenen Art `COMPARE` und in derselben Form wie das Überlauf-Artefakt
    von `schema_compare` (F1, L1) — gepinnt an den echten Verdrahtungen.
11. Über MCP ist die Präferenz nicht stumm: jeder Lese-Job, der eine
    Verbindung liest, legt neben seinem Ergebnis den Reverse-Report dieser
    Verbindung ab (Notes samt `R204`/`R205`, übersprungene Objekte), unter
    der eigenen Art `REVERSE_REPORT` und in fester Reihenfolge der Verweise;
    das Ergebnis trägt sich zuletzt in seinen Index ein — gepinnt an der
    echten Fabrik und den Workern, durch den MCP-Client (`:test:e2e-cli`, samt
    Filter per Art) und im Harness gegen das gebaute Image, dessen Wächter
    nachweislich anschlägt (Sabotage HC; ohne den Blocker-Fix grün).

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
   | P10 (dritter Bauabschnitt, zurückgenommen) | `:hexagon:ports-common` (Fähigkeit), die fünf Treibermodule (Werte), `:hexagon:application` (Naht) | `make docker-check` |
   | P11 | `:hexagon:application` (Semantik, Job-Worker), `:adapters:driving:cli`, `:adapters:driving:mcp`; geteilte Signatur → einmal ohne `MODULES`; Job-Pfad durch den MCP-Client in `:test:e2e-cli` | `make docker-check`, `make integration` |
   | P10 (F3) | `:hexagon:ports-read` (Präferenz), `:adapters:driven:driver-common` (`R205`), MySQL- und SQLite-Treiber, `:hexagon:application`, `:adapters:driving:cli`, `:adapters:driving:mcp`; Rücknahme der Fähigkeit in `:hexagon:ports-common` und allen fünf Treibern; der echte MySQL-Reverse in `:test:integration-mysql` | `make docker-check`, `make integration` |
   | F1, L1, I1 | `:hexagon:core` (Art), `:hexagon:application` (Publisher-Port), `:adapters:driving:mcp` (Artefakte, Golden); Job-Artefakt durch den MCP-Client in `:test:e2e-cli` | `make docker-check`, `make golden-update`, `make integration` |
   | L-3, INFO (fünfter Bauabschnitt) | `:hexagon:ports-read` (Herkunft), `:adapters:driven:driver-common` (Note), MySQL- und SQLite-Treiber, `:hexagon:application`, `:adapters:driving:cli` (Resolver, Wiring, `mcp serve`) | `make docker-check` |
   | M-2 | `:hexagon:ports-read`, `:hexagon:application` (Worker, geteilte Signatur → einmal ohne `MODULES`), `:adapters:driving:mcp`; Report durch den MCP-Client in `:test:e2e-cli` | `make docker-check`, `make integration` |
   | E2E-Harnesses | `examples/mcp-e2e` (Skripte, Fixture, Erwartungen), `make/mcp-e2e.mk`, Workflow | `make mcp-e2e-roundtrip`, `make mcp-e2e-compare-matrix`, `bash -n`, shellcheck |
   | Korrekturabschnitt 6 | `:hexagon:core` (Art, geteilt → einmal ohne `MODULES`), `:hexagon:application` (Ablagereihenfolge), `:adapters:driving:mcp` (Publisher, Upload, Golden), `:adapters:driven:driver-common` und `-sqlite` (`R202`); Art durch den MCP-Client in `:test:e2e-cli`; Harness-Wächter gegen sabotierte Images | `make docker-check`, `make golden-update`, `make integration`, beide Harnesses |

   **Integrationsmodule:** Posten 4 (MySQL-Reader) ist in den Reader-Slice
   gewandert. Seit P11 läuft `:test:e2e-cli` mit (der Job
   `schema_compare_start` durch den MCP-Client, seit F1 samt Art und Form des
   Artefakts), seit dem vierten Bauabschnitt `:test:integration-mysql` (die
   Präferenz am echten MySQL-Reverse) und `:test:integration-sqlite` (der
   Reverse ist dort unverändert).
   Zur Erinnerung für alles Künftige: ohne `-PintegrationTests` überspringen
   sich die Integrations-Tasks **lautlos** und Gradle meldet trotzdem
   `BUILD SUCCESSFUL`.

2. **Sabotage je Paket** — Fix zuruecknehmen, Fehlschlag sehen, zuruecksetzen.
   Und die Ruecknahme danach **verifizieren**: die Ausgabe lesen, nicht annehmen.

3. **Der Konsumenten-Repro ist die Abnahme**: PG-Reverse gegen MSSQL-, MySQL-
   und SQLite-Reverse. Die **Paare ausschreiben** — „Dreier-Matrix" heisst im
   Dokument sonst die Comparator-Matrix (PG↔MSSQL, PG↔MySQL, MSSQL↔MySQL), und
   SQLite kommt in keinem Posten vor: es ist der **Nullfall** (dort gibt es
   keine Kanonisierung zu prüfen). Seit dem fünften Bauabschnitt fährt
   `examples/mcp-e2e` die Matrix über MCP (`smoke-compare-matrix.sh`, 5x5 mit
   Oracle als Opt-in); der Roundtrip dort vergleicht weiter die Quelle gegen je
   einen Reverse.

4. **Der Harness mit gepinnten Erwartungen ist ein anderer**:
   `examples/sample-db/expected/pagila-smoke.compare.txt` plus Byte-Diff-Abbruch
   (`examples/sample-db/scripts/smoke.sh:182`; die Baseline steht in Zeile 30).
   Der Roundtrip in `examples/mcp-e2e` pinnt nicht (er zeigt die Funde und
   verbietet Fehlalarm-Klassen); die Compare-Matrix dort pinnt ihre Zellen
   versionsgebunden in `examples/mcp-e2e/expected/compare-matrix.env`.

5. **Vertrags-Gates:** `make docs-check` (P7 fasst `spec/` an),
   `make doc-immutable RANGE=origin/main..HEAD` (P7 ändert eine ADR-Statuszeile —
   und muss **rot** werden, wenn jemand `ADR 0053` ohne Statusänderung im Kern
   anfasst) und `make solid-suppression-gate`.

## Offen (nicht Teil dieses Slices)

**Seit dem dritten und vierten Bauabschnitt erledigt oder entschieden**
(bleiben hier, damit die Querverweise stimmen):
- **Die zweite MCP-Oberfläche** (`schema_compare_start` verglich wortgleich
  und veröffentlichte den rohen `SchemaDiff`) — entschieden und gebaut als P11;
  das Artefakt hat seit dem vierten Bauabschnitt die eigene Art `COMPARE`
  (F1), in derselben Form wie das Überlauf-Artefakt von `schema_compare` (L1).
- **`schema_compare` (MCP) bereinigte keine Reverse-Markierung** — gebaut
  (P11b), für beide MCP-Wege, gepinnt; seit M1 (Review Runde 3) ist Name und
  Version kein Fund, sobald **eine** Seite die Markierung trägt, in allen drei
  Oberflächen.
- **`legacy_serial_syntax` zwischen PostgreSQL und MySQL** — zuerst als
  Vergleichs-Faltung gebaut (P10, dritter Bauabschnitt), auf Eigner-Entscheidung
  (F3) zurückgenommen und als Reverse-Präferenz `serial`/`identity` gebaut.
- **`schema_compare_start` im ADR** — erledigt: ADR 0057 (P11, die
  Herkunftsregel, F1–F4) ist seit `f6bab1514` `accepted`.
- **Die Präferenz über MCP war stumm** (Review Runde 4, M-2) — gebaut: der
  Reverse-Report der Lese-Jobs (fünfter Bauabschnitt).
- **Ein Tippfehler in einer Lese-Präferenz fiel still auf den Default** (L-3)
  — gebaut: Konfigurationsfehler, wie bei der Schreib-Präferenz.
- **Die Art des Reverse-Reports** — entschieden (Eigner, 2026-09-17): die
  eigene Art `REVERSE_REPORT`, gebaut im sechsten Bauabschnitt.
- **Der Workflow der Matrix war best-effort** — entschieden (Eigner,
  2026-09-17): rot sichtbar, kein Pflicht-Check, mit Artefakt-Upload.
- **Der Sequenz-Wächter der Matrix war blind** (Verifier HB) — gebaut: der
  Matrix-Server liest `identity` für MySQL; HB macht die Matrix rot.
- **Der Identity-Modus zwischen SQL Server und den anderen** — entschieden
  (Eigner, 2026-09-16): bleibt ein Fund, ein Fähigkeitsunterschied
  (`W140`); als Grenze in `spec/cli-spec.md`.
- **Der `postgis`-Dienst der Sample-DB** — korrigiert (`47f8a8641`),
  `make sample-db-spatial-smoke` grün.
- **Die Grenze bei verlustbehafteten Readern** (P9, erste Fassung) —
  widerlegt und korrigiert (H1, M1).

**Offen — bei der Graduation verortet** (der Ort je Punkt steht unter
„Restflächen" oben; die Liste bleibt als Stand vor der Graduation):
- **Der Schlüsselwort-Case** (`sum` gegen `SUM`) — Eigner-Frage. Seit dem
  2026-09-17 hat sie einen Ort: der Plan
  [`../next/compare-toleranzprofil.md`](../next/compare-toleranzprofil.md)
  führt sie als Kandidat K1 (Toleranz statt einer Entscheidung für alle),
  ebenso `RESTRICT` gegen implizit (K4) und den Identity-Modus (K2, s. unten).
- **`= ANY(ARRAY[…])` gegen `IN (…)`** — keine offene Frage, sondern eine
  **ADR-entschiedene**: [`ADR 0055`](../../adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md)
  setzt sie für den zielbewussten Vergleich gleich und lässt `schema compare`
  streng. Sie zu verschieben wäre eine Statusänderung an 0055 — dieselbe Linie
  wie P7, aber eine **andere** Entscheidung.
- **F4 — Messauftrag: die übrigen server-vergebenen Namen.** Nach der
  Eigner-Entscheidung vom 2026-09-17 nimmt `schema compare` nur den
  Identity-Sequenznamen aus (P6). `DialectCapabilities.namesFullTextIndexes`,
  `namesPartitions` und `namesSingleColumnConstraints` wertet weiterhin nur
  der zielbewusste Vergleich aus. **Nicht gebaut.** Zu messen ist zuerst, ob
  zwei Reverses verschiedener Dialekte dort Fehlalarme melden (Namen, die ein
  Server vergibt oder ein Reader erfindet — etwa SQL Servers `p1`, `p2` …
  oder ein synthetischer Name einer einspaltigen Einschränkung); erst mit
  einem gemessenen Paar ist zu entscheiden, ob die Kategorie aus P6 dort gilt.
- **Die Präferenz `serial`/`identity` pro MCP-Aufruf.** Über MCP trägt sie nur
  die Konfigurationsdatei des Servers; ein Tool-Argument an
  `schema_reverse_start` (und `schema_compare_start` mit Verbindungen) als
  Pendant zum CLI-Flag ist nicht gebaut. Ein Abnehmer mit verschiedenen
  Wünschen je Aufruf braucht dafür einen eigenen Schnitt (Tool-Schema,
  Idempotenz-Fingerabdruck).
- **`schema migrate` gegen MySQL plant ein wirkungsloses `MODIFY COLUMN`**,
  sobald das Soll kein `legacy_serial_syntax` trägt — aus **zwei** Ursachen
  (Review Runde 4, M-1): die zielbewusste Naht wertet das Flag gegen MySQL,
  obwohl der Generator beide Formen gleich rendert (trifft auch ein
  handgeschriebenes Soll ohne Präferenz, schon mit 1.7.1), und der Ist-Stand
  wird ohne Präferenz gelesen. Eine Faltung in der Naht berührt Abdruck und
  Overlay-Bindung ([ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md),
  Entscheidung 3) — nicht hier gebaut. Die Analyse samt Spannung zu
  `spec/dialect-preference-mechanism.md` steht in
  [`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md).
- **`artifact_upload_init` nimmt mehr Arten an, als die Spec nennt.** Die
  Spec zählt `schema`, `ddl`, `transform-script`, `seed-data`, `rules` und
  `generic` auf; der Handler nimmt zusätzlich jeden Namen aus `ArtifactKind`
  (etwa `diff`, `profile`). Seit F1 ist `COMPARE` davon ausgenommen (nur der
  Server erzeugt es); die übrige Nachsicht ist älter und nicht Teil dieses
  Slices.
- **`schema compare` meldet `DIFFERENT` mit „0 change(s)", wenn sich nur Name
  oder Version unterscheiden** (zwei handgeschriebene Schemata): die
  Zusammenfassung zählt die Metadaten nicht mit. Beim Bau von M1 gesehen, nicht
  geändert.
- **MCP `schema_compare` validiert nicht.** Die CLI endet bei einem ungültigen
  Schema (`E012`) mit Exit 3, das MCP-Werkzeug und der Job vergleichen
  trotzdem. Bewusst nicht Teil von P11 (Eigner); eine eigene Frage.
- **Der Identity-Modus gegen MySQL und SQLite.** Dieselbe Klasse wie `W140`,
  in der anderen Richtung: der MySQL-Generator rendert `GENERATED ALWAYS` als
  `AUTO_INCREMENT` **ohne** Warnung, der Reverse liest `by_default`. Im Repro
  mit dem Schema des Konsumenten die übrigen Identity-Funde PG↔MySQL und
  MSSQL↔MySQL — auch mit der Präferenz `identity`. Spec und Handbuch nennen es
  seit dem vierten Bauabschnitt ausdrücklich (Review L3); als Toleranz steht
  der Modus im Plan
  [`../next/compare-toleranzprofil.md`](../next/compare-toleranzprofil.md)
  (K2). Ob der MySQL-Generator bei `ALWAYS` warnen soll (wie `W140`), ist
  eine eigene Frage ohne Ort.
- **Der Typ einer berechneten Spalte in SQL Server** — wandert laut
  Eigner-Entscheidung (2026-09-16) in den
  [Reader-Slice](../next/reader-treue-4-mssql-berechneter-typ.md); SQL Server führt
  keinen deklarierten Typ, der Reverse liest den abgeleiteten
  (`decimal(23,2)` statt `decimal(14,2)`). Steht dort als Posten D1; hier
  nicht gebaut.
- **`numeric` ohne Präzision liest der PostgreSQL-Reverse als `float`** — ein
  Reader-Verlust (s. P9, Grenze): zwei verschiedene Spaltentypen sehen im
  Modell gleich aus, der Spaltentyp-Vergleich ist dort blind. Steht im
  [Reader-Slice](../next/reader-treue-2-meldungen.md) als Posten D2.
- **`varchar` ohne Länge ist im Modell `text`.** Seit M1 bleibt PostgreSQLs
  `(spalte)::text` an einer solchen Spalte ein Fund; ebenso liest der Reverse
  `inet`/`interval` als `text` (`R301`). Dieselbe Reader-/Modell-Frage wie
  der vorige Punkt; im Reader-Slice Posten D3.
- **Unbenannte Indizes und die Schreibweise.** Der Zuordnungsschlüssel eines
  unbenannten Index trägt das rohe Prädikat (P5, „Grenze"); ob `schema compare`
  ihn über die kanonische Form bilden soll, ist nicht entschieden. Heute ist
  das Ergebnis konservativ: entfernt + hinzugefügt statt „unverändert".
- **Eine Partitionierungs-Änderung hat keinen MCP-Fund.** `TableDiff` trägt
  `partitioning`, die Projektion von `schema_compare` kennt dafür keinen Code:
  eine Tabelle, die sich nur darin unterscheidet, ergibt `status: different`
  ohne Eintrag in `findings`. Beim Bau von P2b gefunden, nicht Teil dieses
  Slices. Als bekannte Lücke gepinnt (`ObjectDiffFieldsCompletenessTest`):
  kommt ein Fund dazu, fällt der Test auf und die Ausnahme dort weg.
- **Reverse-Umfang CLI gegen MCP.** `schema reverse` liest Sichten und
  Routinen nur mit `--include-*`, `schema_reverse_start` immer; mit PostGIS in
  `public` trägt der MCP-Reverse ~1000 Funktionen (im Repro gemessen, beim
  Konsumenten bekannt und umgangen). Kein Compare-Thema.
- **Die Anwendersicht.** Der Slice hat `docs/user/` angefasst (s. P7,
  Korrektur; dritter Bauabschnitt, L3); Fund-Pfade, `details` und die
  Faltungsmenge stehen in `spec/`, nicht im Handbuch. **Eine Ausnahme wandert
  mit:** der Posten C1/P6 im Reader-Slice verschiebt die Grenze von `E012`, und
  die steht im Anwenderhandbuch (`docs/user/anwenderhandbuch.md:2198`) — dort
  zieht der Reader-Slice mit.
- **Oracle im Konsumenten-Repro und in den Harnesses** — nicht gefahren. Der
  laufende `mcp-e2e-oracle-1` gehört zum Compose-Projekt des Harness, ist aber
  ein fremder Container: der Opt-in (`make mcp-e2e-roundtrip-oracle`,
  `make mcp-e2e-compare-matrix-oracle`) würde ihn übernehmen und seine
  Objekte löschen. Die Oracle-Zellen der Matrix sind deshalb nicht gepinnt;
  `make mcp-e2e-down` lässt ihn stehen (das Netz bleibt dadurch belegt).
- **Der Integrations-Workflow läuft ohne `--continue`.** Ein Ausreißer in
  einem Modul verdeckt deterministische Fehler der übrigen — so an
  `e3116c34c` (FTS-Container startete nicht, `:test:integration-mysql:test`
  lief nicht). Eine CI-Frage, nicht dieses Slices.
- **Das FTS-Test-Image pinnt `mssql-server` nicht**, nur das FTS-Paket; die
  Basis wandert mit dem Upstream-Tag. Kandidat für den Ausreißer oben.
- **`make doc-immutable` friert `superseded`-ADRs nicht ein.** Nach dem
  Statuswechsel fiele eine Kernänderung an 0053 keinem Gate auf (s. P7,
  DoD 3). Eine Frage an d-check.
- **Der Index `diffs` ist in `mcp serve` leer.** `McpRuntimeWiring` verdrahtet
  `EmptyDiffStore`; `diff_list` findet das Artefakt von
  `schema_compare_start` nicht (Review Runde 4 gemessen, vorbestehend). Die
  Spec beschreibt seit dem sechsten Bauabschnitt den **Vertrag** (der Index
  führt das Compare-Artefakt mit beiden Verweisen, Review Runde 5, LOW-4);
  `mcp serve` erfüllt ihn nicht. Für `profile_list` gilt dieselbe Verdrahtung
  (`EmptyProfileStore`, nicht gemessen).
- **Der `job_input`-Upload ist über die Leitung nicht erreichbar:**
  `artifact_upload_init` kennt `approvalKey` und `artifactKind` in seinem
  Eingabeschema nicht (Review Runde 4).
- **`data transfer` gibt keine Reader-Notes aus.** Der Transfer liest beide
  Schemata und verwirft deren Notes; `--sqlite-autoincrement-width 64` bleibt
  dort ohne `R204` — „Nicht stumm" gilt für diesen Weg nicht. Beim Bau von L-3
  gesehen.
- **Befunde der Compare-Matrix, die nicht der Vergleich sind** (gepinnt als
  Zustand `APPLY-FAIL`): der SQL-Server-Reverse liest eine berechnete Spalte
  mit T-SQL-Quoting (`[quantity]*[unit_price]`), und die
  Portabilitätsprüfung (`E053`) erkennt das nicht — PostgreSQL und MySQL
  lehnen die DDL ab; der SQLite-Reverse kennt keine Länge, und MySQL
  indiziert das eindeutige `TEXT` nicht (`ERROR 1170`); der SQLite-Reverse
  nennt die Fremdschlüssel jeder Tabelle `fk_0` …, und SQL Server verlangt
  eindeutige Namen (`Msg 2714`). Ort vermutlich der Reader-Slice (Namen,
  Längen) und der Generator (Portabilität).
- **`schema_list` filtert `jobId` gegen die Job-URI.** Die bloße Kennung aus
  `schema_reverse_start` findet nichts; die Spec nennt nur „`jobId`". Das
  Handbuch-Beispiel ist auf den Ist-Zustand korrigiert, die Vertragsfrage
  (Kennung oder URI) ist offen.
- **Usage-Fehler enden mit Exit 1 statt 2** (Review Runde 5, M-3).
  Ungültige Flag-Werte und andere Clikt-Usage-Fehler laufen über
  `buildRootCommand().main(args)` (`Main.kt`) und enden mit Clikts Exit 1;
  `spec/cli-spec.md` (Exit-Code-Tabelle) und der Troubleshooting-Leitfaden
  sagen 2. Gemessen an `--mysql-autoincrement-syntax identiy` und
  `--sqlite-autoincrement-width 16`. Vorbestehend und global, ein Ticket.
- **Die Geschwister-Workflows laufen mit job-weitem `continue-on-error`:**
  die sechs Sample-DB-Cross-Smokes (`sample-db-cross-smoke*.yml`), dazu
  `sample-db-smoke`, `-sqlite-smoke`, `-spatial-smoke`, `-scale`,
  `bi-demo-smoke`, `mcp-e2e-smoke` und `perf-acceptance` — ein Fehlschlag
  bleibt dort ein grüner Haken. Die Compare-Matrix ist seit dem sechsten
  Bauabschnitt rot sichtbar; die Geschwister sind auf Eigner-Anweisung nicht
  angefasst — ein `open/`-Ticket bei der Graduation
  ([`../open/ci-verdeckte-fehlschlaege.md`](../open/ci-verdeckte-fehlschlaege.md)).
- **Shell, Kotlin und YAML haben kein statisches Sicherheits-Gate.**
  `make semgrep` fährt zwei Regeln auf fünf Dateien (Verifikation Runde 5);
  shellcheck ist kein Repo-Gate (hier per Container gefahren;
  `smoke-scope-matrix.sh` trägt zwei vorbestehende Befunde).
- **Native Typ-Seeds und der Silent-Loss-Check der Compare-Matrix** — stehen
  seit dem sechsten Bauabschnitt im
  [Reader-Slice](../next/reader-treue-1-matrix-abnahme.md) unter
  „Verifikation" (Punkt 5) als Abnahme seiner Pakete (Klassen aus A5, B4,
  D3). Die Matrix wendet `fixtures/seeds/<dialekt>.sql` bereits an, wenn es
  die Datei gibt.

## Was der Slice bewusst nicht tut

Er entscheidet **keine** Grenzfrage: die zwei verbliebenen gehören dem Eigner,
die dritte ist ADR-entschieden. Er behebt, was unstrittig falsch ist — und trägt
die Begründung mit: ein fehlendes Vorher/Nachher (P1), zwei Pfad-Schemata
(P2a/P2b), **redundante** Klammern um einen Operanden (P3, samt der Begründung,
warum sie redundant sind), zwei fehlende Faltungszweige (P5), einen
Servernamen, der als Schema-Eigenschaft gewertet wurde (P6), ein Flag, das der
Reverse ohne Aussage setzte und das jetzt der Anwender am Reverse erklärt
(P10), und drei Oberflächen mit verschiedener Semantik (P11). Und er bewegt
dabei eine ADR-Linie — das ist kein Nebeneffekt, sondern P7.

**Nicht mehr hier:** Posten 4 (MySQL-Reader) ist am 2026-09-16 in den
[Reader-Slice](../next/reader-treue-1-matrix-abnahme.md) gewandert, als Posten C1 mit
Paket P6. **Nicht behoben** wird die Umschreibung `= ANY(…)` gegen `IN (…)`:
sie ist in ADR 0055 entschieden.

## Closure

**Graduiert 2026-09-17.** Alle Pakete sind gebaut (P1–P3, P5–P11; P4 ist in
den Reader-Slice gewandert), in sechs Bauabschnitten und fünf Review- und
Verifikationsrunden. Released ist es noch nicht: die Wirkung steht in
`CHANGELOG.md` unter `[Unreleased]`. Offen bleibt in diesem Slice nichts; jeder
verbliebene Punkt hat unter „Restflächen" einen Ort außerhalb.

**Woran „fertig" gemessen ist** — am Vertrag, nicht an diesem Plan:

- [LF-015](../../../spec/lastenheft-d-migrate.md#lf-015) (Schema-Vergleiche
  zwischen Umgebungen): zwei Reverses verschiedener Dialekte melden keine
  Unterschiede mehr, die nur Schreibweise oder Herkunft sind; was bleibt, ist
  ein Fähigkeitsunterschied, ADR-entschieden oder eine Eigner-Frage mit Ort.
- [ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md)
  übersteuert [ADR 0053](../../adr/0053-vergleich-rohen-sql-texts.md)
  (Statuszeile in `c9737f909`). Seine „Bestätigung" ist eingelöst: jede
  Faltungsregel mit Gegenprobe (`ExpressionCanonicalisationTest`,
  `ViewQueryCanonicalisationTest`, `SpellingFoldBoundaryTest`,
  `ColumnCastFoldTest`), die Rückzugsfälle gepinnt, der Migrate-Pfad
  nachweislich streng (`SchemaMigrateComparatorsTest`), Sabotage je Regel
  (Protokolle des zweiten bis vierten Bauabschnitts).
- [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md)
  (`accepted` seit `f6bab1514`): eine Semantik an einer Stelle, Herkunft kein
  Unterschied, das Compare-Artefakt. Die Tests seiner „Bestätigung" existieren
  und sind je Sabotage rot geworden (dritter und vierter Bauabschnitt).
- [ADR 0027](../../adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md)
  und `spec/dialect-preference-mechanism.md`: `legacy_serial_syntax` löst eine
  Präferenz am Reverse, nicht der Vergleich; „Nicht stumm" gilt auch über MCP
  (Reverse-Report der Lese-Jobs).
- Die Spec beschreibt das Gebaute: `spec/cli-spec.md` (Faltungsmenge, Grenze
  und Rückzug, Pfad-Schema der Funde, Reverse-Markierung, Lese-Flags, Form des
  Reverse-Reports), `spec/mcp-server.md` (Funde, Artefakt-Arten `COMPARE` und
  `REVERSE_REPORT`, Artefakte der Lese-Jobs, Index `diffs`),
  `spec/connection-config-spec.md`. `docs/user/` beschreibt den Ist-Zustand
  (Anwender- und Administrationshandbuch, API-Referenz).

**Paket → Commit**

| Abschnitt | Paket | Commit |
| --- | --- | --- |
| Aktivierung | Eigner gibt die Linie von ADR 0053 im vollen Umfang frei | `1f5503e70` |
| 1 | P7, ADR-Teil: ADR 0056, 0053 auf `superseded` | `c9737f909` |
| 1 | P8 (Altbestand) · P5 · P3 · P6 · P2a · P2b · P1 | `50ee1bd00` · `3b30d9f8a` · `ba263c737` · `10eb5a1df` · `a723584ff` · `b1de205f9` · `e08e217fb` |
| 1 | P7, Spec-Teil | `26ff678ed` |
| 1 | Nebenbefund: `make doc-immutable` lokal still grün | `dbb50a666` |
| 2 | P9 nachgetragen (Plan) | `dbb8ef117` |
| 2 | P9 samt Rückzug, Schlüsselwörtern, Lexik (A–D, H) | `13e397475` |
| 2 | MCP-Werte in Dokument-Schreibweise, Index-Pfad (E, F) | `58510584d` |
| 2 | Absicherung am echten Migrate-Pfad, Vollständigkeit je Feld (G) | `522722ad3` |
| 2 | Sample-DB: PostgreSQL-18-Mount | `0f39332d9` |
| 2 | Handbuch im Ist-Zustand, Abnahme am Repro (I, J) | `2913ab7fd` |
| 2 | Gates, Partitionierungs-Lücke gepinnt; Eigner-Entscheidungen (Plan) | `d14f7021b`, `5986d25dd` |
| 3 | Faltungsgrenzen (H1, M1, L1, INFO 5, INFO 6) | `175800393` |
| 3 | P10, erste Fassung (Vergleichs-Faltung; im vierten Abschnitt zurückgenommen) | `b3e583522` |
| 3 | P11 samt M3 | `2601d1631` |
| 3 | MCP-Funde (L2, INFO 3, INFO 4) · Handbuch (L3) · PostGIS-Mount | `ff4d56082` · `f8c819f6c` · `47f8a8641` |
| 3 | Plan | `c946c2f6e` |
| 4 | P10 als Reverse-Präferenz `serial`/`identity` (F3) | `5a9eaf0a2` |
| 4 | Name und Version kein Fund bei einer Reverse-Seite (M1) | `0e0e1cb24` |
| 4 | Artefakt-Art `COMPARE` in einer Form, typisierter Publisher (F1, L1, I1, I5) | `694b88776` |
| 4 | Doku (L2, L3, I2–I4) · Handbuch `schema migrate` mit `identity` · MySQL-Integrationsfall | `e3116c34c` · `fe6b3d270` · `542008cbd` |
| 4 | Plan | `d3ef2d77d` |
| 4 | P7, zweiter ADR: ADR 0057 `accepted` | `f6bab1514` |
| 5 | Lese-Präferenzen streng, mit Herkunft, `mcp serve` einmal (L-3, INFO) | `49a3b3d4c` |
| 5 | Reverse-Report der MCP-Lese-Jobs (M-2) | `f345e00f7` |
| 5 | Spec, Handbuch, Ticket, CHANGELOG (M-1, L-1, L-2, INFO) | `a1a02b919` |
| 5 | E2E-Harnesses: Roundtrip-Wächter, 5x5-Compare-Matrix, Workflow | `c7bfe88dd` |
| 5 | Plan | `2299f0cf5` |
| 6 | Artefakt-Art `REVERSE_REPORT` | `df098292c` |
| 6 | Lese-Jobs legen ihr Ergebnis zuletzt ab | `c803dda52` |
| 6 | `R202` an der Stelle der Deklaration | `453722a34` |
| 6 | Harness-Blocker, Pin-Schutz, Selbstprobe, sehender Sequenz-Wächter, Workflow rot sichtbar | `328479ba4`, `61ab4aaae` |
| 6 | Spec, Handbücher, CHANGELOG (M-3, LOW-2..4, INFO) | `d5e0c5f9b` |
| 6 | Plan | `6bd249365` |
| Graduation | Orte in `open/` · Nachträge in bestehenden Plänen · Move mit Closure | `2a3820362` · `58d3335fa` · der Move-Commit |

Im selben Zeitraum, aber **nicht** dieser Slice: `00c738c27` (Gradle-Wrapper
entfernt), `42f91a8a5` und `5ffa09bf1` (Toleranzprofil geschnitten,
`next/`-Bestand), `076d6c955` (Reader-Slice nimmt A6/B3 und D1–D3 auf).

**Was über den Entwurf hinausging**

- **P8 und P9 kamen dazu.** Die Faltung aus 1.7.0/1.7.1 setzte Verschiedenes
  gleich (Kommentar, Dollar-Quoting, jeder Cast, kein Literalschutz im
  Sichten-Rumpf); P8 baute ein gemeinsames Gerüst mit Rückzug. P9 wurde neu
  gefasst, nachdem eine Messung gegen PostgreSQL zeigte, dass ein Cast die
  umgebende Operation umtypt: ein Cast fällt nur als Operand eines Vergleichs
  und nur mit dem Spaltentyp dieser Seite. Runde 2 zog die Regel an zwei
  verlustbehafteten Readern weiter zurück (Gleitkomma, `R301`).
- **P10 wurde revidiert.** Die erste Fassung faltete `legacy_serial_syntax` im
  Vergleich. Der Architekt fand im ADR-Entwurf den Widerspruch zu
  `spec/dialect-preference-mechanism.md` („nie im nachgelagerten Vergleich");
  der Eigner entschied (F3): zurück, stattdessen die Reverse-Präferenz
  `serial`/`identity` nach ADR 0027 — streng, mit Herkunft, auch für
  `db:`-Operanden und `mcp serve`.
- **P11 und ein zweiter ADR.** Die drei Oberflächen von `schema compare`
  verglichen verschieden (der Job wortgleich, MCP ohne Entfernen der
  Markierung). Jetzt eine Stelle (`SchemaCompareSemantics`), festgeschrieben in
  ADR 0057; der Entwurf kannte nur den einen ADR, der 0053 übersteuert.
- **Zwei neue Artefakt-Arten.** `COMPARE` (Job-Ergebnis und Überlauf von
  `schema_compare`, eine Form, ungekürzt) und `REVERSE_REPORT` (der
  Reverse-Report der Lese-Jobs — ohne ihn war die Präferenz über MCP stumm).
- **E2E-Harnesses in `examples/mcp-e2e`.** Roundtrip-Wächter nach Klassen
  (`notation`, `metadata`, `sequence`, Selbstprobe der Form) und eine
  5x5-Compare-Matrix über MCP mit versionsgebundenen Erwartungen, Pin-Schutz
  und einem rot sichtbaren Workflow.
- **Beim Bauen mitbehoben:** das Überlauf-Artefakt entstand nur über die
  Byte-Grenze; `data transfer` fing eine ungültige Oracle-Präferenz nie ab
  (Stacktrace, Exit 1 statt 7); die Sample-DB startete unter PostgreSQL 18
  nicht (beide Dienste); der Report-Wächter der Matrix schlug nie an, weil
  sein Eintrag in einer Subshell verloren ging.

**Abnahme**

- **Konsumenten-Repro** (Schema des Konsumenten, PostgreSQL 18.6 mit
  PostGIS 3.6, MySQL 9.7.2, SQL Server 2025, SQLite mit SpatiaLite; Oracle
  nicht): Funde von `schema_compare` 1.7.1 → jetzt PG↔MSSQL 23 → 17,
  PG↔MySQL 20 → 19, PG↔SQLite 36 → 35, MSSQL↔MySQL 19 → 18, mit und ohne
  Präferenz gleich; `schema_compare_start` liefert dieselben Einträge unter
  `COMPARE`. Kein Namens- oder Versionsfund, kein Platzhalter. Mit `identity`
  entfällt das Serial-Flag an fünf Spalten; der Modus bleibt (`GENERATED
  ALWAYS` gegen `by_default`). Sonde `BY DEFAULT`: mit `identity` kein
  Identity-Fund, ohne fünf. Am nachgebauten Schema (CLI): Posten 3 in PG↔MSSQL
  und PG↔SQLite geschlossen, Posten 5 ohne reine Schreibweise-Differenz,
  Posten 1 mit Vorher/Nachher, Posten 2 im Pfad-Schema, Posten 6 für `serial`
  und mit der Präferenz für `IDENTITY`.
- **5x5-Compare-Matrix** (MCP, `d-migrate:dev` 1.8.0-SNAPSHOT, Oracle nicht):
  PostgreSQL → MySQL 6, → SQL Server 5, → SQLite 13; SQL Server → SQLite 9;
  SQLite → PostgreSQL 3; MySQL als Quelle `INVALID` (Posten 4); vier
  `APPLY-FAIL`-Zellen aus Reader und Generator. Kein Wächter schlägt an;
  `OR`/`IS NULL`, `numeric > 0`, Index-Prädikat und Sequenzname melden
  nirgends. Roundtrip: PostgreSQL 1, MySQL ungültig, SQL Server 5, SQLite 6.
  Die Wächter sind nachweislich scharf (Sabotagen HC, HB, WK, WF, JQ, H2′;
  ohne den Blocker-Fix grün).
- **Sample-DB:** `make sample-db-smoke` gleich der Baseline
  (`IDENTICAL`), `make sample-db-spatial-smoke` grün.
- **Abschluss-Verifikation** (nach dem sechsten Abschnitt, im eigenen
  `--no-local`-Klon, 2026-09-17): graduationsreif. HC und HB gegen selbst
  gebaute Images rot, der Neu-Pin-Schutz schreibt bei einem Generate-Exit 7
  nichts, drei Unit-Sabotagen rot (eine nur über `:test:e2e-cli` — der
  `artifact_list`-Filter ist allein dort gepinnt). Eine Lücke fand sie: die
  Regel „das Ergebnis trägt sich zuletzt ein" war für den Compare-Worker nur
  mit **einer** Verbindungsseite gepinnt (Sabotage „Quell-Report, Ergebnis,
  Ziel-Report" überstand alle Tests). Nach der Graduation geschlossen: ein Fall
  mit zwei Verbindungsseiten und scheiterndem Ziel-Report in
  `SchemaCompareJobWorkerTest` (auf HEAD grün, mit der Sabotage rot).
  Nebenbefund ohne eigenen Ort: `make mcp-e2e-up` scheitert in einem frischen
  Klon an der fehlenden `.env`; die Harness-Skripte legen sie selbst an.
- **Gates** des letzten Bauabschnitts stehen dort: `make docker-check` je Modul
  und einmal ohne `MODULES` (12 213 Tests, 0 Fehler), `make integration`
  (`:test:e2e-cli`; im vierten und fünften Abschnitt auch
  `:test:integration-mysql` und `:test:integration-sqlite`),
  `make golden-update`, `make docs-check`, `make solid-suppression-gate`,
  `make doc-immutable` im frischen `--no-local`-Klon.
- **CI, Stand bei Graduation:** `f6bab1514` (damals `origin/main`) ist
  vollständig grün — `Build & Test`, `Integration Tests`, `Per-Module
  Coverage`, `Dependency Submission`. Die zwölf Commits danach (`49a3b3d4c`
  bis `6bd249365`) und die drei Graduations-Commits waren bei der Graduation
  nicht gepusht; ihr CI-Lauf und der erste Lauf des Matrix-Workflows standen
  zu diesem Zeitpunkt aus (Beobachtungspunkt in
  [`../open/ci-verdeckte-fehlschlaege.md`](../open/ci-verdeckte-fehlschlaege.md)).

**Was von diesem Slice lesenswert bleibt.** Die zwei größten Korrekturen kamen
aus Messung und Nachlesen, nicht aus dem Plan: P9 hielt einer Messung gegen
PostgreSQL nicht stand, P10 nicht dem Abgleich mit der eigenen Spec. Und ein
Wächter, der nie anschlägt, sieht genau aus wie ein grüner Lauf — erst die
Sabotage gegen ein präpariertes Image hat den Blocker gezeigt.
