# Reader-Treue: stille Verluste und Fremdes im Reverse und am Generator (Umbrella)

> **Status:** **In Arbeit seit 2026-09-17** (Plan 1 gebaut; die Pläne 2 bis 4
> stehen weiter in [`../next/`](../next/)), **Umbrella über vier Pläne**
> (Schnitt 2026-09-17, Eigner-Entscheidung „E-Schnitt"). Eingearbeitet sind die Befunde
> aus Plan-Review und Architektur-Prüfung (gegen `da29034b1`); die Anker sind
> gegen `90c6c234f` nachgemessen. Der Umbrella ersetzt den ungeschnittenen
> Plan. Dieser liegt samt Vorgeschichte (Review-Runden 1 und 2,
> Aktivierungsschnitt) unter
> [`docs/archive/reader-treue-spatial-array-json.md`](../../archive/reader-treue-spatial-array-json.md);
> er ist durch die vier Pläne vollständig überholt
> ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md),
> „Lebenszyklus-Übergänge").
> **Vorbedingung / Gate:** für den Umbrella keins. Jeder Plan nennt sein
> eigenes; die offenen Eigner-Fragen stehen gesammelt unter „Offen".
> **Aktivierung:** Der Umbrella ist mit dem ersten Implementierungs-Commit von
> Plan 1 nach `in-progress/` gewandert und geht, mit einer
> `## Closure`-Sektion, nach `../done/`, sobald Plan 4 geliefert ist. Jeder der
> vier Pläne wandert einzeln.
> **Warum ein Umbrella und nicht der erste Plan:** Plan 1 ist als erster
> fertig und wandert als erster nach `done/`. Stünden Nenner, Messmethodik und
> Code-Tabelle dort, verwiesen die Pläne 2 bis 4 für ihre gemeinsamen Regeln auf
> einen abgeschlossenen Plan. ADR 0004 sieht für diesen Fall den Umbrella vor,
> der „Planung läuft weiter" und „erste Teile geliefert" zugleich trägt.

## Die vier Pläne

Die Reihenfolge ist Eigner-Entscheidung: 1 → 2 → 3 → 4.

| Plan | Pakete | Abnahme | Hängt ab von |
| --- | --- | --- | --- |
| [1 — Matrix als Abnahme](reader-treue-1-matrix-abnahme.md) | P6 (MySQL-Server-Text), P0 (Seeds und Silent-Loss-Check), S4 (SQLite-Migrate verliert FK-Aktionen), P12 (T-SQL-Quoting im Berechnungsausdruck), P11 (SQLite-Constraint-Namen) | Compare-Matrix, `:test:integration-mysql`, `:test:integration-sqlite`, `:test:integration-mssql` | — |
| [2 — Meldungen](../next/reader-treue-2-meldungen.md) | P5 (Array-Verlust MySQL und SQLite), P10 (`ALWAYS` ohne Entsprechung), P8 (`json`), P9 (ungebundene Zahl, unbekanntes Array-Element), P1 (Oracle-SRID), P3 (`search_path`); dazu S1 bis S3 (erst messen) | Matrix, Integrationsmodule je Dialekt | Plan 1 |
| [3 — Spatial-Treue](../next/reader-treue-3-spatial.md) | P4 (PostgreSQL `geography`, nur Rückweg, samt Datenpfad), P7 (SpatiaLite `NOT NULL`), P2a/P2b (Oracle- und PostGIS-Systemobjekte) | `:test:integration-postgresql` mit PostGIS, `:test:integration-sqlite`, `:test:integration-oracle`, `:test:e2e-cli`, Matrix | Plan 1; P4 auf P3 aus Plan 2 |
| [4 — Typ berechneter SQL-Server-Spalten](../next/reader-treue-4-mssql-berechneter-typ.md) | P13 (erst messen, dann nach der entschiedenen Regel) | `:test:integration-mssql`, Roundtrip-Harness, Matrix | P12 aus Plan 1 |

