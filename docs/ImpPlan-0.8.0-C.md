# Implementierungsplan: Phase C - ResourceBundles und lokalisierte CLI-Ausgaben

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: C (ResourceBundles und lokalisierte CLI-Ausgaben)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.1 bis 4.6, Abschnitt 5.1 bis 5.4,
> Abschnitt 6 Phase C, Abschnitt 8, Abschnitt 9; `docs/ImpPlan-0.8.0-A.md`;
> `docs/ImpPlan-0.8.0-B.md`; `docs/roadmap.md` Milestone 0.8.0;
> `docs/design.md` Abschnitt 9; `docs/cli-spec.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt`

---

## 1. Ziel

Phase C produktiviert den sichtbaren CLI-Teil des 0.8.0-I18n-Vertrags.
Ergebnis der Phase ist eine ResourceBundle-basierte Ausgabeschicht, die
menschenlesbare CLI-Meldungen lokalisierbar macht, ohne die strukturierten
JSON-/YAML-Vertraege sprachabhaengig zu machen.

Der Teilplan liefert bewusst keine ICU4J-Utilities und keine
format-/datenpfadseitige Unicode-Logik. Er schafft die Message- und
Renderer-Grundlage, auf der die spaeteren Phasen D bis G aufsetzen.

Nach Phase C soll im CLI-Layer klar und testbar gelten:

- es gibt ein Root-/Fallback-ResourceBundle in Englisch
- Deutsch ist als erste produktive Lokalisierung verfuegbar
- Plain-Text-Ausgaben von CLI-Kommandos und Progress-Meldungen laufen ueber
  Message-Keys statt ueber frei verteilte Literal-Strings
- `OutputFormatter` und `ProgressRenderer` arbeiten gegen den in Phase B
  eingefuehrten I18n-Runtime-Kontext
- strukturierte JSON-/YAML-Ausgaben behalten ihre englischen Feldnamen und
  sprachstabilen Fehlertexte
- Fehlercodes, Objektpfade, Command-IDs und andere API-artige Felder bleiben
  unveraendert
- die Lokalisierungsschicht bleibt fokussiert auf die eigene CLI-Ausgabe und
  versucht nicht, Clikt-internen Help-/Usage-Text oder tiefe Exceptiontexte
  heuristisch zu uebersetzen

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- Phase B definiert den Runtime-Unterbau fuer:
  - `ResolvedI18nSettings`
  - Config-basierte Locale-/Timezone-/Normalization-Resolution
  - erweiterten `CliContext`
- `OutputFormatter` gibt heute frei formulierte englische Texte aus:
  - Plain-Validation-Header und Summary
  - Warnings/Errors auf stderr
  - freie `printError(...)`-Texte
- `ProgressRenderer` gibt heute frei formulierte englische
  Export-/Import-Fortschrittsmeldungen aus.
- `DataProfileCommand` besitzt einen eigenen `stderr`-Pfad fuer Status- und
  Fehlermeldungen, der bisher nicht ueber eine gemeinsame Message-Schicht
  laeuft.
- JSON-/YAML-Outputs sind heute bewusst primitive-first und englisch
  schluesselbasiert.
- `printError(...)` serialisiert in JSON/YAML aktuell ein freies
  `error`-Textfeld; laut Phase A darf dieser strukturierte Pfad in 0.8.0 nicht
  locale-abhaengig werden.
- ResourceBundles oder Message-Keys existieren im Produktionscode noch nicht.

Konsequenz:

- ohne Phase C bleibt Phase B zwar technisch sauber, aber fuer Endnutzer
  unsichtbar.
- besonders kritisch ist die Grenze zwischen lokalisierter Plain-Ausgabe und
  sprachstabilen strukturierten Fehlerpfaden: wenn Phase C das unsauber zieht,
  werden JSON/YAML-Ausgaben implizit zu locale-abhaengigen Schnittstellen.
- ebenso kritisch ist die Verteilung der Strings:
  wenn nur punktuell uebersetzt wird, bleiben schnell hybride Ausgaben aus
  Literal-Strings, Message-Keys und freiem stderr bestehen.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- ResourceBundles unter `adapters/driving/cli/src/main/resources/messages/`
