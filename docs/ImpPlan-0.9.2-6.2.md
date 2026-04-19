# Implementierungsplan: 0.9.2 - Arbeitspaket 6.2 `schema generate` um den Split-Vertrag erweitern

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.2 (`schema generate` um den Split-Vertrag
> erweitern)
> **Status**: Draft (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 4.2,
> Abschnitt 4.4 bis 4.6, Abschnitt 5.2, Abschnitt 6.2, Abschnitt 7,
> Abschnitt 8 und Abschnitt 9.2;
> `docs/cli-spec.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`.

---

## 1. Ziel

Arbeitspaket 6.2 zieht den sichtbaren Nutzervertrag fuer den DDL-Split in
`schema generate` ein:

- der Aufruf kennt explizit `--split single|pre-post`
- der Request-/Runner-Schnitt transportiert den Modus eindeutig
- unzulaessige Split-Kombinationen werden frueh und testbar mit Exit 2
  abgewiesen
- Help-Text, Fehlermeldungen und CLI-Spezifikation sprechen denselben
  Vertrag

6.2 liefert bewusst noch nicht die fachliche Objektzuordnung oder die
vollstaendige Split-Ausgabe. Es fixiert den sichtbaren Kommandovertrag,
auf dem 6.3 und 6.4 danach aufsetzen.

Nach 6.2 soll klar gelten:

- `schema generate` hat einen expliziten Split-Modus statt impliziter
  Sonderfaelle
- `single` bleibt der unveraenderte Default und ein explizites No-Op
- `pre-post` ist nur dort erlaubt, wo das Ergebnis adressierbar und
  semantisch unterstuetzt ist
- Nutzer sehen fuer ungueltige Kombinationen eine eindeutige Exit-2-
  Antwort statt spaeter Dateisystem- oder Renderer-Nebenwirkungen

---

## 2. Ausgangslage

Der aktuelle CLI-/Runner-Schnitt ist noch auf einen einzigen DDL-Strom
ausgelegt:

- `SchemaGenerateRequest` kennt heute noch keinen `splitMode`
- `SchemaGenerateCommand` bietet kein `--split`
- `SchemaGenerateRunner` erzeugt genau ein `DdlResult` und rendert genau
  einen zusammenhaengenden `ddl`-String
- Textausgabe kennt heute nur:
  - `stdout`
  - eine einzelne `--output`-Datei
  - optional eine einzelne Rollback-Datei
- JSON-Ausgabe kennt heute nur ein einzelnes Feld `ddl`
- Help-Text und `docs/cli-spec.md` beschreiben den Split-Vertrag noch
  nicht

Die bestehende Verzweigung ist deshalb fuer 0.9.2 zu schmal:

- ohne expliziten Request-Modus kann der Runner keinen sauberen
  Vorab-Vertrag fuer `single` vs. `pre-post` validieren
- ohne CLI-Validierung waeren ungueltige Kombinationen wie
  `--split pre-post` ohne adressierbaren Output erst spaet sichtbar
- ohne normierte Fehlermeldungen und Hilfetexte drohen Unterschiede
  zwischen Command, Runner und Spezifikationsdoku

Gleichzeitig darf 6.2 den Bestandspfad nicht destabilisieren:

- bestehende Aufrufe ohne `--split` muessen unveraendert weiterlaufen
- `--generate-rollback` bleibt im Single-Fall wie heute benutzbar
- bestehende JSON- und Datei-Workflows duerfen erst im expliziten
  Split-Fall ihren Vertrag aendern

---

## 3. Scope fuer 6.2

### 3.1 In Scope

- `SchemaGenerateRequest` um einen expliziten Split-Modus erweitern
- `SchemaGenerateCommand` um `--split single|pre-post` erweitern
- Runner-Preflight fuer ungueltige Moduskombinationen einfuehren
- Exit-2-Fehlerpfade fuer:
  - `pre-post` ohne `--output` und ohne `--output-format json`
  - `pre-post` zusammen mit `--generate-rollback`
- Help-Text, Fehlermeldungen und `docs/cli-spec.md` auf denselben
  Vertrag ziehen
- gezielte CLI-/Runner-Tests fuer Default, Validierung und sichtbare
  Fehlermeldungen

### 3.2 Bewusst nicht Teil von 6.2

- die fachliche DDL-Zuordnung nach `PRE_DATA` und `POST_DATA`
- Split-Dateischreiben und Split-JSON-Serialisierung selbst
- Report-Serialisierung fuer `split_mode` und `phase`
- Rollback-Split
- weitere Split-Varianten jenseits von `single|pre-post`

Praezisierung:

6.2 loest "welcher sichtbare Aufrufvertrag gilt fuer den Split?", nicht
"wie werden die Artefakte intern fachlich zusammengesetzt?".

---

## 4. Leitentscheidungen

### 4.1 Der sichtbare Vertrag lautet exakt `single|pre-post`

Verbindliche Folge:

- `schema generate` akzeptiert:
  - `--split single`
  - `--split pre-post`
- Default ist `single`
- `--split single` ist einem fehlenden `--split` semantisch vollstaendig
  gleichgestellt
- weitere Varianten bleiben intern offen, sind in 0.9.2 aber nicht Teil
  des oeffentlichen Vertrags

### 4.2 Der Split-Modus wird frueh normalisiert

Verbindliche Folge:

- `SchemaGenerateRequest` transportiert den Split-Modus explizit statt
  ihn spaeter aus einzelnen CLI-Flags abzuleiten
- `SchemaGenerateRequest.splitMode` bekommt einen Modell-Default fuer
  `single`; Direkt-Instanziierungen ohne expliziten Wert sind damit
  semantisch identisch zu `--split single`
- 6.2 fuehrt bewusst keinen `null`-, `unknown`- oder sonstigen
  Zwischenzustand fuer den Split-Modus ein
- Command und Runner arbeiten damit gegen denselben bereits
  normalisierten Vertrag
- die konkrete interne Darstellung kann Enum oder aequivalente
  normierte Modellform sein; nach aussen bleibt der sichtbare Vertrag
  `single|pre-post`

### 4.3 `pre-post` braucht einen adressierbaren Ausgabeweg

Verbindliche Folge:

- Text-SQL im Split-Fall ist nur zusammen mit `--output` erlaubt
- `--split pre-post` ohne `--output` endet mit Exit 2, sofern nicht
  `--output-format json` aktiv ist
- JSON bleibt die einzige Split-Variante ohne `--output`, weil sie ein
  einzelnes strukturiertes Antwortartefakt liefert
- fuer 6.2 bedeutet das zunaechst nur:
  - `pre-post + json` ist als Aufrufkombination gueltig
  - der Runner traegt `splitMode = pre-post` ohne Rueckfall auf
    `single` weiter
  - die semantische Split-JSON-Nutzlast selbst wird erst in 6.4
    fertiggestellt
- 6.2 definiert daher bewusst keinen stillen Fallback auf das alte
  Single-JSON als abgeschlossenen `pre-post`-Vertrag

Begruendung:

- zwei SQL-Artefakte brauchen einen stabilen Basispfad
- `stdout` ist fuer den Split-Textpfad kein adressierbarer Zweifach-
  Outputvertrag

### 4.4 Rollback bleibt im Split-Fall gesperrt

Verbindliche Folge:

- `--split pre-post --generate-rollback` endet in 0.9.2 immer mit Exit 2
- der Fehler wird als bewusste Vertragsgrenze und nicht als technische
  Laune kommuniziert

### 4.5 `single` bleibt byte-nah rueckwaertskompatibel

Verbindliche Folge:

- Aufrufe ohne `--split` oder mit `--split single` folgen denselben
  Pfaden wie heute
- bestehende Hilfe- und Testfaelle fuer den Single-Pfad bleiben gueltig
- 6.2 darf keine Split-spezifischen Nebenwirkungen in bestehenden
  Single-Aufrufen einfuehren

### 4.6 Fehlertexte und Spezifikation sind Teil des Vertrags

Verbindliche Folge:

- Exit 2 allein reicht nicht; die Fehlermeldung muss die ungueltige
  Kombination konkret benennen
- fuer 6.2 werden die Runner-Fehlertexte als Contractsprache festgezogen:
  - ``--split pre-post` requires `--output` unless `--output-format json` is used.`
  - ``--split pre-post` cannot be combined with `--generate-rollback`.`
- Tests pruefen diese Runner-Meldungen direkt oder gegen exakt dieselben
  String-Pattern; nicht gegen zufaellige Framework-Paraphrasen
- Help-Text, Command-Doku und `docs/cli-spec.md` muessen denselben
  Wortlaut fuer:
  - `single`
  - `pre-post`
  - Split-ohne-Output
  - Split-mit-Rollback
  tragen

---

## 5. Konkrete Arbeitsschritte

### 5.1 Request- und CLI-Schnitt erweitern

- `SchemaGenerateRequest` um `splitMode` erweitern
- `splitMode` im Datenmodell selbst auf `single` defaulten
- `SchemaGenerateCommand` um `--split single|pre-post` erweitern
- Default fuer den fehlenden Parameter auf `single` setzen
- `--split single` explizit als normalen Request-Wert weiterreichen
- Direkt-Instanziierungen ausserhalb der CLI duerfen sich auf denselben
  Modell-Default verlassen; 6.2 braucht keinen separaten
  Nachnormalisierungspfad fuer "nicht gesetzt"

### 5.2 Runner-Preflight fuer den Split einfuehren

- Split-Validierung vor Ausgabezweigen und vor Dateischreiblogik
  ausfuehren
- folgende Faelle mit Exit 2 abweisen:
  - `splitMode = pre-post` und kein `output`, solange
    `outputFormat != json`
  - `splitMode = pre-post` und `generateRollback = true`

Wichtig:

- diese Validierung ist rein vertragsbezogen und unabhaengig von 6.3
  oder 6.4
- der Runner soll ungueltige Split-Kombinationen vor jedem Versuch des
  Dateischreibens oder der Rollback-Erzeugung stoppen
- fuer `splitMode = pre-post` plus `outputFormat = json` validiert 6.2
  nur die Zulaessigkeit der Kombination und die Weitergabe des Modus;
  die JSON-Nutzlastform wird nicht in 6.2 abgeschlossen

### 5.3 Fehlermeldungen und Help-Text angleichen

- Help in `SchemaGenerateCommand` um den Split-Modus ergaenzen
- Fehlertexte fuer die Exit-2-Pfade auf konkrete Runner-Strings
  festziehen:
  - ``--split pre-post` requires `--output` unless `--output-format json` is used.`
  - ``--split pre-post` cannot be combined with `--generate-rollback`.`
- Root-/CLI-Kontext um die neue Nutzungsform nicht widerspruechlich zu
  `--output-format json` dokumentieren

Ziel:

- Nutzer koennen aus Hilfe und Fehlermeldung direkt erkennen, welche
  Split-Kombinationen erlaubt oder verboten sind

### 5.4 CLI-Spezifikation synchronisieren

- `docs/cli-spec.md` um `--split single|pre-post` erweitern
- dort dieselben Regeln festhalten wie im Runner:
  - Default `single`
  - `pre-post` braucht bei Textausgabe `--output`
  - `pre-post` plus `--generate-rollback` ist ungueltig
- JSON-Sonderfall explizit notieren:
  - `pre-post` ohne `--output` ist nur mit `--output-format json`
    zulaessig
  - 6.2 validiert diese Kombination bereits, aber die semantische
    Split-JSON-Struktur (`split_mode`, `ddl_parts`, `phase`) wird erst
    in 6.4 abgeschlossen

### 5.5 Tests ergaenzen

Mindestens abzudecken:

- fehlendes `--split` ergibt denselben Request-/Runner-Modus wie
  `--split single`
- Direkt-Instanziierung von `SchemaGenerateRequest()` ohne expliziten
  `splitMode` ergibt denselben Modus wie `single`
- `--split single` bleibt gegenueber heutigen Single-Faellen
  funktional unveraendert
- `--split pre-post` ohne `--output` und ohne JSON endet mit Exit 2
- `--split pre-post --output-format json` ist ohne `--output`
  zulaessig
- `--split pre-post --output-format json` wird in 6.2 nur auf
  Zulaessigkeit und Modus-Weitergabe geprueft, nicht auf die finale
  6.4-JSON-Nutzlast
- `--split pre-post --generate-rollback` endet mit Exit 2
- Fehlermeldungen verwenden die in 5.3 festgezogenen Contracttexte
- Help-/CLI-Parsing akzeptiert genau `single|pre-post`

---

## 6. Verifikation

Pflichtfaelle fuer 6.2:

- `schema generate` ohne `--split` bleibt fuer bestehende Single-
  Tests unveraendert
- `schema generate --split single` ist verhaltensgleich zu
  `schema generate` ohne `--split`
- `schema generate --split pre-post --output out/schema.sql` passiert
  den 6.2-Preflight und bleibt fuer 6.4 als gueltiger Vertrag offen
- `schema generate --split pre-post --output-format json` passiert den
  6.2-Preflight auch ohne `--output`
- fuer diesen JSON-Fall prueft 6.2 die gueltige Kombination und die
  unveraenderte Weitergabe von `splitMode = pre-post`, nicht schon die
  finale 6.4-Ausgabeform
- `schema generate --split pre-post` ohne `--output` und ohne JSON endet
  mit Exit 2
- `schema generate --split pre-post --generate-rollback` endet mit
  Exit 2
- die Exit-2-Meldungen sind ueber die in 5.3 definierten Contracttexte
  stabil und testbar
- `docs/cli-spec.md`, Help-Text und Runner-Verhalten sprechen denselben
  Vertrag

Erwuenschte Zusatzfaelle:

- Command-Parsing-Test fuer ungueltige Werte ausserhalb von
  `single|pre-post`
- explizite Help-Snapshots fuer den neuen `--split`-Parameter
- Contract-Smoke fuer `SchemaGenerateRequest(splitMode=...)`

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `docs/cli-spec.md`
- CLI-/Runner-Tests unter:
  - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
  - `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/...`

Wahrscheinlich indirekt mit betroffen:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
  wegen Help-/Outputformat-Kontext
- Integrations- oder Exporttests, falls sie den bisherigen
  `SchemaGenerateRequest`-Konstruktor direkt verwenden
- spaetere 6.4-Ausgabepfade, die auf dem neuen `splitMode` aufbauen

---

## 8. Risiken und Abgrenzung

### 8.1 Split-Validierung kann zu spaet im Runner sitzen

Risiko:

- wenn der Runner ungueltige Kombinationen erst nach Dateilogik oder
  Rollback-Erzeugung erkennt, werden Exit-2-Pfade unnnoetig fragil

Mitigation:

- Split-Preflight vor Datei-, JSON- und Rollback-Zweige ziehen
- ungueltige Kombinationen ohne weitere Nebenwirkungen abbrechen

### 8.2 Help-Text und Spezifikation koennen auseinanderlaufen

Risiko:

- Nutzer sehen in Help, Tests und `docs/cli-spec.md` leicht
  unterschiedliche Aussagen zu denselben Flags

Mitigation:

- den 6.2-Vertrag einmalig klar formulieren und in allen drei Ebenen
  wortgleich nachziehen
- die sichtbaren Exit-2-Faelle testseitig absichern

### 8.3 `single` koennte unbeabsichtigt umgedeutet werden

Risiko:

- bei unvorsichtiger Implementierung wird `--split single` zu einem
  Sonderpfad mit eigener Ausgabe- oder Validierungslogik

Mitigation:

- `single` als explizites No-Op behandeln
- bestaetigende Regressionstests fuer fehlendes `--split` und
  `--split single` parallel fuehren

### 8.4 6.2 koennte 6.4-Ausgabeentscheidungen vorwegnehmen

Risiko:

- wenn 6.2 bereits zu viele Datei- oder JSON-Details fest in den Runner
  codiert, verengt das unnoetig den Spielraum fuer 6.4

Mitigation:

- in 6.2 nur den sichtbaren Aufrufvertrag und die Preflight-Regeln
  festziehen
- konkrete Split-Dateien, `ddl_parts` und Report-Inhalte in 6.4
  umsetzen, aber gegen den hier fixierten Vertrag pruefen

### 8.5 `pre-post + json` koennte versehentlich als fertige 6.2-Ausgabe missverstanden werden

Risiko:

- weil `pre-post` mit `--output-format json` bereits in 6.2 als
  gueltige Kombination akzeptiert wird, koennte ein Zwischenstand
  versehentlich als bereits semantisch fertige Split-JSON-Implementierung
  gelesen werden

Mitigation:

- 6.2 fixiert fuer diesen Fall nur:
  - Zulaessigkeit der Kombination
  - Weitergabe von `splitMode = pre-post`
- die eigentliche Split-JSON-Nutzlast bleibt ausdruecklich 6.4
- ein stiller Rueckfall auf "legacy `ddl` als vollwertige `pre-post`-
  Antwort" ist nicht der abgeschlossene Zielvertrag