## Der gemeinsame Nenner

Zwei Muster tragen alle Posten.

1. **Fidelity: ein Verlust wird nicht gesagt.** Der Reader verliert eine
   Information oder der Generator verwirft eine Eigenschaft, ohne es zu
   melden. Oft meldet ein Schwesterdialekt denselben Verlust schon: `W149`
   (Oracle) und `W137` (SQL Server) melden den Array-Verlust, MySQL und SQLite
   nicht; SQL Server meldet seinen `geography`-Sonderfall mit `R345`,
   PostgreSQL nicht. Die Meldewege sind je Dialekt einzeln gebaut, nicht aus
   einer Naht.
2. **Modell-Reinheit: der Reader nimmt zu viel auf.** Fremdes kommt hinzu:
   Oracles interne Sequenzen und die PostGIS-Routinen als Anwenderobjekte,
   Server-Text mit Dialekt-Anhang (MySQLs Introducer, T-SQL- und
   Backtick-Quoting) als neutraler Ausdruck. Ein Filter, der Fremdes
   ausschließt, darf dabei kein Anwenderobjekt mitnehmen.

Das Ziel aller vier Pläne ist ein Satz: **Kein Verlust bleibt still.** Wo
Information nicht erhalten werden kann, wird sie benannt, mit dem Mechanismus,
den die Schwesterdialekte schon benutzen. Wo sie erhalten werden kann, wird
sie erhalten.

## Belegart

Jeder Posten trägt eine der folgenden Angaben. Übernommen ist sie aus dem
ungeschnittenen Plan und den beiden Prüfungen; neu vergeben ist sie nur für die
Befunde, die beim Schnitt dazukamen.

- **nachgemessen** — an einem echten Server gemessen (Konsumentenmessung,
  Compare-Matrix, Compare-Slice).
- **gemeldet** — aus der Konsumentenmessung übernommen; im Repo ist nur die
  **Vorbedingung** geprüft (der Filter fehlt, der Zweig fehlt), nicht die Zahl
  oder der Objektname.
- **im Code geprüft** — aus dem Code gelesen, nicht gemessen. Diese Posten
  messen zuerst, bevor sie bauen.

## Befunde ohne eigenes Paket

- **D3 — `varchar` ohne Länge kommt als `text` an: vertragsgleich.**
  PostgreSQL behandelt beide gleich, und `text` ohne `max_length` beschreibt im
  Modell genau das. Die Vergleichsfolge (`(spalte)::text` nur an einer
  `varchar`-Spalte) ist im Compare-Slice als Grenze festgehalten und ein
  Kandidat des [Toleranzprofils](../next/compare-toleranzprofil.md). `inet` und
  `interval` sind laut (`R301`); die Modellfrage liegt bei
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md).
  **Folge für die Matrix:** der Silent-Loss-Check zählt `varchar` zur
  Text-Familie.
- **B4 — `interval` wird still `text`: widerlegt.** `R301` meldet ihn
  (`PostgresTypeMapping.mapColumn`, `else`-Zweig);
  [`spec/type-mapping.md`](../../../spec/type-mapping.md), Abschnitt 8,
  schreibt die Warnung für alle fünf Reverse-Mapper fest. `interval` steht
  als Mechanik-Zeile im Kandidaten-Tracker.
- **Oracles `-slim`-Image hat kein Spatial: kein Befund.** Der
  `regular`-Flavor (`23-faststart`) hat es, die Free-Edition lizenziert es.
  Im Repo modelliert als `TestImages.ORACLE_FULL` und Dienst `oracle-spatial`
  in `examples/sample-db/docker-compose.yml`.
- **SpatiaLites Metadatentabellen lecken nicht** ins Reverse, und der
  R-Tree-Index kommt als `type: spatial` mit.

## Verbleib der Posten

