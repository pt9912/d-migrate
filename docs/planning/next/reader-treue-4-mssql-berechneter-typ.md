# Reader-Treue 4: der Typ einer berechneten SQL-Server-Spalte (P13)

> **Status:** Entwurf mit Scope (Schnitt 2026-09-17 aus dem ungeschnittenen
> Reader-Slice; Befunde aus Plan-Review und Architektur-Prüfung eingearbeitet,
> Anker gegen `90c6c234f` nachgemessen). Teil des Umbrellas
> [`reader-treue.md`](../in-progress/reader-treue.md); dort stehen Nenner, Belegart, Regeln
> der Abnahme, Doku-Pflichten und die Code-Tabelle.
> **Vorbedingung / Gate:** P12 aus [Plan 1](../done/reader-treue-1-matrix-abnahme.md) ist geliefert — die Rückführung
> arbeitet auf normalisiertem Text. Die Entscheidungsregel ist vom Eigner
> bestätigt (2026-09-17). Kein ADR: die Regel „ohne Typ" steht in
> `spec/ddl-generation-rules.md` 3.2a und ist eine Spec-Regel.
> **Aktivierung:** Move nach `../in-progress/` beim ersten
> Implementierungs-Commit dieses Plans; die Messungen allein sind noch kein
> Implementierungs-Commit.
> **Abhängigkeit:** [Plan 1](../done/reader-treue-1-matrix-abnahme.md) (P12, Nulllinie von `:test:integration-mssql`).
> Plan 2 und Plan 3 sind keine Vorbedingung; der Plan kommt nach Eigner-Reihenfolge
> zuletzt.

## Befund

### D1 — SQL Server führt für eine berechnete Spalte keinen deklarierten Typ (nachgemessen)

SQL Server leitet den Typ einer berechneten Spalte aus dem Ausdruck ab. Der
Reverse liest den abgeleiteten Typ — `decimal(23,2)` für
`quantity * unit_price` bei zwei `decimal(12,2)`-Spalten —, das Soll sagt
`decimal(14,2)`. Gemessen im Compare-Slice als Fund in PostgreSQL ↔ SQL Server
und SQL Server ↔ MySQL, schon mit 1.7.1. In der Matrix ist der Posten
unsichtbar: beide Seiten sind Reverses und tragen denselben abgeleiteten Typ.
Sichtbar ist er im Roundtrip-Harness (Datei gegen Reverse): die Tabelle für SQL
Server nennt „abgeleiteter Typ der berechneten Spalte"
([`examples/mcp-e2e/README.md`](../../../examples/mcp-e2e/README.md), Zeile
139).

### Der Generator sagt es nicht (im Code und in der Spec geprüft)

`spec/ddl-generation-rules.md` 3.2a schreibt für SQL Server
`<quoted_name> AS (<expression>) [PERSISTED]` vor, „ohne Typ" — ein Typ davor
ist ein Syntaxfehler. Der Generator meldet nicht, dass der deklarierte Typ
damit wegfällt: spec-konform, aber still.

### Die CAST-Hülle und ihr Haken (im Code geprüft)

