# Implementierungsplan: 0.9.2 - Arbeitspaket 6.7 Tests, Fehlercode-Matrix und E2E-Round-Trip nachziehen

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.7 (Tests, Fehlercode-Matrix und E2E-Round-Trip
> nachziehen)
> **Status**: Draft (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 3.2,
> Abschnitt 6.7, Abschnitt 7, Abschnitt 8 und Abschnitt 9.6;
> `docs/quality.md`;
> `docs/ddl-generation-rules.md`;
> `adapters/driven/formats/src/test/resources/fixtures/ddl/...`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliGenerateTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EPostgresTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataImportE2EPostgresTest.kt`;
> `docs/ImpPlan-0.9.2-6.1.md`;
> `docs/ImpPlan-0.9.2-6.6.md`.

---

## 1. Ziel

Arbeitspaket 6.7 macht den 0.9.2-Milestone verifikationsseitig
belastbar:

- bestehende Golden Masters fuer den `single`-Pfad werden eingefroren
- neue Golden Masters und Contract-Tests fuer `pre-data` /
  `post-data` werden nachgezogen
- die dokumentierten Fehler- und Warncode-Ledger werden systematisch
  gegen Tests gespiegelt
- ein echter, aber schmaler End-to-End-Round-Trip belegt den
  Gesamtpfad ueber Export und Import

6.7 ist damit das Abschlussarbeitspaket fuer den Milestone. Es fuehrt
keine neue Fachlogik ein, sondern zieht die Beleglage fuer 6.1 bis 6.6
auf ein Niveau, das Merge, Review und spaetere Regressionen sauber
absichert.

Nach 6.7 soll klar gelten:

- `schema generate` ist fuer `single` und `pre-post` ueber Golden
  Masters, JSON, Report und CLI-Preflight belegt
- Fehlercodes E006-E121 und die fuer 0.9.2 relevanten Warncodes sind
  nicht nur dokumentiert, sondern einem nachweisbaren Test oder einem
  strikt dokumentierten Ausnahmepfad zugeordnet
- mindestens ein echter Round-Trip zeigt:
  - Export aus Quell-DB
  - Import in Ziel-DB
  - erneuter Schema-Read / Vergleich

Abhaengigkeiten zu 6.1 bis 6.6:

- 6.7 setzt die fachlichen und technischen Vertraege aus 6.1 bis 6.6
  voraus
- das Paket kann vorbereitend frueher begonnen werden
- final abschliessbar ist es aber erst, wenn Split-Output, DDL-Zuordnung,
  DDL-Haertung und Runner-Zuschnitt in ihrem Zielzustand stehen

---

## 2. Ausgangslage

Das Repo hat bereits eine starke Testbasis, aber fuer 0.9.2 bleiben
drei Luecken offen:

- Split-spezifische Golden Masters und Contract-Tests fehlen noch
- Fehler- und Warncodes sind nicht systematisch gegen ein Ledger
  gespiegelt
- es existieren zwar Export- und Import-E2E-Tests, aber kein echter
  kombinierter Round-Trip ueber den Gesamtpfad

Der aktuelle Bestand ist brauchbar, aber noch nicht der 0.9.2-
Zielzustand:

- es gibt bestehende DDL-Fixtures unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- `CliGenerateTest` und `SchemaGenerateRunnerTest` decken bereits viele
  Schema-Generate-Pfade ab
- `DataExportRunnerTest` und `DataImportRunnerTest` sichern Exit-Pfade,
  Resume und Summary fuer Export/Import umfangreich ab
- `DataExportE2EPostgresTest` / `DataImportE2EPostgresTest` sowie die
  MySQL-Pendants belegen reale Teilpfade

Die offene Qualitaetsluecke laut `docs/quality.md` bleibt trotzdem
klar:

- kein echter E2E-Round-Trip-Test
- Fehler- und Warncodes nicht systematisch gegen Validierungs- bzw.
  Generator-Matrix getestet

Fuer 0.9.2 reicht "es gibt schon viele Tests" nicht mehr aus:

- der neue Split-Vertrag erzeugt neue Artefaktformen
- 6.5 und 6.6 veraendern technische Hotspots, die regressionsanfaellig
  sind
- ohne Matrix und Round-Trip bleibt Review zu stark auf Stichproben
  angewiesen

---

## 3. Scope fuer 6.7

### 3.1 In Scope

- Golden-Master-Strategie fuer `single`, `pre-data` und `post-data`
  festziehen
- CLI- und Runner-Tests fuer Split-Validierung, Dateinamenlogik,
  JSON- und Report-Vertrag nachziehen
- Fehlercode-Ledger E006-E121 und separates Warncode-Ledger fuer
  0.9.2-relevante `W-*`-Codes aufbauen
- mindestens einen schmalen echten E2E-Round-Trip ueber Export und
  Import ergaenzen
- vorhandene Teil-E2E-Tests und Contract-Tests konsolidieren, statt
  redundant zu duplizieren

### 3.2 Bewusst nicht Teil von 6.7

- neue Fachlogik im Generator-, Runner- oder Import-/Export-Pfad
- breit angelegte Vollabdeckung fuer jede Dialektkombination im E2E-Test
- bytegenaue Gleichheit von `single` vs. `pre-data + post-data`
- unendliche Testausweitung fuer jede Randkombination im selben Paket

Praezisierung:

6.7 loest "wie belegen wir 0.9.2 belastbar?", nicht "wie testen wir
jedes Detail auf jeder Ebene mehrfach?".

---

## 4. Leitentscheidungen

### 4.1 Golden Masters bleiben die Referenz fuer sichtbare SQL-Artefakte

Verbindliche Folge:

- bestehende `single`-Golden-Master-Fixtures bleiben eingefroren, ausser
  dort, wo 6.5 bewusst sichtbare Aenderungen erzwingt
- fuer den Split-Fall kommen neue Referenzen hinzu:
  - `pre-data`
  - `post-data`
- die Referenz fuer den Split ist semantisch, nicht byte-identisch zu
  `single`

Praezisierung:

- `single` bleibt Rueckwaertskompatibilitaetsanker
- `pre-data` / `post-data` pruefen die neue sichtbare Artefaktform
- ein Test darf nicht erwarten, dass `cat pre-data.sql post-data.sql`
  exakt der alten `single.sql` entspricht

### 4.2 JSON- und Report-Vertraege werden als Contract-Tests behandelt

Verbindliche Folge:

- Split-JSON und Split-Report werden nicht nur implizit ueber
  Implementierungsdetails abgesichert
- es gibt explizite Contract-Tests fuer:
  - `split_mode`
  - `ddl_parts`
  - `phase`
  - `notes`
  - `skipped_objects`

Wichtig:

- Single-JSON bleibt eigener Rueckwaertskompatibilitaetsvertrag
- Split-JSON ist kein "abgeleiteter Sonderfall", sondern ein eigener
  Outputvertrag
- JSON- und Report-Tests arbeiten fuer 0.9.2 auf demselben
  `DdlResult`-Testanker:
  - derselbe Builder / dieselbe Fixture-Quelle
  - zwei Kanaele
  - ein gemeinsamer Vergleichskontext vor der Kanalserialisierung

### 4.3 Die Fehlercode-Matrix wird als Test-Ledger gefuehrt

Verbindliche Folge:

- E006-E121 werden nicht lose "irgendwo mitgetestet"
- fuer den Error-Ledger gilt dabei eine harte Trennlinie:
  - jeder Code aus E006-E121 erscheint genau einmal
  - entweder als aktiv nachgewiesener Codepfad
  - oder als bewusst dokumentierter `not_applicable`-Eintrag
- fuer 0.9.2 relevante Warncodes werden in einem separaten Warncode-
  Ledger gefuehrt; 0.9.2-relevant bedeutet:
  - der Code wird durch 0.9.2 neu eingefuehrt
  - oder seine Sichtbarkeit in CLI, JSON oder Report wird durch 0.9.2
    neu vertraglich relevant
  - oder seine Split-Semantik wird in 0.9.2 neu abgesichert
  - fuer 0.9.2 ist der Pflichtkatalog zentral und abschliessend:
    - `W113`
    - `W120`
- fuer jeden Ledger-Eintrag gibt es einen expliziten Nachweis:
  - vorhandener Test
  - neu anzulegender Test
  - oder bewusst dokumentierter, in 0.9.2 nicht direkt automatisierter
    Pfad mit Begruendung

Praezisierung:

- die Matrix ist ein Arbeits- und Review-Artefakt, kein nur verbaler
  Wunsch
- Primaerquelle fuer Generator-/Spatial-Codes bleibt
  `docs/ddl-generation-rules.md`
- Runner-Exit-Codes und Validierungsfehler muessen auf die tatsaechlich
  getesteten Stellen im Repo verweisen
- die Ledgers werden fuer 0.9.2 als maschinenlesbare YAML-Dateien unter
  festen Pfaden in `docs/` gefuehrt:
  - `docs/error-code-ledger-0.9.2.yaml`
  - `docs/warn-code-ledger-0.9.2.yaml`
- der normative Schema-Quellort fuer beide Ledger ist fuer 0.9.2
  eindeutig:
  - `docs/code-ledger-0.9.2.schema.json`
- Dokument, Implementierungstest und Parser-Validierung muessen auf
  denselben Schema-Quellort verweisen
- beide Ledger-Dateien verwenden fuer 0.9.2 dieselbe feste Struktur:
  - Top-Level:
    - `version`
    - `entries`
  - pro `entries[]`-Eintrag:
    - `code`
    - `level`
    - `entry_type`
- fuer die Nachweisgranularitaet gilt:
  - ein Error-Code erscheint genau einmal als Ledger-Eintrag
  - mehrere aktive Nachweispfade werden innerhalb dieses einen Eintrags
    ueber `evidence_paths[]` modelliert
  - jedes `evidence_paths[]`-Element haelt mindestens:
    - `source`
    - `path_type`
- `entry_type = standard` und `entry_type = rest_path` sind fuer 0.9.2
  strikt disjunkte Modi:
  - ein Eintrag ist genau einem Modus zugeordnet
  - die Pflichtfeldmengen gelten exklusiv fuer den gewaehlten Modus
- fuer `entry_type = standard` sind genau diese Felder Pflicht:
  - `code`
  - `level`
  - `test_path`
  - `entry_type`
  - `evidence_paths`
- fuer `level = error` im Error-Ledger ist zusaetzlich Pflicht:
  - `status`
  - erlaubte Werte:
    - `active`
    - `not_applicable`
- fuer `entry_type = rest_path` sind zusaetzlich Pflicht:
  - `why_not_automated`
  - `evidence_owner`
  - `priority`
  - `planned_remediation`
- ein begleitender Test prueft mindestens:
  - jeder im Ledger gefuehrte Code existiert in der referenzierten
    Doku- oder Codequelle
  - fuer `entry_type = standard` existiert jeder referenzierte
    `test_path`
  - fuer `entry_type = rest_path` sind `why_not_automated`,
    `evidence_owner`, `priority` und `planned_remediation` befuellt
  - `evidence_paths[]` ist befuellt und jedes Element traegt gueltige
    Werte fuer `source` und `path_type`
  - die Ledger-Dateien bestehen eine Schema-Validierung fuer alle
    Pflichtfelder und erlaubten Werte von `level`, `path_type`,
    `entry_type` und `status`
- Restpfad-Eintraege sind nur mit `entry_type = rest_path` zulaessig

Damit gilt fuer 6.7 explizit:

- Error-Ledger:
  - E006-E121
  - jeder Eintrag mit `status = active` oder `status = not_applicable`
- Warncode-Ledger:
  - in 0.9.2 genau fuer `W113` und `W120`
- beide Ledgers werden getrennt gefuehrt und separat reviewed

### 4.4 Der E2E-Round-Trip bleibt schmal und echt

Verbindliche Folge:

- 6.7 fuehrt keinen ueberfrachteten Mega-Test ein
- es reicht ein echter, schmaler Pfad mit hoher Aussagekraft
- bevorzugt wird ein Pfad, der vorhandene Export-/Import-E2E-Bausteine
  wiederverwendet

Praezisierung:

- der Test muss den Gesamtpfad belegen:
  - Quellschema + Quelldaten
  - Export in ein reales Austauschformat
  - Import in eine frische Ziel-DB
  - Schema-Read oder Vergleich gegen Erwartung
  - Datenvergleich gegen Erwartung
- der Vergleich ist strukturell, nicht textuell:
  - normalisierter Schema-Vergleich
  - stabile Reihenfolgeregel
  - explizit erlaubte Nicht-Diffs nur dort, wo sie fachlich begruendet
    sind
- fuer die Datenvalidierung gilt mindestens:
  - Zeilenanzahl pro betroffener Tabelle
  - stabile Schluesselwerte oder aequivalente Referenzprojektion
  - mindestens ein fachlich aussagekraeftiger Inhaltswert pro
    exportierter Tabelle
- fuer den strukturellen Schema-Vergleich gelten mindestens diese
  Normalisierungsregeln:
  - Reihenfolge irrelevanter Sammlungen wird ueber stabile Sortierung
    nach fachlichem Identifikator normalisiert, nicht ueber
    Einfuege-Reihenfolge
  - fachlich aequivalente Default-Darstellungen werden vor dem Vergleich
    auf eine kanonische Form abgebildet
- nicht erforderlich ist ein vollstaendiger Dialekt-Cross-Product-Test

### 4.5 Testbreite wird entlang der Ebenen verteilt, nicht in einen Test gepresst

Verbindliche Folge:

- Golden Masters pruefen sichtbare SQL-Artefakte
- CLI-/Runner-Tests pruefen Exit-Codes, Flags und Output-Kanaele
- Matrix-Tests pruefen Fehlercode-Abdeckung
- E2E prueft den echten Gesamtpfad

Damit gilt fuer 6.7:

- keine Ebene muss alles alleine beweisen
- dieselbe fachliche Aussage darf auf zwei Ebenen erscheinen, aber nicht
  vierfach redundante Testlast erzeugen

---

## 5. Konkrete Arbeitsschritte

### 5.1 Golden-Master-Bestand fuer `single` einfrieren

- bestehende `single`-Fixtures unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
  als Rueckwaertskompatibilitaetsanker behandeln
- ein expliziter Non-Regression-Test blockiert unbeabsichtigte Deltas im
  `single`-Bestand
- nur dort anfassen, wo 6.5 oder ein bewusst dokumentierter
  Vertragswechsel sichtbare SQL-Aenderungen erzwingt
- fuer jede geaenderte `single`-Fixture muss die Begruendung im
  Review-Kontext klar sein:
  - Haertung
  - neue Diagnose-Regel
  - bewusstes Output-Contract-Update
- jede zulaessige Abweichung wird in einer festen 6.5-Ausnahmeliste
  gefuehrt:
  - `docs/ddl-single-exceptions-0.9.2.yaml`
- die Ausnahmeliste ist fuer 6.7 ein pflichtiges Review-Artefakt und
  haelt pro Eintrag mindestens fest:
  - `fixture_path`
  - `reason`
  - `source_ap`
  - `approved_by`

### 5.2 Split-Golden-Masters pro Dialekt und Fixture-Typ nachziehen

- fuer den Split-Fall neue Referenzartefakte einfuehren:
  - `*.pre-data.sql`
  - `*.post-data.sql`
- die Auswahl der Fixtures bleibt zielgerichtet, aber deterministisch:
  - fuer jeden 0.9.2-Pflichtdialekt mindestens:
    - 3 Basisfaelle
    - 1 Trigger-Fall
    - 1 Fall mit Routine-abhaengiger View
    - 1 Diagnosefall
- Kardinalitaetsregel fuer 6.7:
  - die 3 Basisfaelle sind eigenstaendige, generische Split-Faelle
  - Trigger-, Routine-View- und Diagnosefall zaehlen nicht in diese 3
    Basisfaelle hinein
  - der Mindestumfang pro Pflichtdialekt umfasst damit 6 disjunkte
    Fixture-Rollen, vorbehaltlich dokumentierter Surrogate oder
    Entfaelle fuer featurearme Dialekte
- als 0.9.2-Pflichtdialekte gelten nur Dialekte, die im bestehenden
  0.9.2-Test- und CI-Umfang bereits als Split-relevante Zielsysteme
  gefuehrt werden
- wenn ein Dialekt nicht zu diesem Pflichtumfang gehoert, ist er fuer
  6.7 ein Kann-Dialekt und kein Abnahmekriterium
- dieses Set ist zugleich das maximale Pflichtset pro Dialekt fuer 6.7:
  - weitere Split-Fixtures sind Kann-Faelle
  - neue Muss-Fixtures nur bei nachgewiesenem Vertragsloch
- fuer Dialekte mit bewusst kleinerem Feature-Umfang sind explizite
  Surrogat- oder Ausnahmefaelle zulaessig:
  - wenn ein Dialekt keine echte Routine-abhaengige View sinnvoll
    abbildet, wird ein fachlich aequivalenter Split-Zuordnungsfall als
    Surrogat dokumentiert
  - wenn ein Trigger- oder Diagnosefall im Dialekt nicht natuerlich
    vorkommt, muss die Ausnahme im Reviewkontext und in der Fixture-
    Auswahl begruendet sein
- jede Ausnahme oder jeder Surrogatfall braucht eine pruefbare
  Checkliste:
  - betroffener Dialekt
  - fehlendes Original-Feature oder fehlender Naturfall
  - gewaehlter Surrogatfall oder begruendeter Entfall
  - welche 6.3-/6.4-/6.5-Aussage damit trotzdem belegt wird
  - Referenz auf die konkrete Fixture oder den Review-Nachweis
- "pro Dialekt mindestens" ist damit kein Zwang zu kuenstlicher
  Fixture-Explosion, sondern ein Untergrenze mit dokumentierter
  Ausnahmefuehrung fuer featurearme Dialekte
- Priorisierung fuer 6.7:
  - Muss:
    - Basisfall
    - Trigger-Fall oder dokumentierter Entfall
    - Routine-View-Fall oder dokumentierter Surrogatfall
    - Diagnosefall oder dokumentierter Entfall
  - Kann:
    - zusaetzliche Dialekt-Sonderfaelle ueber dieses Set hinaus
- die Liste der Pflichtdialekte wird fuer 6.7 nicht ad hoc im Review
  erweitert, sondern nur ueber bewusstes Plan-Update
- Golden Masters muessen die 6.3-Zuordnungsregeln sichtbar machen:
  - Trigger nicht in `pre-data`
  - Functions/Procedures nicht in `pre-data`
  - Views mit Routine-Abhaengigkeit in `post-data`

### 5.3 CLI- und Runner-Tests fuer Split-Vertrag vervollstaendigen

- `CliGenerateTest`, `SchemaGenerateRunnerTest` und
  `SchemaGenerateHelpersTest` fuer 0.9.2-Vertrag nachziehen
- Pflichtbereiche:
  - `--split pre-post`
  - Dateinamenableitung fuer den dateibasierten Basispfad-Fall
  - JSON-Output
  - Sidecar-Report
  - Exit-2-Fehlerpfade fuer unzulaessige Kombinationen
- Single- und Split-Fall muessen explizit nebeneinander abgesichert
  werden, nicht nur implizit ueber Defaults

### 5.4 JSON-/Report-Contract-Tests schaerfen

- Split-JSON und Split-Report gegen denselben `DdlResult`-Bestand
  pruefen
- dafuer einen gemeinsamen Testanker verwenden:
  - Shared Kotlin-Builder fuer `DdlResult`
  - JSON-Serializer und Report-Writer greifen auf denselben
    Ausgangszustand zu
- fuer 0.9.2 ist der Shared Builder die bevorzugte Form:
  - keine doppelte Erwartungswelt ueber getrennte Fixtures
  - flexibel fuer `globalNotes`, gemischte Phasen und
    Split-spezifische Diagnosefaelle
- Pflichtfelder:
  - `split_mode`
  - `ddl_parts.pre_data`
  - `ddl_parts.post_data`
  - `phase` in Kebab-Case
  - stabile Reihenfolge von `notes` und `skipped_objects`
- Single-Vertrag weiterhin explizit pruefen:
  - `ddl` vorhanden
  - keine `split_mode`-/`ddl_parts`-Felder

### 5.5 Fehler- und Warncode-Ledger aufbauen

- ein explizites Error-Ledger fuer E006-E121 erstellen oder nachziehen
- fuer Codes ohne aktiven 0.9.2-Nachweispfad ist ein expliziter
  `not_applicable`-Eintrag Pflicht; stilles Weglassen ist unzulaessig
- ein separates Warncode-Ledger fuer die in 0.9.2 zentral festgelegten
  Warncodes `W113` und `W120` erstellen oder nachziehen
- die Ledgers werden als maschinenlesbare Dateien unter `docs/`
  materialisiert:
  - `docs/error-code-ledger-0.9.2.yaml`
  - `docs/warn-code-ledger-0.9.2.yaml`
  - `docs/code-ledger-0.9.2.schema.json`
- das erstmalige Anlegen dieser drei Referenzartefakte ist Teil von 6.7
  selbst und keine vorgelagerte Basisarbeit
- jeder Ledger-Eintrag haelt mindestens fest:
  - existierender Test oder neuer Zieltest bei `entry_type = standard`
  - mindestens einen Nachweispfad in `evidence_paths[]` mit:
    - `source`
    - `path_type`
- bei `entry_type = rest_path` zusaetzlich:
  - `why_not_automated`
  - `evidence_owner`
  - `priority`
  - `planned_remediation`
- ein begleitender Test validiert mindestens:
  - jeder Ledger-Code ist in der referenzierten Quelle auffindbar
  - fuer `entry_type = standard` existiert jeder referenzierte
    `test_path`
  - fuer `entry_type = rest_path` sind `why_not_automated`,
    `evidence_owner`, `priority` und `planned_remediation` befuellt
  - `evidence_paths[]` ist befuellt und traegt pro Element gueltige
    Werte fuer `source` und `path_type`
  - eine Schema-Validierung prueft alle Pflichtfelder sowie die
    erlaubten Werte von `level`, `path_type`, `entry_type` und
    `status`
  - fuer das Error-Ledger ist pro Code aus E006-E121 genau ein Eintrag
    vorhanden, entweder mit `status = active` oder mit
    `status = not_applicable`
- die Schema-Validierung laeuft gegen den normativen Schema-Quellort
  `docs/code-ledger-0.9.2.schema.json`
- die Matrix darf aus mehreren Testebenen zusammengesetzt sein
- Ziel ist Nachweisbarkeit, nicht zwingend ein Test pro Code in nur
  einem Modul

Praezisierung:

- Codes wie `E020`, `E052` bis `E056`, `E120`, `E121` muessen fuer
  0.9.2 sichtbar im Error-Ledger vorkommen
- `W113` und `W120` muessen fuer 0.9.2 sichtbar im Warncode-Ledger
  vorkommen
- bereits vorhandene Tests werden nicht dupliziert, sondern sauber
  referenziert
- Luecken werden als neue Tests oder bewusst dokumentierte Restpunkte
  ausgewiesen

### 5.6 Schmalen echten E2E-Round-Trip ergaenzen

- vorhandene Export- und Import-E2E-Bausteine als Grundlage nutzen
- bevorzugt ein PostgreSQL- oder MySQL-Pfad mit bereits existierenden
  Containertests
- 6.7 verlangt genau einen verpflichtenden Basispfad; ein zweiter
  Dialektpfad ist fuer dieses AP optionaler Zusatznutzen, kein Muss
- der neue Test geht explizit ueber beide Richtungen:
  - Export
  - Import
  - Schema-Read / Vergleich
  - Datenvalidierung
- Ziel ist ein echter End-to-End-Nachweis, kein weiterer Teilpfadtest
- der Vergleich wird ueber einen gemeinsamen Test-Comparator umgesetzt,
  nicht ueber ad-hoc-Assertions pro Testfall

Wichtig:

- der Test soll schmal bleiben
- keine Mehrdialekt-Matrix in einem einzigen Test
- stabile, kleine Datenbasis und klarer Vergleichsmassstab
- Vergleichsvertrag fuer den Round-Trip:
  - struktureller Schema-Vergleich nach erneutem Read
  - keine Textvergleiche von SQL-Artefakten
  - Reihenfolge irrelevanter Sammlungen wird normalisiert
  - explizit erlaubte Unterschiede muessen vorab dokumentiert sein
  - stabile Sortierung erfolgt nach fachlichem Identifikator statt nach
    Einfuege-Reihenfolge
  - fachlich aequivalente Default-Darstellungen werden vor dem
    Vergleich kanonisiert
  - verglichen wird eine normalisierte Read-Modell-Projektion, nicht
    die rohe Einlesereihenfolge
  - Tabellen, Views, Routinen und Sequenzen werden nach voll
    qualifiziertem Namen sortiert
  - untergeordnete Sammlungen wie Spalten, Indizes und Constraints
    werden nach stabilem fachlichem Schluessel sortiert
  - Datenvergleich mindestens ueber:
    - Zeilenanzahl pro betroffener Tabelle
    - stabile Schluesselwerte oder aequivalente Referenzprojektion
    - mindestens einen fachlich relevanten Inhaltswert pro Tabelle
- explizit erlaubte Unterschiede laufen nicht implizit mit, sondern
  ueber eine kleine, benannte Allowlist direkt am Testfall oder an der
  Fixture:
  - Feldpfad / Vergleichsaspekt
  - fachliche Begruendung
  - erwarteter Unterschied

### 5.7 Testdokumentation und Review-Artefakte nachziehen

- `docs/quality.md` darf nach Umsetzung den fehlenden E2E-Round-Trip und
  die fehlenden Error-/Warncode-Ledger nicht mehr als offene 0.9.2-Punkte
  fuehren
- falls die Fehlercode-Quelle in Doku oder Guide verankert ist, muss die
  Matrix darauf konsistent verweisen
- Review sollte fuer 6.7 einen einfachen Nachweis lesen koennen:
  - welche Fixtures neu sind
  - welche Contract-Tests neu sind
  - welche Codes neu abgedeckt wurden

---

## 6. Verifikation

### 6.1 Pflichtfaelle fuer Golden Masters

- `schema generate` ohne `--split` bleibt fuer bestehende `single`-
  Fixtures unveraendert, ausser bei bewusst dokumentierten 6.5-Diffs
- ein expliziter `single`-Golden-Master-Test scheitert bei jedem
  unbeabsichtigten Delta ausserhalb der bekannten 6.5-Ausnahmeliste
- die bekannte 6.5-Ausnahmeliste liegt fuer 6.7 unter:
  - `docs/ddl-single-exceptions-0.9.2.yaml`
- fuer 6.7 wird `--output` in den Split-Pflichtfaellen explizit als
  Dateipfad mit Basisdateiname interpretiert, nicht als Verzeichnis-
  Pfad
- `schema generate --split pre-post --output out/schema.sql` erzeugt
  genau:
  - `out/schema.pre-data.sql`
  - `out/schema.post-data.sql`
- die Dateinamen-Ableitung fuer Verzeichnis-Pfade oder andere
  plattformspezifische Sonderfaelle ist nicht Teil dieses 6.7-
  Pflichtfalls und wird nicht ueber diese Golden-Master-Assertion
  abgenommen
- Split-Fixtures belegen die 6.3-Phasenregeln sichtbar

### 6.2 Pflichtfaelle fuer JSON und Report

- `--split pre-post --output-format json` liefert:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte `notes`
  - phasenattribuierte `skipped_objects`
- Sidecar-Report spiegelt denselben Phasenbezug
- Single-JSON bleibt rueckwaertskompatibel
- JSON und Report werden aus demselben `DdlResult`-Testanker
  verglichen, nicht aus zwei getrennt aufgebauten Erwartungswelten

### 6.3 Pflichtfaelle fuer CLI- und Runner-Vertrag

- `--split pre-post` ohne `--output` und ohne JSON endet mit Exit 2
- `--split pre-post --generate-rollback` endet mit Exit 2
- die Dateinamenableitung ist testseitig fuer genau den
  dateibasierten Basispfad-Fall `out/schema.sql` belegt
- Verzeichnis-Pfade und andere plattformspezifische Sonderfaelle sind
  in 6.7 bewusst nicht abgedeckt und koennen spaeter in einem
  Folge-AP separat vertraglich erweitert werden
- 6.6-Refactor bleibt in Exit-Code- und Resume-Semantik stabil

### 6.4 Pflichtfaelle fuer Error- und Warncode-Ledger

- fuer E006-E121 existiert ein expliziter Error-Ledger-Nachweis
- fuer jeden Code aus E006-E121 existiert genau ein Ledger-Eintrag mit
  `status = active` oder `status = not_applicable`
- fuer die in 0.9.2 zentral festgelegten Warncodes `W113` und `W120`
  existiert ein expliziter Warncode-Ledger-Nachweis
- die Nachweise liegen fuer 0.9.2 genau unter:
  - `docs/error-code-ledger-0.9.2.yaml`
  - `docs/warn-code-ledger-0.9.2.yaml`
  - `docs/code-ledger-0.9.2.schema.json`
- jeder Ledger-Eintrag ist einem Test oder einem bewusst dokumentierten
  Restpfad mit Pflichtfeldern zugeordnet
- fuer `entry_type = standard` ist `test_path` verpflichtend;
  fuer `entry_type = rest_path` ist stattdessen der Nachweis ueber
  `why_not_automated`, `evidence_owner`, `priority` und
  `planned_remediation` verpflichtend
- Generator-/Report-Codes und Validator-Codes werden nicht vermischt,
  sondern mit ihrer Quelle und Ebene referenziert

### 6.5 Pflichtfall fuer Integrationsverifikation

- mindestens ein echter Round-Trip-Test ueber:
  - Schema / Daten in Quell-DB
  - Export
  - Import in Ziel-DB
  - erneuten strukturellen Schema-Read / Vergleich gegen Erwartung
  - Datenvergleich ueber Zeilenanzahl, stabile Schluesselwerte oder
    aequivalente Referenzprojektion sowie mindestens einen
    Inhaltswert pro Tabelle
- genau ein Basispfad ist fuer 6.7 verpflichtend; ein zweiter
  Dialektpfad bleibt erwuenschter Zusatzfall, aber kein Abnahmekriterium
- der Vergleich nutzt einen gemeinsamen normalisierenden Comparator mit
  expliziter Allowlist fuer fachlich begruendete Nicht-Diffs

Erwuenschte Zusatzfaelle:

- dedizierte Split-Fixtures fuer MySQL- und SQLite-Trigger-Szenarien
- Split-Fixtures mit routine-abhaengigen Views
- JSON-Contract-Smokes fuer externe Consumer

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `docs/quality.md`
- `docs/ddl-generation-rules.md`
- `docs/error-code-ledger-0.9.2.yaml`
- `docs/warn-code-ledger-0.9.2.yaml`
- `docs/code-ledger-0.9.2.schema.json`
- `docs/ddl-single-exceptions-0.9.2.yaml`
- `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliGenerateTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpersTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataExportE2EPostgresTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/DataImportE2EPostgresTest.kt`
- ggf. die MySQL-E2E-Pendants unter
  `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`

Wahrscheinlich mit betroffen:

- Validator- und Schema-Tests, die Fehlercodes im Bereich E006-E121
  bereits heute pruefen
- weitere CLI-Smoke-Tests fuer `schema generate`
- Testcontainers-basierte Integrationskonfiguration fuer PostgreSQL oder
  MySQL

Praezisierung:

- 6.7 erzwingt nicht automatisch Produktionscode-Aenderungen
- falls bei Matrix- oder Round-Trip-Arbeit Produktionsluecken sichtbar
  werden, muessen diese als bewusst getrennte Nachbesserung behandelt
  werden

---

## 8. Risiken und Abgrenzung

### 8.1 Die Error-/Warncode-Ledger koennen ohne harte Quelle unscharf werden

Wenn die Matrix nur aus Erinnerung gepflegt wird, entsteht ein zweiter
inoffizieller Contract.

Mitigation:

- Dokuquelle klar benennen
- Test-Ledger mit konkreten Dateireferenzen pflegen
- Generator-, Validator- und Runner-Codes getrennt markieren

### 8.2 Ein ueberfrachteter E2E-Test wird instabil und teuer

Zu viele Dialekte, Sonderfaelle oder Artefaktformen in einem Test
verlangsamen den Milestone und erschweren Fehlersuche.

Mitigation:

- ein schmaler echter Pfad statt mehrerer halb-integrierter Monster-
  Specs
- vorhandene E2E-Bausteine wiederverwenden
- Datenbasis klein und deterministisch halten

### 8.3 Golden-Master-Churn kann Reviews unnoetig aufblaehen

Wenn zu viele Fixtures gleichzeitig kippen, sinkt der Reviewwert.

Mitigation:

- `single` nur mit expliziter Begruendung aendern
- Split-Fixtures gezielt und sparsam aufbauen
- Diffs nach Vertragsaenderung gruppieren, nicht wahllos neu aufzeichnen

### 8.4 Matrix-Vollstaendigkeit kann in Scheingenauigkeit kippen

Nicht jeder Code braucht denselben Testtyp. Reine Mengenabdeckung hilft
wenig, wenn die Aussagekraft fehlt.

Mitigation:

- pro Code den passenden Testtyp waehlen
- vorhandene Tests referenzieren statt duplizieren
- Luecken sichtbar machen, statt sie hinter einem "100%"-Wert zu
  verstecken

---

## 9. Ist-Stand der betroffenen Codebasis (ermittelt 2026-04-19)

### 9.1 Golden-Master-Bestand

15 DDL-Fixtures unter `adapters/driven/formats/src/test/resources/fixtures/ddl/`:

| Schema | postgresql | mysql | sqlite |
|--------|-----------|-------|--------|
| minimal | ✓ | ✓ | ✓ |
| e-commerce | ✓ | ✓ | ✓ |
| all-types | ✓ | ✓ | ✓ |
| full-featured | ✓ | ✓ | ✓ |
| spatial | ✓ | ✓ | ✓ |

`DdlGoldenMasterTest` (15 Tests): 12 non-spatial + 3 spatial, vergleicht
`stripHeader(actual) shouldBe stripHeader(expected)`.

Neue Split-Fixtures (`.pre-data.sql`, `.post-data.sql`) existieren noch
nicht.

### 9.2 E2E-Testbestand

4 E2E-Testdateien mit Testcontainers:
- `DataExportE2EPostgresTest.kt` / `DataImportE2EPostgresTest.kt`
- `DataExportE2EMysqlTest.kt` / `DataImportE2EMysqlTest.kt`

Alle mit `IntegrationTag` getaggt. Kein kombinierter Export→Import
Round-Trip-Test vorhanden.

### 9.3 Fehler- und Warncodes im Produktionscode

**27 Error-Codes** (E001–E020, E052–E056, E060, E120–E121):
- SchemaValidator: E001–E020, E120, E121
- DDL-Generatoren: E052–E056
- Split-Analyse: E060

**7 Warn-Codes**: W100, W102, W103, W111, W112, W113, W120

### 9.4 Testcontainers-Module

- `adapters/driving/cli` (PostgreSQL + MySQL)
- `test/integration-postgresql`
- `test/integration-mysql`
- `adapters/driven/integrations`

### 9.5 Schema-Fixtures mit Split-relevanten Objekten

| Fixture | Funktionen | Views | Trigger | Procedures |
|---------|-----------|-------|---------|------------|
| minimal | - | - | - | - |
| e-commerce | - | - | - | - |
| all-types | - | - | - | - |
| full-featured | calc_total | active_orders, monthly_stats | trg_updated, trg_insert | update_status |
| spatial | - | - | - | - |
| view-function-deps | calc_total | simple/computed/dependent/heuristic | trg_audit | - |

Nur `full-featured` und `view-function-deps` haben Split-relevante
Objekte (Functions, Triggers, Views mit Dependencies).

---

## 10. Konkrete Implementierungsschritte (verfeinert)

Die folgenden Schritte verfeinern Abschnitt 5 mit konkreten Datei-,
Methoden- und Codeaenderungen. Jeder Schritt ist eigenstaendig
commitbar.

### 10.1 Schritt A — Split-Golden-Masters erzeugen

Neue Fixture-Dateien unter `fixtures/ddl/`:

Fuer `full-featured` (hat Functions, Views, Triggers, Procedures):
- `full-featured.postgresql.pre-data.sql`
- `full-featured.postgresql.post-data.sql`
- `full-featured.mysql.pre-data.sql`
- `full-featured.mysql.post-data.sql`
- `full-featured.sqlite.pre-data.sql`
- `full-featured.sqlite.post-data.sql`

Fuer `view-function-deps` (hat Function-abhaengige Views):
- `view-function-deps.postgresql.pre-data.sql`
- `view-function-deps.postgresql.post-data.sql`
- (MySQL und SQLite optional — Functions nicht nativ unterstuetzt)

Erzeugung: programmatisch ueber `generate()` + `renderPhase()` mit
dem bestehenden `DdlGoldenMasterTest`-Pattern.

Neuer Test in `DdlGoldenMasterTest`:

```kotlin
for (schema in splitSchemas) {
    for ((dialectName, generator) in dialects) {
        test("$schema generates correct $dialectName pre-data DDL") {
            val input = loadFixture("schemas/$schema.yaml")
            val expected = loadGoldenMaster("ddl/$schema.$dialectName.pre-data.sql")
            val result = generator.generate(input)
            stripHeader(result.renderPhase(DdlPhase.PRE_DATA)) shouldBe stripHeader(expected)
        }
        test("$schema generates correct $dialectName post-data DDL") {
            val input = loadFixture("schemas/$schema.yaml")
            val expected = loadGoldenMaster("ddl/$schema.$dialectName.post-data.sql")
            stripHeader(result.renderPhase(DdlPhase.POST_DATA)) shouldBe stripHeader(expected)
        }
    }
}
```

Abhaengigkeiten: AP 6.3 (Phasenzuordnung).

### 10.2 Schritt B — JSON-/Report-Contract-Tests

Neue Tests in `SchemaGenerateHelpersTest` oder separater Datei:

- `formatJsonOutput` mit `SplitMode.PRE_POST`: pruefen auf
  `split_mode`, `ddl_parts.pre_data`, `ddl_parts.post_data`,
  `phase` in Notes/Skips, kein `ddl`-Feld
- `formatJsonOutput` mit `SplitMode.SINGLE`: pruefen auf
  `ddl`-Feld, kein `split_mode`/`ddl_parts`
- `TransformationReportWriter.render()` mit `splitMode = "pre-post"`:
  pruefen auf `split_mode:`, `phase:` in Notes/Skips
- `TransformationReportWriter.render()` ohne splitMode:
  kein `split_mode:`, kein `phase:`

Gemeinsamer `DdlResult`-Builder fuer beide Kanaele:

```kotlin
val testResult = DdlResult(
    statements = listOf(
        DdlStatement("CREATE TABLE t;", phase = DdlPhase.PRE_DATA),
        DdlStatement("CREATE TRIGGER trg;", phase = DdlPhase.POST_DATA),
    ),
    skippedObjects = listOf(
        SkippedObject("sequence", "seq1", "not supported", phase = DdlPhase.PRE_DATA),
    ),
    globalNotes = listOf(
        TransformationNote(NoteType.WARNING, "W113", "views", "circular deps"),
    ),
)
```

Abhaengigkeiten: AP 6.4 (JSON/Report Output).

### 10.3 Schritt C — Fehlercode-Ledger (E006-E121)

Neue Datei `docs/error-code-ledger-0.9.2.yaml`:

```yaml
version: "0.9.2"
entries:
  - code: E001
    level: error
    status: active
    entry_type: standard
    test_path: "hexagon/core/src/test/.../SchemaValidatorTest.kt"
    evidence_paths:
      - source: "hexagon/core/.../SchemaValidator.kt"
        path_type: production
  # ... E002-E020, E052-E056, E060, E120-E121
```

Fuer Codes ohne direkten Test: `entry_type: rest_path` mit
`why_not_automated`, `evidence_owner`, `priority`,
`planned_remediation`.

Begleitender Test `CodeLedgerValidationTest.kt`:
- Laedt YAML
- Prueft: jeder Code E006-E121 hat genau einen Eintrag
- Prueft: jeder `test_path` existiert als Datei
- Prueft: jeder `entry_type = rest_path` hat Pflichtfelder

Schema: `docs/code-ledger-0.9.2.schema.json` (JSON Schema Draft 7).

### 10.4 Schritt D — Warncode-Ledger (W113, W120)

Neue Datei `docs/warn-code-ledger-0.9.2.yaml` analog zu Schritt C,
aber nur fuer W113 und W120.

### 10.5 Schritt E — E2E-Round-Trip-Test

Neue Testdatei (bevorzugt PostgreSQL wegen bestem Feature-Support):
`test/integration-postgresql/src/test/kotlin/.../E2ERoundTripTest.kt`

Ablauf:
1. Testcontainer PostgreSQL starten
2. Schema + Seed-Daten ueber DDL einspielen (2-3 Tabellen, ~10 Rows)
3. `data export` in JSON-Format
4. Zweiten Testcontainer oder frische DB
5. `schema generate` + DDL ausfuehren
6. `data import` aus JSON
7. Schema-Read auf Ziel-DB → struktureller Vergleich
8. Datenvergleich: Zeilenanzahl + Schluesselwerte + mindestens ein
   Inhaltswert pro Tabelle

Seed-Schema: `users` (id, name) + `orders` (id, user_id FK, amount).
Minimal, aber FK-Abhaengigkeit vorhanden.

Vergleichs-Comparator: normalisierter Schema-Vergleich ueber
`SchemaComparator` oder manuell ueber `SchemaReader`-Output.

Abhaengigkeiten: Testcontainers (bereits konfiguriert in
`test/integration-postgresql`).

### 10.6 Schritt F — `docs/quality.md` und Review-Artefakte

- E2E-Round-Trip als erledigt markieren
- Fehlercodes als erledigt markieren
- `docs/ddl-single-exceptions-0.9.2.yaml` anlegen (nur die
  `full-featured.mysql.sql` Aenderung aus AP 6.5)

---

## 11. Empfohlene Commit-Reihenfolge

```
A (Split-Golden-Masters)
 ↓
B (JSON-/Report-Contract-Tests)
 ↓
C + D (Error- und Warncode-Ledger)
 ↓
E (E2E-Round-Trip)
 ↓
F (Doku-Update)
```

Alle Edits eines Schritts abschliessen, dann einmal `docker build`.
