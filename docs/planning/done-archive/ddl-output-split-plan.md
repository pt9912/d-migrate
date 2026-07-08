# Implementierungsplan: DDL-Output-Split fuer Import-freundliche Artefakte

> Dieses Dokument beschreibt einen konkreten Implementierungsplan fuer einen
> optionalen Split der von `schema generate` erzeugten DDL in mehrere Phasen.
>
> Status: Draft (2026-04-17)
> Referenzen: `spec/ddl-generation-rules.md`,
> `spec/design-import-sequences-triggers.md`,
> `spec/cli-spec.md`,
> `docs/planning/roadmap.md`

---

## 1. Ziel

`d-migrate schema generate` erzeugt heute einen zusammenhaengenden DDL-Stream,
der Tabellen, Views, Routinen und Trigger in einer festen Reihenfolge
ausgibt. Das ist fuer Neuaufbau eines Schemas passend, ist aber fuer
datengetriebene Migrationsablaeufe unguenstig:

- bei MySQL und SQLite koennen Trigger waehrend `data import` derzeit nicht
  generisch deaktiviert werden
- Trigger, Functions und Procedures sind haeufig "post-data"-Objekte, die
  erst nach dem Laden von Daten aktiv werden sollen
- externe Tools wie Flyway oder Liquibase profitieren von klar getrennten
  Phasen

Ziel ist daher ein optionaler DDL-Split in mindestens zwei logisch getrennte
Ausgaben:

- `pre-data`: Objekte, die vor dem Import vorhanden sein muessen
- `post-data`: Objekte, die den Import stoeren oder logisch erst danach
  aktiviert werden sollen

Der bestehende Default ohne Split bleibt unveraendert.

Wichtig:

- jede erzeugte Phase muss fuer sich genommen ausfuehrbar sein
- `pre-data` darf keine Objekte enthalten, die erst durch `post-data`
  aufloesbare Abhaengigkeiten haben

---

## 2. Fachlicher Nutzen

Der Split loest nicht das Importproblem durch "mehr Dateien", sondern durch
eine explizite Phasentrennung.

Beispielablauf:

1. `schema generate --split pre-post --output out/schema.sql`
2. `out/schema.pre-data.sql` ausfuehren
3. `data import ...`
4. `out/schema.post-data.sql` ausfuehren

Dadurch entsteht fuer MySQL und SQLite ein praktikabler Workaround fuer
aktive Trigger, obwohl `--trigger-mode disable` dort bewusst nicht
unterstuetzt ist.

Fuer PostgreSQL bleibt der Split ebenfalls nuetzlich, auch wenn dort
`trigger-mode=disable` verfuegbar ist: externe Migrationstools und manuelle
Rollouts koennen denselben Phasenvertrag verwenden.

---

## 3. Scope

### 3.1 In Scope

- optionaler Split fuer `schema generate`
- neue interne DDL-Phasen im Generator-Ergebnis
- getrennte Dateiausgabe fuer `pre-data` und `post-data`
- JSON-Ausgabe mit phasenbezogenen Feldern
- Dokumentation der Objektzuordnung pro Phase
- Tests fuer Dateinamen, Inhalt, Reihenfolge und Abwaertskompatibilitaet

### 3.2 Nicht in Scope

- automatische Ausfuehrung eines Imports zwischen den Phasen
- automatische Tool-Integration fuer Flyway/Liquibase im ersten Schritt
- neuer Importmodus fuer MySQL/SQLite Trigger-Deaktivierung
- semantische Aenderung der bestehenden Default-Ausgabe ohne Split

---

## 4. Zielbild

Der User kann zwischen zwei Modi waehlen:

- `single`:
  heutiges Verhalten, ein zusammenhaengender DDL-Output
- `pre-post`:
  zwei getrennte Outputs fuer vor und nach dem Datenimport

Vorgeschlagener CLI-Vertrag:

```text
d-migrate schema generate --target mysql --source schema.yaml \
  --split pre-post --output out/schema.sql
```

Erwartete Dateien:

- `out/schema.pre-data.sql`
- `out/schema.post-data.sql`
- optional weiter ein gemeinsamer Report, z. B. `out/schema.report.yaml`

Wird kein `--output` angegeben, bleibt fuer `--split pre-post` als erste
Iteration SQL-Textausgabe auf `stdout` weiterhin **nicht** erlaubt. Eine
Split-Ausgabe im JSON-Format bleibt dagegen erlaubt, weil sie als einzelnes
strukturiertes Artefakt serialisierbar ist.

---

## 5. Phasenmodell

### 5.1 Phase `pre-data`

Enthaelt alle Objekte, die fuer einen Import strukturell noetig sind und ihn
nicht durch serverseitige Logik verfremden sollen:

- Header
- Custom Types
- Sequences, soweit der Zieldialekt sie nativ unterstuetzt
- Tabellen
- zirkulaere FK-Nachzuegler via `ALTER TABLE ... ADD CONSTRAINT`
- Indizes
- Views **nur dann**, wenn sie keine Abhaengigkeit auf Functions oder
  Procedures haben

Fuer die erste Iteration sollten Views in `pre-data` bleiben, um die Semantik
nahe am heutigen Generator zu halten, **sofern** sie ohne spaetere Routinen
ausfuehrbar bleiben.

### 5.2 Phase `post-data`

Enthaelt Objekte, die typischerweise nach dem Datenimport angelegt werden
sollten:

- Functions
- Procedures
- Triggers
- Views mit Abhaengigkeit auf Functions oder Procedures

Begruendung:

- Trigger koennen Datenimporte beeinflussen oder Seiteneffekte erzeugen
- Functions/Procedures enthalten haeufig prozedurale Logik und sind fuer das
  reine Laden von Daten meist nicht erforderlich
- die heutige Generierungsreihenfolge setzt diese Objekte ohnehin ans Ende
- Views, die Routinen referenzieren, waeren in `pre-data` nicht sicher
  ausfuehrbar und muessen deshalb in dieselbe Phase wie ihre Abhaengigkeiten

### 5.3 Sonderfaelle

- MySQL- und SQLite-Sequences bleiben wie heute `action_required` bzw.
  `skipped`, da diese Dialekte dafuer keinen gleichwertigen nativen Vertrag
  haben
- PostgreSQL-Trigger-Funktionen, die bei der Trigger-Erzeugung implizit
  generiert werden, gehoeren zusammen mit dem Trigger in `post-data`
- Views mit Routine-Abhaengigkeiten werden deterministisch nach `post-data`
  verschoben; kann diese Abhaengigkeit in Phase 1 nicht verlaesslich erkannt
  werden, endet `--split pre-post` mit Exit 2 statt ein potenziell defektes
  `pre-data`-Artefakt zu erzeugen
- `--rollback` ist in Phase 1 zusammen mit `--split pre-post` explizit nicht
  erlaubt und endet mit Exit 2; spiegelbildliche Rollback-Phasen folgen erst
  in Schritt 2 des Features

---

## 6. Interner Modell-Schnitt

Das heutige `DdlResult` ist im Wesentlichen eine flache Liste von Statements.
Fuer den Split braucht es einen expliziten Phasenbezug.

Vorgeschlagene Richtung:

- `DdlPhase = PRE_DATA | POST_DATA`
- jedes `DdlStatement` traegt zusaetzlich `phase`
- jede `TransformationNote` und jedes `SkippedObject` traegt ebenfalls
  `phase`; globale, nicht statementgebundene Hinweise duerfen `phase = null`
  verwenden
- `DdlResult.render()` bleibt fuer den Default bestehen und rendert beide
  Phasen in bisheriger Reihenfolge
- neue Renderer:
  - `renderPhase(DdlPhase.PRE_DATA)`
  - `renderPhase(DdlPhase.POST_DATA)`