- englisches Root-/Fallback-Bundle
- deutsches Bundle als erste Uebersetzung
- Einfuehrung eines `MessageResolver`-Adapters fuer CLI-Ausgaben
- Umstellung von:
  - `OutputFormatter`
  - `ProgressRenderer`
  - command-nahen Nutzertexten mit freiem stderr/stdout
- Lokalisierung von:
  - Plain-Text-Headers
  - Status- und Fortschrittsmeldungen
  - nutzerbezogenen Fehler- und Hinweistexten
- Tests fuer:
  - Bundle-Fallback
  - englische/deutsche Ausgaben
  - Stabilitaet strukturierter Fehlerpfade

### 3.2 Bewusst nicht Teil von Phase C

- ICU4J oder grapheme-/normalization-bezogene Utilities
- Uebersetzung von JSON-/YAML-Feldnamen
- Lokalisierung freier Fehlertexte in strukturierten JSON-/YAML-Payloads
- Uebersetzung von Clikt-Help oder Clikt-internen Usage-Meldungen
- Uebersetzung tiefer technischer Exceptiontexte aus Parsern, JDBC oder
  Dateisystempfaden
- finale Aktivierung von `--lang` als aktiver Override-Quelle im 0.9.0-Sinn

Praezisierung:

Phase C loest "wie werden unsere eigenen CLI-Texte lokalisiert?" und nicht
"wie wird jede Textquelle im Prozess mehrsprachig?".

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Root-Bundle ist englisch und vollstaendig

Phase C fixiert:

- `messages.properties` ist das vollstaendige Root-/Fallback-Bundle
- `messages_de.properties` enthaelt die deutsche Uebersetzung
- fehlende deutsche Keys fallen auf das englische Root-Bundle zurueck

Verbindliche Folge:

- die Uebersetzungsstrategie ist additiv und robust gegen partielle Bundles
- das Produkt bleibt bei fehlenden Uebersetzungen funktional und lesbar

### 4.2 Message-Keys statt String-Scatter

Phase C fuehrt keine halbe Lokalisierung ein, in der nur einzelne Strings
parametrisiert werden.

Verbindliche Folge:

- `OutputFormatter` und `ProgressRenderer` lesen alle menschenlesbaren Texte
  ueber Message-Keys
- auch command-nahe stderr-Statusmeldungen werden, wo praktikabel, an denselben
  Resolver angebunden
- neue Literal-Strings fuer Nutzertexte sind in Phase C nicht akzeptabel

### 4.3 Strukturierte Ausgaben bleiben sprachstabil

Phase C fixiert den wichtigsten Vertragschutz:

- JSON-/YAML-Schluessel bleiben englisch
- `command`, `status`, `exit_code`, `code`, `object` und aehnliche Felder
  bleiben unveraendert
- strukturierte `results[*].message`-Felder bleiben in 0.8.0 ebenfalls
  sprachstabil englisch und werden nicht ueber Nutzer-Locale lokalisiert
- freie Textfelder in strukturierten Fehlerpfaden bleiben in 0.8.0 ebenfalls
  sprachstabil englisch

Verbindliche Folge:

- `printError(...)` bekommt fuer Plain-Text lokalisierte Meldungen
- JSON-/YAML-`printError(...)` bleiben englisch bzw. nicht lokalisiert
- strukturierte Validation-/Warning-/Error-`message`-Felder in JSON/YAML
  bleiben ebenfalls englisch bzw. nicht lokalisiert
- die Lokalisierungsschicht darf keine locale-abhaengigen Payloads in
  strukturierte Formate leaken

### 4.4 Parameterisierte Meldungen bleiben primitive-first

Phase C fuehrt kein komplexes Message-Formatting-Framework ein.

Verbindliche Folge:

- Message-Parameter bleiben primitive Werte oder kanonische Strings
- Tabellenname, Dateipfad, Zaehler, Row-Counts oder Codes werden explizit als
  Argumente eingesetzt
- die Bundles enthalten keine unkontrollierbaren Objekt-Interpolationstricks

### 4.5 Technische Details bleiben englisch und duerfen roh weitergereicht werden

Phase C unterscheidet bewusst zwischen:

- nutzerbezogener Huelle
- technischem Detailtext

Verbindliche Folge:

- Nutzerrahmen wird lokalisiert:
  - "Config file not found"
  - "Exporting table"
  - "Validation failed"
- tiefe technische Details duerfen englisch bleiben:
  - konkrete Exception-Messages
  - Dateiformat-/Parserfehler
  - JDBC-/IO-Detailtexte