| Posten | Kurz | Belegart | Ort |
| --- | --- | --- | --- |
| A1 | Oracle verliert den SRID quotiert kleingeschriebener Tabellen | nachgemessen | Plan 2, P1 |
| A2 | Oracles `MDRS_*`-Sequenz im Modell | gemeldet | Plan 3, P2a |
| A3 | PostGIS-Routinen fluten das Reverse | gemeldet | Plan 3, P2b |
| A4 | PostGIS außerhalb des `search_path`, Degradierung ohne Grund | gemeldet | Plan 2, P3 (Plan 3 erweitert auf `geography_columns`) |
| A5 | PostgreSQL `geography` als Enum | im Code bestätigt | Plan 3, P4 |
| A6 | SpatiaLite verwirft die Tabelle bei `NOT NULL` (entschieden) | gemeldet, SpatiaLite gemessen | Plan 3, P7 |
| B1 | MySQL rendert Arrays still als JSON | gemeldet | Plan 2, P5 |
| B2 | der Array-Verlust über MySQL ist irreversibel | gemeldet | Plan 2, P5 (Grenze) |
| B3 | PostgreSQL `json` wird `jsonb` (entschieden: laut) | im Code bestätigt | Plan 2, P8 |
| B4 | `interval` still | widerlegt | entfällt |
| C1 | MySQL-Introducer macht das Schema ungültig | nachgemessen | Plan 1, P6 |
| D1 | Typ berechneter SQL-Server-Spalten | nachgemessen | Plan 4, P13 |
| D2 | `numeric` ohne Präzision wird `float` | nachgemessen | Plan 2, P9; Modellfrage in [`../open/decimal-ohne-praezision-verlustfrei.md`](../open/decimal-ohne-praezision-verlustfrei.md) |
| D3 | `varchar` ohne Länge ist `text` | nachgemessen | kein Paket (oben) |
| D4 | MySQL rendert `ALWAYS` ohne Warnung | nachgemessen (MySQL), im Code geprüft (SQLite) | Plan 2, P10 |
| D5 | SQLite nennt jeden Fremdschlüssel `fk_0` | nachgemessen (`Msg 2714`), Rest im Code geprüft | Plan 1, P11 |
| D6 | T-SQL-Quoting im Berechnungsausdruck | nachgemessen | Reader-Hälfte: Plan 1, P12; Prüfungs-Hälfte: [`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md) |
| N1 | unbekanntes PostgreSQL-Array-Element ohne `R301` | im Code geprüft | Plan 2, P9 |
| N2 | MySQL-Server-Text trägt Backtick-Quoting; nach P6 lehnte `E053` jeden MySQL-CHECK auf jedem anderen Ziel ab | im Code geprüft (Schnitt) | Plan 1, P6 |
| N3 | `W120` ist doppelt belegt (SRID-Hinweis und SQLite-Trigger-Body) | im Code geprüft (Schnitt) | [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md) |
| S1 | PostgreSQL `integer`-Identity als alleiniger Primärschlüssel verliert den Modus (Review H2) | im Code geprüft | Plan 2 |
| S2 | SQLite-Migrate verliert `AUTOINCREMENT` einer `generation: identity`-Spalte (Review M4) | im Code geprüft; bekannt aus [`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md), Ursache 1 | Plan 2 |
| S3 | PostgreSQL-Generator rendert Array-Elemente außer `text`/`integer`/`boolean`/`uuid` als `TEXT[]` (Review M5) | im Code geprüft | Plan 2 |
| S4 | SQLite-Migrate verwirft `ON DELETE`/`ON UPDATE` an Fremdschlüsseln der Tabellenebene (Review M15) | im Code geprüft | Plan 1 (stört die Konvergenzmessung von P11) |
| F4 | Ledger: R-Codes mit `WARNING`, gültige Datei, `W137`-Richtung, `W160` | im Code und in der Spec geprüft | [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md) |