Eine Hülle `CAST(<ausdruck> AS <typ>)` gäbe der Spalte den Solltyp. SQL
Server legt `CAST` aber als `CONVERT` ab; der Kommentar am Computed-Zweig in
`MssqlSchemaReader` hält es fest (gemessen auf 2025:
„`([qty]*[price])`, aus `CAST` wird `CONVERT`"). `CONVERT(<typ>, …)` ist auf
PostgreSQL und MySQL kein gültiger Ausdruck, und `E053` erkennt ihn nicht.
Ohne Rückführung im Reader wäre D1 gelöst und ein neuer D6-Fall entstanden.

**Der Präzedenzfall steht in der Spec:** der SQL-Server-Reverse führt einen
Default `CONVERT([date],getdate())` auf `current_date` zurück
(`spec/type-mapping.md` 6.2, „Defaults").

### Architektur-Prüfung

- **Kein ADR:** 3.2a ist eine Spec-Regel.
- **Die Begründung für Variante a** (für den Spec-Nachtrag): in PostgreSQL
  und MySQL **ist** der deklarierte Typ einer berechneten Spalte die
  implizite Umwandlung des Ausdrucks. Eine äußerste `CAST`-Hülle auf genau
  diesen Typ drückt in SQL Server dasselbe aus; sie beim Lesen zu entfernen
  ist bedeutungserhaltend.
- **Die Messungen bleiben im Plan.** Die Spec begründet die Regel aus der
  Sache, nicht mit einem Verweis auf diese Messungen.

### F5 — Herkunft (Architektur-Prüfung, übertragen)

Variante a ändert den erzeugten Ausdruck (mit Hülle) und den gelesenen
(ohne). Für die Herkunftsplanung von `schema migrate`
([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
übernommene Entscheidung 2) ist zu prüfen, ob ein Artefakt aus der Zeit vor P13
eine `AlterColumnGeneration` auslöst — die SQL Server nicht in place kann (3.2a).

## Ziel

Der deklarierte Typ einer berechneten Spalte geht auf dem Weg über SQL Server
nicht still verloren: entweder bleibt er erhalten (Variante a) oder sein
Wegfall ist benannt (Variante b). Welche Variante, entscheidet die Messung
nach der bestätigten Regel.

Begründet gegen [`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003) und
[`LF-004`](../../../spec/lastenheft-d-migrate.md#lf-004).

## Abgrenzung

- **Keine Änderung an den anderen Dialekten**; sie tragen den Typ schon.
- **Keine Portabilitätsprüfung mit Herkunft**
  ([`../open/ausdrucks-portabilitaet-mit-herkunft.md`](../open/ausdrucks-portabilitaet-mit-herkunft.md)).
  Ein `CONVERT`, das die Rückführung nicht trifft, bleibt dort.
- **Kein SQL-Server-Seed in der Matrix für D1**, solange die Rückführung
  nicht belegt ist (Plan 1, Abgrenzung). Bei Variante a darf P13 ihn anlegen.

## Arbeitspakete

### P13 — messen, dann nach der Regel bauen (D1)

**Modul:** `:adapters:driven:driver-mssql` — der Generator für berechnete
Spalten (`MssqlColumnConstraintHelper`) und der Computed-Zweig in
`MssqlSchemaReader`. Messung in `:test:integration-mssql`.

**Messung zuerst** (SQL Server 2025, derselbe Stand wie die Matrix; die
Ergebnisse stehen danach in diesem Plan):
1. Was `sys.computed_columns.definition` und der Spaltentyp für
   `AS (CAST(<ausdruck> AS decimal(14,2))) PERSISTED` liefern — und für
   `varchar`, `int` und `datetime2` als Zieltyp.
2. Ob der Reader die **äußerste** Hülle `CONVERT(<typ>, <ausdruck>)` genau
   dann eindeutig zurückführen kann, wenn `<typ>` der Spaltentyp ist, so dass
   Hin- und Rückweg Typ und Ausdruck unverändert liefern. Dazu die
   Gegenproben: ein Autor schreibt selbst eine äußerste `CAST`-Hülle auf einen
   **anderen** Typ (sie bleibt); ein Autor schreibt eine Hülle auf **denselben**
   Typ (ihre Entfernung ändert den Autorentext — ist das hinnehmbar?); ein
   `CONVERT` mit Stilargument (bleibt).
3. Was der Vergleich daraus macht (`W137`, Entscheidbarkeit), was der
   Roundtrip-Harness für SQL Server meldet (die Fixture trägt den Fall:
   `line_total` als `decimal(14,2)` über `quantity * unit_price`) und was
   `schema migrate` mit einem Artefakt aus der Zeit vor P13 plant (F5).

**Regel (Eigner, 2026-09-17):**
- **Variante a** — der Generator hüllt den Ausdruck in `CAST(… AS <typ>)`,
  der Reader führt die äußerste `CONVERT`-Hülle zurück —, **nur** wenn
  Messung 1 und 2 deterministisch sind und der Rückweg nachweislich exakt ist.
- **Variante b** — sonst bleibt der Generator bei „ohne Typ" und meldet den
  Wegfall mit `W164`: der deklarierte Typ fällt weg, SQL Server leitet ihn
  aus dem Ausdruck ab, ein Reverse liest den abgeleiteten Typ.
- Ergibt Messung 3 bei Variante a eine geplante `AlterColumnGeneration` für
  bestehende Artefakte, hält das Paket vor dem Bau an; der Ausweg ist eine
  Eigner-Frage.

**DoD:**
1. Die drei Messungen stehen in diesem Plan, samt der gewählten Variante und
   ihrer Begründung nach der Regel.
2. **Variante a:** ein SQL-Server-Reverse liefert `decimal(14,2)` und den
   Ausdruck ohne Hülle; die Gegenproben aus Messung 2 halten; der
   Hash-Partitions-Pfad (`MssqlHashPartitionRecognition`) ist unberührt.
   **Variante b:** jede berechnete Spalte mit deklariertem Typ trägt beim
   Generieren nach SQL Server `W164`, auf Generate und Migrate.
3. Test je Pfad; **Sabotage:** a — Rückführung weg → Reverse-Test rot, Hülle
   weg → Generate-Test rot; b — Note weg → Test rot.
4. **Doku (Spec):** `spec/ddl-generation-rules.md` 3.2a — a: die Regel „ohne
   Typ" wird „mit `CAST` auf den deklarierten Typ", mit der Begründung aus der
   Architektur-Prüfung; b: die Regel bekommt den Code.
   `spec/type-mapping.md` 6.2 (die Rückführung neben der
   `CONVERT([date],getdate())`-Regel, bei a) und 6.3 (bei b: der Reverse
   liest den abgeleiteten Typ). Keine Messung und kein Plan als Beleg in der
   Spec.
5. **Doku (Code, bei b):** die fünf Registrierungsorte für `W164`
   (Umbrella), Ledger-Datei `ledger/warn-code-ledger-1.1.0.yaml`.
6. **Doku (Anwender):** Anwenderhandbuch 3.23 („Eine Spalte aus anderen
   Spalten berechnen lassen", `docs/user/anwenderhandbuch.md:2494`) — a: SQL
   Server behält den deklarierten Typ; b: `W164` und was er bedeutet.
   CHANGELOG „Changed" (a: anderes DDL, anderer Reverse) bzw. „Added" (b).
7. **Roundtrip (a):** der Typfund `decimal(23,2)` gegen `decimal(14,2)` für
   SQL Server verschwindet; die README-Tabelle des Harness zieht nach.
8. **Matrix:** SQL Server → PostgreSQL und → MySQL messen weiter — bei a der
   Beleg, dass die Rückführung greift, denn ohne sie stünde dort `CONVERT(…)`
   und die Zelle wäre wieder `APPLY-FAIL`; P13 legt dann den
   SQL-Server-Seed mit `CAST` an. Bei b steht `W164` in `GEN_CODES_*` jeder
   Zelle mit Ziel SQL Server, deren Quelle die Berechnung behält (PostgreSQL,
   MySQL, SQLite → SQL Server). Betroffen sind nach der Regel des Umbrellas
   alle Zellen mit Quelle oder Ziel SQL Server.

**Abnahme:** `:test:integration-mssql` (Nulllinie mit Plan 1 gemessen),
`make mcp-e2e-roundtrip` (SQL Server), Matrix.

## Akzeptanzkriterien

1. Für D1 stehen die drei Messungen im Plan, und die Variante ist nach der
   bestätigten Regel gewählt und gebaut.
2. Bei a trägt ein SQL-Server-Reverse den deklarierten Typ und einen
   Ausdruck ohne Hülle; bei b ist der Wegfall mit `W164` benannt.
3. Kein neuer `CONVERT`-Text erreicht ein anderes Ziel: SQL Server →
   PostgreSQL und → MySQL messen weiter.
4. Spec, gegebenenfalls Ledger, Anwenderhandbuch und CHANGELOG sind
   nachgezogen; `make docs-check` ohne Befund.
5. Der Fix fällt nachweislich mit zurückgenommenem Fix.

## Verifikation

1. **Nulllinie:** `:test:integration-mssql` ist mit Plan 1 gemessen.
2. **Messort:** `:test:integration-mssql` für die Messungen 1 und 2; der
   Roundtrip-Harness und `schema migrate` für Messung 3.
3. **Neu-Pin:** ein Commit, die Schlüssel in der Nachricht (Umbrella).
4. **Gates:** Umbrella, „Gates je Commit".
5. **Was gemessen ist und was nicht:** D1 ist gemessen; dass SQL Server
   `CAST` als `CONVERT` ablegt, ist für `date` gemessen (Default-Pfad) und
   für den Berechnungsausdruck am Kommentar im Reader festgehalten; die
   Rückführbarkeit ist ungemessen.

## Offen

- **Messung 3, Herkunft:** eine geplante `AlterColumnGeneration` für ältere
  Artefakte wird eine Eigner-Frage.
- **Autorentext mit eigener Hülle auf denselben Typ** (Messung 2): ob seine
  Entfernung hinnehmbar ist, entscheidet die Messung; ist sie es nicht,
  schließt das Variante a aus.