Alternative:

- statt `phase` direkt zwei getrennte Listen im Ergebnis

Bewertung:

- `phase` am Statement ist der robustere Schnitt, weil Notes,
  `skipped_objects` und spaetere weitere Phasen daran sauber andocken koennen
- zwei Listen sind kleiner, aber unflexibler fuer kuenftige Erweiterungen wie
  `schema-only`, `post-constraints` oder Tool-Exports

Empfehlung fuer Umsetzung:

- `phase` am Statement

---

## 7. CLI- und Output-Vertrag

### 7.1 Neue Option

Vorgeschlagen:

```text
--split single|pre-post
```

Default:

```text
single
```

### 7.2 Verhalten ohne `--output`

Fuer `--split single`:

- unveraendert, DDL auf `stdout` oder in `--output`

Fuer `--split pre-post`:

- mit SQL-Textausgabe nur zusammen mit `--output` erlaubt
- ohne `--output` und ohne `--output-format json` -> Exit 2 mit klarer
  Fehlermeldung
- mit `--output-format json` auch ohne `--output` erlaubt

Begruendung:

- zwei getrennte SQL-Artefakte lassen sich auf `stdout` nicht sauber
  adressieren
- Marker-Kommentare waeren moeglich, aber fuer Shell-Pipelines und Tooling
  fehleranfaellig
- JSON ist dagegen ein einzelnes strukturiertes Antwortartefakt und daher
  eindeutig konsumierbar

### 7.3 Dateinamen

Aus:

```text
--output out/schema.sql
```

wird bei `pre-post`:

- `out/schema.pre-data.sql`
- `out/schema.post-data.sql`
- Report weiterhin `out/schema.report.yaml`

Optional fuer spaeter:

- `out/schema.pre-data.rollback.sql`
- `out/schema.post-data.rollback.sql`

### 7.4 Verhalten mit `--rollback`

Fuer Phase 1 gilt:

- `--split single --rollback` bleibt unveraendert unterstuetzt
- `--split pre-post --rollback` ist explizit **nicht** unterstuetzt
- Aufruf endet mit Exit 2 und klarer Fehlermeldung

Begruendung:

- ein halb spezifizierter Rollback-Split wuerde mehr operative Unsicherheit
  schaffen als Nutzen
- die Spiegelung von `pre-data` und `post-data` in Down-Migrationen braucht
  einen eigenen, bewusst modellierten Vertrag

---

## 8. JSON-Ausgabe

Die bestehende JSON-Ausgabe enthaelt heute ein einzelnes `ddl`-Feld. Bei
`--split pre-post` reicht das nicht mehr.

Verworfene Alternative:

```json
{
  "command": "schema.generate",
  "status": "completed",
  "exit_code": 0,
  "target": "mysql",
  "split_mode": "pre-post",
  "ddl": {
    "pre_data": "...",
    "post_data": "..."
  },
  "notes": [],
  "skipped_objects": []
}
```

Abwaertskompatibilitaet:

- bei `split_mode = single` bleibt `ddl` ein String
- bei `split_mode = pre-post` wird `ddl` zu einem Objekt

Dieser Typwechsel ist fuer bestehende Consumer zu riskant und wird fuer Phase
1 **nicht** verwendet.

Phase-1-Vertrag:

- `ddl` bleibt ausschliesslich fuer `split_mode = single` gesetzt
- bei `split_mode = pre-post` liefert JSON stattdessen ein neues Feld
  `ddl_parts`

Beispiel fuer Phase 1:

```json
{
  "command": "schema.generate",
  "status": "completed",
  "exit_code": 0,
  "target": "mysql",
  "split_mode": "pre-post",
  "ddl_parts": {
    "pre_data": "...",
    "post_data": "..."
  },
  "notes": [
    { "phase": "post-data", "code": "E053", "message": "..." }
  ],
  "skipped_objects": [
    { "phase": "post-data", "type": "trigger", "name": "..." }
  ]
}
```