Die Review-Befunde H1, M1–M3, M6–M13, L1–L7 und die Architektur-Befunde F1,
F3, F5 sowie die Spec-Befunde zu P4, P7, P8, P9, P12 und P13 sind in den
Plänen dort eingearbeitet, wo ihr Paket steht.

## Gemeinsame Regeln der Abnahme

### Die Matrix

Plan 1 macht die 5x5-Compare-Matrix
([`smoke-compare-matrix.sh`](../../../examples/mcp-e2e/scripts/smoke-compare-matrix.sh))
zur Abnahme: native Seeds, ein Silent-Loss-Check, die Codes je Reverse und je
Generate. Danach gilt für jedes Paket, das die Matrix berührt:

- **Rot ohne Fix.** Der Fall des Pakets läuft in der Matrix, **bevor** der
  Fix kommt, und steht bis dahin in der Liste bekannter Befunde. Das Paket
  streicht seinen Eintrag im selben Commit, der den Fix bringt. **Ausnahme:**
  ein Fall, der ohne Fix die Zelle unpinnbar macht (ein gescheiterter Reverse,
  `GEN-FAIL` wie `geography` vor P4), kommt mit seinem Fix; das Rot zeigen
  dann Unit- und Integrationstest.
- **Jeder Neu-Pin ist bewusst und einzeln.** Ein Neu-Pin von
  [`expected/compare-matrix.env`](../../../examples/mcp-e2e/expected/compare-matrix.env)
  ist ein eigener Commit, der nur die Wirkung **eines** Pakets enthält. Die
  Commit-Nachricht nennt jeden geänderten Schlüssel und warum er sich ändert.
  Eine Änderung ohne Grund im Paket wird vor dem Pin benannt (als Posten oder
  als `open/`-Eintrag), nicht weggepinnt.
- **Die betroffenen Zellen (Review L7).** Eine Änderung am Reader von Dialekt
  D trifft jede Zelle mit Quelle D **und** jede mit Ziel D, denn auch der
  Reverse des Ziels läuft durch denselben Reader. Eine Änderung am Generator
  von D trifft jede Zelle mit Ziel D. Jedes Paket zählt seine Zellen vor dem
  Pin auf.
- **`GEN_CODES_*` entstehen vor dem Anwenden.** Sie gelten auch für eine
  Zelle, deren DDL das Ziel danach ablehnt (`APPLY-FAIL`); ein Generator-Paket
  kann eine solche Zelle also ändern, ohne sie zu öffnen.
- **Oracle bleibt Opt-in** (`make mcp-e2e-compare-matrix-oracle`), und auf dem
  Messhost bis zur Klärung von
  [`../open/mcp-e2e-oracle-nicht-gefahren.md`](../open/mcp-e2e-oracle-nicht-gefahren.md)
  nicht zulässig. Oracle-Pakete nehmen in `:test:integration-oracle` ab.

### Die Nulllinie der Integrationsmodule

Ohne `-PintegrationTests` überspringt Gradle die Test-Tasks und meldet
trotzdem `BUILD SUCCESSFUL`. Ein grüner Lauf nach einem Paket bedeutet deshalb
nur dann etwas, wenn vorher feststand, dass das Modul läuft.

| Modul | Nulllinie | gebraucht von |
| --- | --- | --- |
| `:test:integration-oracle` | gemessen 2026-09-16: `executed`, 33/33, keine Selbstüberspringung | Plan 2 (P1, P9), Plan 3 (P2a) |
| `:test:integration-postgresql` | gemessen 2026-09-16: `executed`, 43/43, keine Selbstüberspringung | Plan 2 (P3, P8, P9, S1, S3), Plan 3 (P2b, P4) |
| `:test:integration-mysql` | gemessen 2026-09-16: `executed`, keine Selbstüberspringung | Plan 1 (P6), Plan 2 (P5, P10) |
| `:test:integration-sqlite` | gemessen 2026-09-17: `executed`, keine Selbstüberspringung | Plan 1 (S4, P11), Plan 2 (P5, P9, P10, S2), Plan 3 (P7) |
| `:test:integration-mssql` | gemessen 2026-09-17: `executed`; **eine** Selbstüberspringung, s. unten | Plan 1 (P12), Plan 4 (P13) |
| `:test:e2e-cli` | **nicht gemessen** | Plan 3 (P4, Datenpfad) |