Das verhindert, dass Phase C eine vollstaendige Exception-Lokalisierung
heuristisch nachbaut.

### 4.6 Progress- und Quiet-Semantik bleibt erhalten

Lokalisierung darf den bestehenden CLI-Vertrag nicht semantisch veraendern.

Verbindliche Folge:

- `--quiet` unterdrueckt weiterhin dieselben Statusmeldungen wie bisher
- `--no-progress` veraendert nur Sichtbarkeit, nicht die Message-Keys selbst
- Lokalisierung ist nur ein Darstellungswechsel, kein Verhaltenswechsel

---

## 5. Arbeitspakete

### C.1 ResourceBundles anlegen

Anzulegen sind mindestens:

- `adapters/driving/cli/src/main/resources/messages/messages.properties`
- `adapters/driving/cli/src/main/resources/messages/messages_de.properties`

Lookup-Vertrag:

- ResourceBundle-Basename ist `messages.messages`
- der CLI-Resolver laedt also explizit `ResourceBundle.getBundle("messages.messages", ...)`
  und nicht einen impliziten oder pfadabhaengigen Variantenamen

Key-Gruppen mindestens:

- `cli.common.*`
- `cli.validation.*`
- `cli.progress.*`
- `cli.error.*`

Ziel:

- klare, nachvollziehbare Struktur fuer Nutzertexte statt eines flachen
  unsortierten Keyraums

### C.2 CLI-Message-Resolver adaptieren

Phase C braucht einen CLI-seitigen Adapter ueber dem in Phase B eingefuehrten
Runtime-Kontext.

Mindestens noetig:

- Bundle-Lookup ueber `ResolvedI18nSettings.locale`
- Fallback auf Root-Bundle
- `text(key, vararg args)` fuer parameterisierte Meldungen

Wichtig:

- kein globaler Singleton
- kein direkter Zugriff auf JVM-Default-Locale
- testbar ueber injizierte Bundles/Locale

### C.3 `OutputFormatter` auf Message-Keys umstellen

Zu lokalisieren sind mindestens:

- Validation-Header
- Tabellen-/Spalten-/Index-/Constraint-Zaehler
- "Results"
- "Validation passed/failed"
- Plain-Error-Huelle in `printError(...)`
- Warning-/Error-Praefixe, soweit sie menschenlesbarer Text sind

Explizit nicht zu lokalisieren:

- JSON-/YAML-Schluessel
- strukturierte `code`-/`object`-Werte
- strukturierte `results[*].message`-Texte in JSON/YAML
- freie technische Fehlertexte in JSON/YAML

### C.4 `ProgressRenderer` auf Message-Keys umstellen

Zu lokalisieren sind mindestens:

- `RunStarted`
- `ExportTableStarted`
- `ExportChunkProcessed`
- `ExportTableFinished`
- `ImportTableStarted`
- `ImportChunkProcessed`
- `ImportTableFinished`

Wichtig:

- numerische Formatierung bleibt stabil
- die bestehenden expliziten Formatregeln (`Locale.US` fuer technische
  Zahlenformatierung) werden nicht versehentlich durch Nutzer-Locale ersetzt,
  sofern dies die Snapshot-/Diff-Stabilitaet gefaehrdet

### C.5 Command-nahe stderr/stdout-Pfade nachziehen

Mindestens zu pruefen und falls noetig an den gemeinsamen Resolver anzubinden:

- `DataProfileCommand`-stderr
- weitere direkte `System.err.println(...)`- oder `println(...)`-Pfade in der
  CLI, die Nutzertexte und nicht nur rohe technische Details emittieren

Ziel:

- keine hybriden Ausgaben aus halb lokalisierten und halb harten
  Nutzerstrings

### C.6 Strukturierte Fehlerpfade absichern

Phase C muss den in Phase A festgezogenen Vertrag explizit im Code sichern.

Konkret:

- `printError(...)` in JSON/YAML bleibt sprachstabil englisch
- `results[*].message` in JSON/YAML bleibt sprachstabil englisch
- falls spaeter ein `message`-Feld oder aehnlicher Klartext erhalten bleibt,
  ist dessen Sprache explizit englisch
- Plain-Text `printError(...)` darf lokalisiert werden

Ziel:

- klare Trennung zwischen menschenlesbarer Shell-Ausgabe und maschinenlesbarer
  Struktur

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `adapters/driving/cli/src/main/resources/messages/...`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/...`
- `docs/implementation-plan-0.8.0.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/ImpPlan-0.8.0-A.md`
- `docs/ImpPlan-0.8.0-B.md`
- `docs/cli-spec.md`
- [DataProfileCommand.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt)

---

## 7. Akzeptanzkriterien

- [ ] Es existiert ein englisches Root-Bundle und ein deutsches Bundle fuer
      CLI-Nutzertexte.
- [ ] `OutputFormatter` nutzt fuer menschenlesbare Plain-Ausgaben Message-Keys
      statt harter Literal-Strings.
- [ ] `ProgressRenderer` nutzt fuer menschenlesbare Fortschrittsmeldungen
      Message-Keys statt harter Literal-Strings.
- [ ] Command-nahe freie Nutzertexte auf stderr/stdout sind an dieselbe
      Message-Schicht angebunden oder explizit als technische Rohtexte
      abgegrenzt.
- [ ] JSON-/YAML-Feldnamen bleiben unveraendert englisch.
- [ ] Strukturierte Fehlerpfade bleiben sprachstabil und leaken keine
      locale-abhaengigen Meldungstexte in JSON/YAML.
- [ ] Strukturierte `results[*].message`-Felder in JSON/YAML bleiben
      sprachstabil englisch und werden nicht ueber Nutzer-Locale aufgeloest.
- [ ] Fehlende deutsche Keys fallen sauber auf das Root-Bundle zurueck.
- [ ] `--quiet`- und `--no-progress`-Semantik bleiben unveraendert.

---

## 8. Verifikation

Phase C wird ueber Unit- und kleine CLI-nahe Renderer-Tests verifiziert.

Mindestumfang:

1. Bundle-Tests:
   - Root-Bundle vorhanden
   - deutsches Bundle vorhanden
   - Basename `messages.messages` wird explizit geladen
   - Fallback von `de` auf Root bei fehlendem Key
2. `OutputFormatter`-Tests:
   - englische Plain-Ausgabe
   - deutsche Plain-Ausgabe
   - JSON-/YAML-Ausgaben unveraendert englisch/stabil
   - `results[*].message` bleibt in JSON/YAML englisch/stabil
3. `ProgressRenderer`-Tests:
   - englische Meldungen
   - deutsche Meldungen
   - Zahlen-/MB-Format stabil
4. Tests fuer `printError(...)`:
   - lokalisierter Plain-Text
   - englische JSON-/YAML-Fehlerstruktur
5. Regression-Tests fuer `--quiet` und `--no-progress`, soweit die betroffenen
   Renderer das Verhalten direkt beeinflussen

---

## 9. Risiken

### R1 - Teilweise Lokalisierung fuehrt zu Mischausgaben

Wenn nur `OutputFormatter` umgestellt wird, aber freie stderr-Pfade bleiben,
entsteht schnell eine inkonsistente CLI.

### R2 - Strukturierte Fehlerpfade werden versehentlich lokalisiert

Das wuerde den in Phase A festgezogenen Vertragschutz sofort verletzen.

### R3 - Nutzer-Locale beeinflusst technische Zahlenformate

Wenn Progress- oder Plain-Renderer versehentlich numerische Formate an die
Nutzer-Locale koppeln, werden Tests und Diff-Stabilitaet fragil.

### R4 - Ueberlokalisierung technischer Exceptiontexte

Phase C darf keine halbautomatische "alles uebersetzen"-Schicht bauen, die
Parser-, JDBC- oder IO-Details unkontrolliert umformuliert.

---

## 10. Abschluss-Checkliste

- [ ] ResourceBundles fuer Englisch und Deutsch existieren.
- [ ] `OutputFormatter` und `ProgressRenderer` sind message-key-basiert.
- [ ] Strukturierte JSON-/YAML-Ausgaben bleiben sprachstabil.
- [ ] Freie Nutzertexte ausserhalb der beiden Haupt-Renderer sind entweder
      angebunden oder explizit als technische Rohtexte abgegrenzt.
- [ ] Die Phase bleibt kompatibel mit `docs/ImpPlan-0.8.0-A.md`,
      `docs/ImpPlan-0.8.0-B.md` und dem 0.8.0-Masterplan.