---

## 9. Generator-Zuordnung pro Objekttyp

Die Generatoren sollten wie folgt markiert werden:

- Header -> `pre-data`
- Custom Types -> `pre-data`
- Sequences -> `pre-data`
- Tables -> `pre-data`
- Indices -> `pre-data`
- Circular FK `ALTER TABLE` -> `pre-data`
- Views ohne Routine-Abhaengigkeiten -> `pre-data`
- Views mit Routine-Abhaengigkeiten -> `post-data`
- Functions -> `post-data`
- Procedures -> `post-data`
- Triggers -> `post-data`

Wichtig:

- die heutige Gesamtreihenfolge darf fuer `single` exakt gleich bleiben
- `pre-data` und `post-data` muessen jeweils intern stabil und deterministisch
  bleiben
- im Split-Modus darf kein Objekt in `pre-data` landen, dessen Ausfuehrbarkeit
  von einem `post-data`-Objekt abhaengt

---

## 10. Umsetzungsschritte

### Schritt 1: Vertrags- und CLI-Erweiterung

- `SchemaGenerateRequest` um `splitMode` erweitern
- CLI um `--split single|pre-post` erweitern
- Validierung:
  - `pre-post` ohne `--output` und ohne `--output-format json` -> Exit 2
  - `pre-post` zusammen mit `--rollback` -> Exit 2
- Hilfe-/Fehlermeldungen ergaenzen

### Schritt 2: DDL-Phasen im Modell

- `DdlPhase` einfuehren
- `DdlStatement` um `phase` erweitern
- `TransformationNote` und `SkippedObject` um optionales `phase` erweitern
- bestehende Generatorpfade auf `PRE_DATA` migrieren
- Functions/Procedures/Triggers explizit auf `POST_DATA` setzen
- View-Phase ueber deterministische Routine-Abhaengigkeitsanalyse ableiten;
  wenn die Analyse einen sicheren Split nicht liefern kann, Fehlerpfad statt
  defektem Output erzeugen

### Schritt 3: Rendering

- `DdlResult.render()` unveraendert fuer `single`
- neue Phasen-Renderer einfuehren
- Dateinamenslogik fuer `pre-data`/`post-data` implementieren
- JSON-Renderer fuer `ddl_parts` implementieren

### Schritt 4: Report und JSON

- Report um `split_mode` ergaenzen
- Report-Notes und `skipped_objects` um `phase` erweitern
- JSON-Ausgabe fuer Split-Fall erweitern
- `skipped_objects` und `notes` als Gesamtsicht behalten, aber jedes Element
  traegt seine Phase

### Schritt 5: Dokumentation

- `spec/ddl-generation-rules.md` um Split-Vertrag erweitern
- `spec/cli-spec.md` um `--split` erweitern
- `README.md` um ein Import-freundliches Beispiel ergaenzen
- Doku fuer View-Abhaengigkeiten und Split-Fehlerpfade ergaenzen

### Schritt 6: Tests

- Unit-Tests fuer CLI-Validierung
- Generator-Tests fuer Phasen-Zuordnung
- Golden-Master-Tests fuer `single`
- neue Golden-Master-Tests fuer `pre-data` und `post-data`
- Tests fuer Dateinamen und JSON-Ausgabe
- Tests fuer Note-/Skip-Phase im Report und JSON
- Tests fuer View-Abhaengigkeiten:
  - View ohne Routinen bleibt in `pre-data`
  - View mit Function/Procedure landet in `post-data`
  - nicht sicher aufloesbarer Fall endet mit Fehler statt defektem `pre-data`

---

## 11. Teststrategie

### 11.1 Kompatibilitaet

- bestehende Fixtures fuer `schema generate` ohne `--split` muessen unveraendert
  bleiben
- bestehende Golden Masters duerfen sich im `single`-Modus nicht aendern