Ein Plan misst die Nulllinie seiner noch offenen Module **vor** seinem ersten
Paket: `make integration INTEGRATION_TASKS=":test:<modul>:test"`, der Task
steht als `executed` im Lauf (nicht `SKIPPED`, nicht `UP-TO-DATE`), und das
Modul trägt keine Selbstüberspringung (`assumeTrue`, `Assumptions`,
`@Disabled`). Ein PostGIS-Container ist in `:test:integration-postgresql` neu
(`TestImages.POSTGIS` benutzt bisher nur `:test:e2e-cli`); die gemessene
Nulllinie deckt ihn nicht.

**Eine Selbstüberspringung gibt es:** `MssqlFullTextEnvironmentIntegrationTest`
in `:test:integration-mssql` überspringt sich (`xtest`), wenn das abgeleitete
Volltext-Image fehlt (`MSSQL_FTS_IMAGE`, sonst `d-migrate-mssql-fts:local`;
`make mssql-fts-image` baut es). Auf dem Messhost vom 2026-09-17 lag es, die
Spec lief mit. Wer die Nulllinie anderswo misst, prüft das Image mit —
sonst ist der Lauf grün und diese Spec stumm.

### Sabotage

Jeder Fix gilt erst als geliefert, wenn sein Test mit zurückgenommenem Fix
fällt: den Fix entfernen, den Fehlschlag sehen, den Fix wiederherstellen und
die Tests danach grün fahren. Wo ein Paket mehrere Pfade hat (Generate und
Migrate, zwei Treiber), gilt das je Pfad.

### Gates je Commit

- `make solid-suppression-gate` vor jedem Commit.
- `make docker-check MODULES=…` für die berührten Module; eine geteilte
  Signatur im Hexagon oder in `driver-common` einmal ohne `MODULES`, weil
  `MODULES=` die Integrationsmodule nicht kompiliert.
- `make docs-check` bei jeder Änderung an `docs/`, `spec/` oder
  `docs/user/`.
- `make doc-immutable RANGE=origin/main..HEAD` vor dem Push. Kein Plan fasst
  den Kern eines akzeptierten ADR an.

## Gemeinsame Doku-Pflichten

- **Ein neuer W-Code** ist erst vollständig, wenn er an fünf Orten steht: in
  der W-Tabelle von [`spec/cli-spec.md`](../../../spec/cli-spec.md); in
  [`spec/ledger.md`](../../../spec/ledger.md), Einzelzeile **und**
  Bereichszeile; in
  [`ledger/warn-code-ledger-1.1.0.yaml`](../../../ledger/warn-code-ledger-1.1.0.yaml);
  in der Render-Regel des Dialekts in
  [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md); und in
  `docs/user/`.
  **Warum die Datei 1.1.0:** `spec/ledger.md` verlangt je Minor einen eigenen
  Satz Ledger-Dateien, gelebt wird aber die Fortschreibung dieser Datei
  (`W155` bis `W161` stehen dort), und `CodeLedgerValidationTest` liest sie;
  eine neue Datei für die laufende Minor-Version läse kein Test. Der Widerspruch gehört in
  [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md);
  bis er entschieden ist, landet ein neuer Code dort, wo ein Test ihn liest.
- **Ein neuer R-Code** hat keinen Ledger (derselbe `open/`-Eintrag). Er steht
  in [`spec/type-mapping.md`](../../../spec/type-mapping.md) im Abschnitt seines
  Dialekts, im Anwenderhandbuch dort, wo ein Anwender die Folge bemerkt, und im
  CHANGELOG.
- **CHANGELOG `[Unreleased]`** nennt jede sichtbare Änderung: neue Codes
  unter „Added", geänderte Reverse-Ausgaben (andere Namen, andere Typen,
  andere Severity) unter „Changed", behobene Defekte unter „Fixed".
- **`docs/user/` zieht im selben Commit wie das Verhalten nach**, nicht
  vorher: dort steht nur, was wirkt.
- **Spec-Richtung.** Kein Spec-Nachtrag verweist auf einen Plan, einen ADR
  oder einen `open/`-Eintrag; die Begründung steht in der Spec selbst.
  `spec/type-mapping.md` trägt an mehreren Stellen noch Ist-Formulierungen
  (Abschnitt 3.1 „Aktuelles Verhalten", 3.3 mit Statusspalte, 5.2 mit
  „Aktuell | Korrekt"). Wer eine dieser Tabellen anfasst, schreibt die Zeilen,
  die er berührt, als Zielbild-Regel
  ([ADR 0024](../../adr/0024-ist-zustand-dokumentation.md)).

## Codes

Alle Kennungen sind am 2026-09-17 frei nachgemessen: sie kommen in Code, Spec
und Ledger nicht vor, nur in Plänen. Die R-Codes folgen den Bereichen, die der
Code tatsächlich benutzt (R200–R220 SQLite; R300/R301 allgemein; R310–R330
MySQL; R340–R369 gemischt SQL Server, Oracle und `driver-common`; R400/R401
PostgreSQL). Eine Entscheidung über die R-Vergabe gibt es nicht; das gehört in
den Ledger-Eintrag.

| Code | Plan, Paket | Severity | Stand |
| --- | --- | --- | --- |
| `R370` | 2, P1 | `WARNING` ([ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md)) | reserviert |
| `R371` | 2, P9 (Oracle `NUMBER`) | `WARNING` | reserviert beim Schnitt |
| `R402` | 2, P8 | `WARNING` (die Daten ändern sich beim Übertragen) | reserviert |
| `R403` | 3, P4 | `WARNING` (Begründung in Plan 3) | reserviert |
| `R404` | 2, P9 (PostgreSQL) | `WARNING` | reserviert |
| `R405` | 2, P3 | `WARNING` (ADR 0058, Entscheidung 1: ein verlorener SRID) | reserviert |
| `R221` | 2, P9 (SQLite) | `WARNING` | reserviert |
| `W162` | 2, P5 (MySQL und SQLite) | warning | reserviert |
| `W163` | 2, P10 (MySQL und SQLite) | warning | reserviert |
| `W164` | 4, P13, nur Variante b | warning | reserviert |

Frei, aber **nicht** reserviert — für S1 bis S3, falls die Messung eine Note
statt eines Fixes ergibt: `R406` (PostgreSQL), `R222` (SQLite), `W165`.

**`W162` statt `W149`.** `W149` meldet „Array wird als JSON gerendert" und
steht im Oracle-Bereich von `spec/ledger.md` (`W145`–`W154`). SQLite rendert
ein Array aber als `TEXT`, nicht als JSON; `W149` für beide wäre eine zweite
Bedeutung derselben Kennung, genau die Art Doppelbelegung, die `W137` und
`R345` schon tragen. `W162` meldet die gemeinsame Aussage — die Spalte verliert
ihre Array-Eigenschaft — für MySQL und SQLite, wie `W132` den
Volltext-Verlust für mehrere Dialekte meldet. `W149` bleibt Oracles Code.

## Abgrenzung (für alle vier Pläne)

- **Keine Modellerweiterung.** Kein `interval`-Neutraltyp, keine zweite
  JSON-Art, kein `decimal` ohne Präzision, kein Attribut „geodätisch". Ihre
  Orte:
  [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md),
  [`../open/json-jsonb-zweite-json-art.md`](../open/json-jsonb-zweite-json-art.md)
  (entschieden: gleichsetzen, aber laut),
  [`../open/decimal-ohne-praezision-verlustfrei.md`](../open/decimal-ohne-praezision-verlustfrei.md),
  [`../open/geometrie-geodaetisch-modellattribut.md`](../open/geometrie-geodaetisch-modellattribut.md).
- **Der SRID-Abgleich des Oracle-Reverse bleibt wortgetreu**, und ein
  verlorener SRID blockt nichts
  ([ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md)).
- **„Keine partielle DDL" bleibt.** P7 ändert nur, **wann** eine
  SpatiaLite-Tabelle blockiert.
- **Die Portabilitätsprüfung urteilt weiter nach dem Ziel**
  ([`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md)).
- **Der Identity-Modus bleibt im Vergleich ein Fund**
  ([ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md),
  Abschnitt 2, Punkt 5); eine Toleranz dafür ist Kandidat K2 im
  [Toleranzprofil](../next/compare-toleranzprofil.md).
- **Keine lokale Normalisierung in `schema migrate` und im Fingerabdruck**
  ([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
  neu gefasste Entscheidung 1). Eine Normalisierung **im Reader** ist davon
  nicht betroffen: sie ändert, was ins Modell geht, nicht, wie verglichen
  wird. Ihre Folgen für die Herkunftsplanung messen P6 und P12.

## Offen

### Entschieden (übernommen)

- **E-Schnitt** (2026-09-17): vier Pläne, Reihenfolge 1 → 2 → 3 → 4.
- **F1** (2026-09-17): PostgreSQL `geography` nur auf dem Rückweg; vorwärts
  bleibt `geometry`; das Modell-Attribut wird ein `open/`-Eintrag.
- **F2** (2026-09-17): ein verlorener SRID ist eine Warnung, kein Block —
  [ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md).
  `R370` und `R365` einheitlich `WARNING`; Ausweg: SRID in der Schemadatei
  deklarieren oder die Tabelle unquotiert anlegen, nie die Metadatenzeile von
  Hand.
- **P13-Regel** (2026-09-17): Variante a nur bei nachweislich exaktem
  Rückweg, sonst Variante b mit `W164`.
- **D2** (2026-09-17): `numeric` ohne Präzision nur melden.
- **A6** (2026-09-16): `NOT NULL` nativ über `AddGeometryColumn`.
- **B3** (2026-09-16): `json` und `jsonb` gleichsetzen, aber laut.

### Eigner-Fragen aus dem Schnitt — am 2026-09-17 entschieden

Alle vier wie empfohlen: **E1 = (b)** (der MySQL-Generator setzt `"…"`-Bezeichner
in rohen Ausdrücken in Backticks um), **E2 = beheben**, **E3 = Ursache 1 in
Plan 2 beheben, Ursache 2 nur messen**, **S3 mit Anhebung des Fingerabdrucks
bestätigt**. Damit ist keine der Sperren mehr aktiv. Die Fragen im Wortlaut des
Schnitts:


1. **E1 — Bezeichner in `"…"` gegen MySQL** (Plan 1, P6 und P12). Beide
   Reader normalisieren Server-Quoting zum neutralen Bezeichner: ein
   kleingeschriebener Name bleibt nackt, jeder andere wird `"Name"`. MySQL liest
   `"…"` ohne `ANSI_QUOTES` als Zeichenkette. Nach P12 würde eine
   PascalCase-Spalte SQL Server → MySQL deshalb nicht mehr laut scheitern,
   sondern still falsch rechnen (Review M9). Nach P6 gilt dasselbe für MySQL →
   MySQL über eine neutrale Datei. Wege: (a) ein `E053`-Marker für
   `"…"`-Bezeichner gegen MySQL — laut, aber die Berechnung oder der CHECK
   entfällt dort; (b) der MySQL-Generator setzt `"…"`-Bezeichner in rohen
   Ausdrücken in Backticks um, wie es die Spec für Sichten-Rümpfe schon
   vorsieht
   ([`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md),
   8.3, „Identifier-Quoting in View-Queries"); dieselbe Stelle nennt ein
   Umschreiben roher Ausdrücke ausdrücklich „eine eigene Entscheidung";
   (c) die Reader normalisieren nur, wo der Name ohne Quoting auskommt.
   **Empfehlung: (b).** Das neutrale Modell erklärt `"…"` schon heute zum
   Bezeichner; (b) setzt diese Regel am einzigen Ziel durch, das sie anders
   liest, und verliert nichts. (a) wäre laut, machte aber aus einem heute
   funktionierenden MySQL → MySQL-Weg einen Verlust. (c) ließe `` `Name` `` und
   `[Name]` im Modell und damit `E053` auf jedem anderen Ziel. **Sperrt** den
   Neu-Pin von P6 und P12, nicht deren Bau.
2. **E2 — S1 beheben oder melden** (Plan 2). Eine
   `integer GENERATED ALWAYS AS IDENTITY`-Spalte als alleiniger
   Primärschlüssel liest als `identifier` ohne Modus; der PostgreSQL-Generator
   rendert daraus `SERIAL`. Der Verlust trifft damit auch PostgreSQL →
   PostgreSQL, und eine Note heilt das nicht. **Empfehlung:** beheben — eine
   solche Spalte mit `ALWAYS` liest wie der Zweig für Nicht-Schlüsselspalten
   als `integer` + `generation: identity` (Modus `always`); `by_default` und
   `serial` behalten den `identifier`-Vertrag. Folge: ein Reverse derselben
   Datenbank liefert eine andere Datei („Changed").
3. **E3 — S2 in Plan 2 beheben** (Plan 2). Der SQLite-Migrate-Pfad rendert
   `generation: identity` nicht; das ist Ursache 1 von
   [`../open/sqlite-migrate-biginteger-identity-render-gap.md`](../open/sqlite-migrate-biginteger-identity-render-gap.md).
   Eine Note an dieser Stelle wäre ein Platzhalter für den Fix.
   **Empfehlung:** Plan 2 übernimmt Ursache 1 (Render-Parität von
   `columnLine` und `primaryKeyClause` mit dem Generate-Pfad) und misst
   Ursache 2 (Präferenz im Post-Compare-Re-Read) nur; bleibt danach Drift,
   bleibt Ursache 2 im `open/`-Eintrag. `W163` auf dem SQLite-Migrate-Pfad
   entsteht erst mit Ursache 1.
4. **Zur Bestätigung, keine Sperre — S3 mit Fingerabdruck-Anhebung**
   (Plan 2). Den PostgreSQL-Generator zu korrigieren ändert die Projektion
   des PostgreSQL-Kanonisierers und damit den Fingerabdruck
   (`schema-fingerprint-v16` → `v17`); bestehende Rollback-Artefakte und
   Overlay-Pins werden laut ungültig. Das ist die etablierte Praxis jeder
   Projektionsänderung. **Empfehlung:** so bauen.

### Im Plan entschieden, vom Eigner übersteuerbar

- **P1 meldet auch die unquotiert angelegte Tabelle ohne registrierte Zeile**
  — mit eigenem Text und dem Ausweg, der dort passt (die Zeile registrieren);
  ADR 0058 lässt das offen (Plan 2).
- **P2a/P2b filtern stumm**, wie ihre drei Vorbilder; ein Hinweis entsteht
  nur, wenn die Messung kein Katalogkriterium findet (Plan 3).
- **P9 nimmt Oracles `NUMBER` ohne Angabe auf** (`R371`), statt ihn nur im
  `open/`-Eintrag zu nennen (Plan 2).

### Außerhalb der vier Pläne

- Ledger (F4, N3):
  [`../open/warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md).
- Portabilitätsprüfung mit Herkunft (D6, zweite Hälfte):
  [`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md).
- Die Modellfragen: s. „Abgrenzung".