### 11.2 Split-Korrektheit

- Trigger erscheinen nie in `pre-data`
- Functions/Procedures erscheinen nie in `pre-data`
- Tabellen/Indizes erscheinen nie in `post-data`
- Views mit Routine-Abhaengigkeiten erscheinen nie in `pre-data`
- `pre-data + post-data` ergibt logisch denselben Inhalt wie `single`
  abgesehen von Dateitrennung und optionalen Abschnittskommentaren
- alle Notes und `skipped_objects` im Split-Fall sind phasenattribuiert

### 11.3 Dialektfaelle

- PostgreSQL mit Sequence + Trigger
- MySQL mit Trigger + Function + `AUTO_INCREMENT`
- SQLite mit Trigger + `AUTOINCREMENT`

### 11.4 Fehlpfade

- `--split pre-post` ohne `--output` und ohne `--output-format json`
- `--split pre-post --output-format json` ohne `--output` ist erlaubt und
  liefert `ddl_parts`
- `--rollback` zusammen mit Split, sofern in Phase 1 noch nicht unterstuetzt
- Split mit nicht sicher aufloesbarer View-/Routinen-Abhaengigkeit

---

## 12. Risiken

- JSON-Vertrag kann bei falscher Modellierung rueckwaertskompatible Consumer
  brechen
- Rollback-Split fuehrt schnell zu Detailfragen, die den ersten Scope aufblaehen
- View-Abhaengigkeiten gegen Routinen duerfen nicht heuristisch so schwach
  bestimmt werden, dass defekte `pre-data`-Artefakte entstehen
- Tool-Exports koennten spaeter eigene Phasenartefakte erwarten; dafuer sollte
  die interne Phasenmodellierung offen genug bleiben
- Phase-Attribution in Notes/Skips/Report muss konsistent aus derselben Quelle
  kommen, sonst divergiert die Diagnose zwischen Text-, JSON- und File-Output

---

## 13. Offene Entscheidungen

Vor Implementierung zu klaeren:

1. Soll `--split pre-post` fuer SQL-Textausgabe nur mit `--output` erlaubt
   sein, waehrend JSON ohne `--output` erlaubt bleibt?
2. Soll JSON ein neues Feld `ddl_parts` bekommen statt `ddl` umzudeuten?
3. Sollen Views nur dann in `pre-data` bleiben, wenn ihre Routinen-
   Abhaengigkeiten sicher ausgeschlossen oder aufgeloest werden koennen?
4. Soll `--rollback` im Split-Modus direkt mit ausgeliefert werden oder erst
   in Schritt 2 des Features?
5. Soll der Report mindestens `phase` pro Note/Skip und `split_mode`
   enthalten?

Empfohlene Antworten fuer Phase 1:

1. Ja
2. Ja
3. Ja
4. Nein, erst Folgeiteration
5. Ja

---

## 14. Empfehlung

Das Feature sollte als optionaler, strikt abwaertskompatibler Ausbau von
`schema generate` umgesetzt werden. Der groesste praktische Mehrwert liegt bei
MySQL und SQLite, weil dort Trigger beim Import nicht generisch deaktiviert
werden koennen. Genau deshalb ist ein sauberer `pre-data`/`post-data`-Split
hier keine kosmetische Verbesserung, sondern ein echter Betriebsvertrag fuer
importfreundliche Migrationen.

Der Vertrag muss allerdings von Anfang an drei Dinge garantieren:

- `pre-data` ist fuer sich ausfuehrbar und enthaelt keine verdeckten
  Abhaengigkeiten auf `post-data`
- JSON bleibt rueckwaertskompatibel und fuehrt den Split ueber `ddl_parts`
  statt ueber einen Typwechsel von `ddl` ein
- Diagnoseobjekte wie Notes und `skipped_objects` bleiben im Split-Fall
  operativ brauchbar, weil ihre Phase explizit sichtbar ist
