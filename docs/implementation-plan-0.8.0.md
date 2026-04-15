# Implementierungsplan: Milestone 0.8.0 - Internationalisierung

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.8.0. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage waehrend der Umsetzung.
>
> Status: Draft (2026-04-15)
> Referenzen: `docs/roadmap.md` Milestone 0.8.0, `docs/design.md` Abschnitt 9
> Internationalisierung, `docs/architecture.md` (`I18nConfig`, ICU4J,
> Unicode-Normalisierung), `docs/cli-spec.md` globale Flags, LF-005, LF-006,
> LF-007, LN-021, LN-023, LN-024.

---

## 1. Ziel

Milestone 0.8.0 fuehrt die technische Grundlage fuer internationale Nutzung von
d-migrate ein. Der Schwerpunkt liegt nicht auf neuen Datenbankfeatures,
sondern auf einem belastbaren Querschnitts-Unterbau:

- lokalisierbare CLI-Meldungen und Fehlertexte
- Unicode-sichere Verarbeitung an relevanten Ein- und Ausgabekanten
- ICU4J-basierte Utilities fuer Grapheme- und Normalisierungsfragen
- konsistente Zeitzonen- und ISO-8601-Regeln in den Datenpfaden
- konsolidiertes CSV-Encoding-/BOM-Verhalten als Teil des i18n-Vertrags

0.8.0 soll damit die Luecke zwischen dem bereits vorhandenen technischen
UTF-8/CSV-Support und einer wirklich internationalisierbaren Produktbasis
schliessen.

Wichtig:

- 0.8.0 ist kein komplettes "alles ist lokalisiert"-Release.
- 0.8.0 fuehrt keine globale Mutation von Datenpayloads per Unicode-
  Normalisierung ein.
- 0.8.0 ersetzt nicht 0.9.0: der offizielle Nutzervertrag fuer `--lang`
  bleibt dort verankert.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- Die CLI besitzt bereits ein globales Flag `--lang`, aber es ist heute nicht
  an eine echte I18n-Runtime oder ResourceBundles angeschlossen.
- Menschliche CLI-Texte sind aktuell hart codiert:
  - `adapters/driving/cli/.../OutputFormatter.kt`
  - `adapters/driving/cli/.../ProgressRenderer.kt`
  - einzelne Commands schreiben Fehler direkt auf `stderr`
- Strukturierte JSON-/YAML-Ausgaben sind bereits stabil und englisch
  schluesselbasiert.
- BOM-Erkennung fuer Importformate existiert seit 0.4.0 bereits ueber
  `EncodingDetector`; CSV-Output kann optional ein UTF-8-BOM schreiben.
- Datums-/Zeitwerte werden im Format-Layer bereits grob ISO-8601-nah
  serialisiert, aber es gibt noch keine zentrale Policy fuer
  Default-Zeitzonen, locale-bezogene Eingaberegeln und durchgaengige
  Konfigurationsableitung.
- ICU4J ist in Architektur und Roadmap vorgesehen, wird im Produktionscode
  aber noch nicht als allgemeine Unicode-Hilfskomponente genutzt.
- Die Konfigurationsspezifikation kennt bereits:
  - `i18n.default_locale`
  - `i18n.default_timezone`
  - `i18n.normalize_unicode`
  aber der produktive CLI-Config-Loader liest heute im Wesentlichen nur den
  `database`-Block.

Zusaetzliche Spannungen, die 0.8.0 explizit aufloesen muss:

- `docs/design.md` spricht an einer Stelle von Deutsch als Default-Bundle,
  `docs/connection-config-spec.md` und `docs/architecture.md` von `en` als
  Default-Locale.
- `docs/cli-spec.md` listet `--lang` schon global, waehrend die Roadmap den
  stabilen Nutzervertrag erst fuer 0.9.0 vorsieht.
- Unicode-Normalisierung ist als Zielbild dokumentiert, darf aber reale
  Nutzdaten nicht still veraendern.

0.8.0 muss diese Punkte bewusst entscheiden, statt sie dokumentarisch
weiterzuschieben.

---

## 3. Scope

### 3.1 In Scope fuer 0.8.0

- ResourceBundle-basierte Lokalisierungsarchitektur fuer mindestens:
  - Englisch
  - Deutsch
- zentraler Locale-/Timezone-/Unicode-Settings-Resolver fuer die CLI-Runtime
- Lokalisierung der menschlichen Plain-Text- und Fehlerausgaben:
  - `OutputFormatter`
  - `ProgressRenderer`
  - command-nahe Fehlermeldungen, soweit sie heute frei formatiert werden
- ICU4J-Integration fuer:
  - Grapheme-Counting
  - Unicode-Normalisierung
  - locale-sensitive Vergleiche/Sortierhilfen, wo fachlich noetig
- definierter Unicode-Normalisierungsvertrag fuer Metadaten und Vergleiche
  ohne stilles Umschreiben von Datenpayloads
- konsolidierte Zeitzonen-Policy:
  - UTC als technischer Default
  - konfigurierbare Default-Zeitzone
  - klare Regeln fuer `TIMESTAMP` vs. `TIMESTAMP WITH TIME ZONE`
- Konsolidierung des bestehenden CSV-Encoding-/BOM-Vertrags im Kontext
  Internationalisierung
- Tests fuer Unicode-Integritaet mit Emoji, CJK, Kyrillisch und kombinierten
  Zeichen

### 3.2 Bewusst nicht Teil von 0.8.0

- vollstaendig lokalisierte Clikt-Hilfe und Parser-Fehlermeldungen
- offizieller, breit dokumentierter Nutzervertrag fuer `--lang`
  als oberste Prioritaetsquelle (bleibt 0.9.0)
- Lokalisierung maschinenlesbarer JSON-/YAML-Schluessel
- locale-abhaengige Veraenderung von Zahlformaten in strukturierten Ausgaben
- automatische Unicode-Normalisierung von Tabelleninhalten beim
  Export/Import/Transfer
- vollstaendige CLDR-basierte Lokalisierung von Waehrungen und
  Datumsdarstellungen in allen Ausgaben
- E.164-Validierung oder weitere datenqualitaetsbezogene internationale Regeln
- neue Dialekte oder neue Datenpfade

Begruendung:

Der Roadmap-Vertrag fuer 0.8.0 verlangt Internationalisierung als belastbaren
Unterbau. Das heisst: Lokalisierbarkeit, Unicode-Sicherheit und klare
Zeit-/Encoding-Regeln. Er verlangt nicht, in demselben Milestone alle
Benutzeroberflaechen, Parser-Hilfen und maschinenlesbaren Formate
sprachabhaengig zu machen.

---

## 4. Leitentscheidungen

### 4.1 Root-Fallback ist Englisch, nicht Deutsch

Verbindliche Entscheidung:

- `messages.properties` ist das Root-/Fallback-Bundle in Englisch
- `messages_de.properties` enthaelt die deutsche Lokalisierung
- weitere Sprachen koennen spaeter ergaenzt werden

Begruendung:

- die existierende CLI und die meisten technischen Texte sind heute englisch
- `i18n.default_locale: en` ist bereits in `connection-config-spec.md` und
  `architecture.md` verankert
- ein englisches Root-Bundle minimiert Fallback-Risiken bei fehlenden Keys

Konsequenz:

- `docs/design.md` Abschnitt 9.1 muss bei Umsetzung auf dieses Modell
  korrigiert werden

### 4.2 0.8.0 lokalisiert nur menschenlesbare Ausgaben, nicht maschinenlesbare Strukturen

Verbindliche Entscheidung:

- Plain-Text-Ausgaben auf stdout/stderr werden lokalisierbar
- JSON-/YAML-Ausgaben behalten englische Feldnamen und stabile Strukturen
- Codes wie `E001`, `W100` und Enum-Werte bleiben unveraendert

Begruendung:

- CI/CD und Automationspfade duerfen nicht von Nutzer-Locale abhaengen
- strukturierte Ausgaben sind API-artige Vertragsflaechen
- LN-023 fordert lokalisierbare Nutzermeldungen, nicht lokalisierte JSON-Keys

### 4.3 `--lang` bleibt in 0.8.0 technisch vorhanden, der stabile Nutzervertrag folgt in 0.9.0

Die CLI besitzt das Flag bereits. Es still no-op zu lassen waere schlecht;
den gesamten Roadmap-Scope von 0.9.0 in 0.8.0 zu ziehen waere aber ebenfalls
unsauber.

Verbindliche Entscheidung:

- 0.8.0 fuehrt die eigentliche Locale-Resolution und Message-Infra ein
- 0.8.0 zieht dabei NICHT den offiziellen CLI-Prioritaetsvertrag fuer
  `--lang` nach vorne
- fuer 0.8.0 kommen als dokumentierter Resolution-Vertrag Config, ENV und
  System-Fallbacks in Betracht; `--lang` bleibt bis 0.9.0 technisch sichtbar,
  aber ausserhalb des final freigegebenen Override-Vertrags
- 0.9.0 liefert dann explizit die Freischaltung von `--lang` als
  dokumentierte Override-Quelle ueber den 0.8.0-ResourceBundles

Damit loesen wir den Code-/Roadmap-Widerspruch, ohne einen sichtbaren
Schein-Flag mitzuschleppen.

### 4.4 Unicode-Normalisierung gilt fuer Vergleiche und Metadaten, nicht fuer Nutzdatenpayloads

Verbindliche Entscheidung:

- Unicode-Normalisierung wird als Utility und Vergleichsregel eingefuehrt
- betroffen sind insbesondere:
  - locale-/unicode-sensitive Vergleiche
  - Schluessel-/Identifier-nahe Metadatenpfade
  - textuelle CLI-/Doku-/Report-Oberflaechen, wo Vergleichsstabilitaet hilft
- NICHT betroffen sind:
  - exportierte/importierte Tabellenwerte
  - freie Datenpayloads in JSON/YAML/CSV

Begruendung:

- LF-005 fordert sichere Verarbeitung und Normalisierungskompetenz
- d-migrate darf dabei aber nicht still Benutzerinhalte semantisch veraendern

### 4.5 Zeitzonen-Handling ist regelbasiert, nicht heuristisch

Verbindliche Entscheidung:

- `TIMESTAMP WITH TIME ZONE` bzw. offsetfaehige Java-Typen werden mit Offset
  serialisiert
- `TIMESTAMP` ohne Zeitzone bleibt ein lokaler Date-Time-Wert ohne erfundene
  Zone
- fuer CLI-Eingaben ohne Offset wird eine konfigurierbare Default-Zeitzone
  nur dort verwendet, wo der Vertrag dies explizit erlaubt
- technischer Default bleibt UTC

Begruendung:

- eine naive `LocalDateTime` darf nicht still als UTC oder lokale JVM-Zeit
  "interpretiert" werden
- Zeitzonenbehandlung muss fuer Export, Import und `--since` konsistent sein

### 4.6 0.8.0 fuehrt keine neue Gradle-Topologie ein

Trotz Architektur-Zielbild "i18n-Modul" wird fuer 0.8.0 kein separates
Top-Level-Gradle-Modul eingefuehrt.

Verbindliche Entscheidung:

- gemeinsame I18n-/Unicode-/Timezone-Typen leben zunaechst in
  `hexagon:application`
- ResourceBundles und CLI-nahe Resolver leben in `adapters:driving:cli`
- format- und import/export-nahe Konvertierungslogik bleibt in
  `adapters:driven:formats`

Begruendung:

- der technische Slice ist querschnittlich, aber noch nicht gross genug fuer
  ein weiteres Modul
- 0.8.0 soll einen funktionierenden Unterbau liefern, keine modulare
  Reorganisation als Selbstzweck

---

## 5. Zielarchitektur fuer 0.8.0

### 5.1 Gemeinsame Runtime-Typen

Geplante neue Basistypen in `hexagon:application`:

- `ResolvedI18nSettings`
  - `locale: Locale`
  - `timezone: ZoneId`
  - `normalization: UnicodeNormalizationMode`
- `UnicodeNormalizationMode`
  - `NFC`
  - `NFD`
  - `NFKC`
  - `NFKD`
- `MessageResolver`
  - `fun text(key: String, vararg args: Any?): String`

Optional zusaetzlich:

- `UserMessage`
- `MessageKey`-Konstantenobjekte pro Bereich

### 5.2 CLI-Adapter fuer ResourceBundles

Geplante Ressourcen in `adapters/driving/cli/src/main/resources/messages/`:

- `messages.properties`
- `messages_de.properties`

Key-Gruppen:

- `cli.validation.*`
- `cli.error.*`
- `cli.progress.*`
- `cli.common.*`

### 5.3 Locale-/Timezone-Resolution

Vorgesehene Aufloesungsreihenfolge fuer 0.8.0:

1. `D_MIGRATE_LANG`
2. `LC_ALL`
3. `LANG`
4. `i18n.default_locale` aus der effektiv aufgeloesten Config-Datei
5. JVM-/System-Locale
6. Fallback `en`

Wichtig fuer Phase A:

- diese Reihenfolge beschreibt die technische I18n-Basis von 0.8.0
- sie ist nicht identisch mit dem spaeteren 0.9.0-Nutzervertrag fuer
  `--lang` als dokumentierte CLI-Override-Quelle

Config-Datei fuer `i18n.*` wird dabei nicht direkt aus `./.d-migrate.yaml`
geraetselt, sondern ueber denselben Pfadvertrag wie bestehende Config-Nutzung
aufgeloest:

1. `--config`
2. `D_MIGRATE_CONFIG`
3. Default `./.d-migrate.yaml`

Zeitzone:

1. spaeterer expliziter CLI-Override (nicht Teil von 0.8.0)
2. `D_MIGRATE_TIMEZONE` falls wir den ENV-Pfad mit aufnehmen
3. `i18n.default_timezone` aus derselben effektiv aufgeloesten Config-Datei
4. JVM/System Default
5. Fallback `UTC`

Unicode-Normalisierung:

1. `i18n.normalize_unicode` aus derselben effektiv aufgeloesten Config-Datei
2. Fallback `NFC`

### 5.4 ICU4J-Utilities

Geplante Hilfskomponenten:

- `UnicodeNormalizer`
- `GraphemeCounter`
- optional `LocaleAwareComparator` fuer spaetere Sortier-/Vergleichspfade

Diese Utilities muessen pure, testbare Helfer bleiben. Kein globaler
Singleton- oder JVM-Locale-State.

---

## 6. Geplante Arbeitspakete

### Phase A - Spezifikationsbereinigung und Scope-Fixierung

1. `docs/cli-spec.md` fuer 0.8.0/0.9.0 sauber trennen:
   - `--lang`-Eintrag annotieren
   - klarstellen, dass JSON/YAML-Schluessel nicht lokalisiert werden
   - festhalten, dass freie strukturierte Fehlermeldungstexte bis auf
     Weiteres englisch und stabil bleiben
2. `docs/design.md` Abschnitt 9.1 auf englisches Root-Bundle korrigieren
3. `docs/connection-config-spec.md` und `docs/architecture.md` mit der
   tatsaechlichen 0.8.0-Resolution abgleichen
4. BOM-/Encoding-Texte aus 0.4.0 uebernehmen statt neu zu erfinden

### Phase B - I18n-Runtime und Config-Resolution

Ziel: Ein durchgaengiger Lauf bekommt saubere `ResolvedI18nSettings`.

Geplante Bausteine:

- `I18nSettingsResolver`
- YAML-Lookup fuer `i18n.*` ueber einen Config-Pfadvertrag analog zum
  bestehenden `NamedConnectionResolver`:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`
- Mapping:
  - String `de`, `de_DE.UTF-8`, `en_US`
  - String `UTC`, `Europe/Berlin`
  - String `NFC`, `NFD`, `NFKC`, `NFKD`
- Erweiterung von `CliContext` um:
  - `locale`
  - `timezone`
  - `normalization`

Wichtige Regeln:

- keine harte Abhaengigkeit auf JVM-Default-Locale in Tests
- parse-Fehler bei ungueltigen Locale-/Timezone-Werten klar attribuieren
- Config-Fehler bleiben Exit 7 / lokale Fehler

### Phase C - ResourceBundles und lokalisierte CLI-Ausgaben

Ziel: Der heutige englische String-Scatter verschwindet hinter Message-Keys.

Betroffene Stellen mindestens:

- `OutputFormatter`
- `ProgressRenderer`
- direkte Command-Fehlertexte mit Nutzerbezug
- ggf. `DataProfileCommand`-stderr-Ausgaben

Verbindlicher Zuschnitt fuer 0.8.0:

- Plain-Text validiert, exportiert, importiert, compare/reverse/profile-
  Begleittexte werden lokalisierbar
- technische Details in Exceptions oder strukturierten Outputs bleiben
  sprachstabil englisch
- bestehende strukturierte JSON-/YAML-Fehlerpfade wie `printError(...)`
  duerfen fuer 0.8.0 keine locale-abhaengigen freien Meldungstexte in ihre
  Payload aufnehmen; wenn dort ein `message`-Feld bleibt, ist es
  sprachstabil und nicht lokalisiert
- vorhandene Fehlercodes und Objektpfade bleiben unveraendert

Beispiel:

- lokalisiert:
  - "Validation failed"
  - "Exporting table"
  - "Config file not found"
- nicht lokalisiert:
  - `E002`
  - `schema.validate`
  - JSON-Feld `exit_code`

### Phase D - Unicode-/ICU4J-Integration

Ziel: Unicode-Unterstuetzung wird fachlich belastbar statt implizit.

Konkrete Schritte:

1. ICU4J als produktive Abhaengigkeit aufnehmen
2. `GraphemeCounter` auf ICU-Basis bauen
3. `UnicodeNormalizer` mit den vier dokumentierten Modi bauen
4. gezielte bestehende `.length`-/Vergleichsstellen ueberpruefen und dort
   umstellen, wo semantische Zeichenlaenge oder Unicode-Vergleich wirklich
   relevant ist

Explizit NICHT:

- blindes Ersetzen jeder `.length`-Nutzung im gesamten Code
- Performance-feindliche ICU-Operation auf allen Hot Paths ohne Bedarf

### Phase E - Zeitzonen- und Format-Policy

Ziel: Ein klarer, dokumentierter Vertrag fuer temporaere Werte.

Geplante Bausteine:

- `TemporalFormatPolicy` oder aequivalente Helper
- Nutzung im Format-Layer (`ValueSerializer`, relevante Parse-/Render-Helfer)
- Konsistenz fuer `DataExportHelpers.parseSinceLiteral`

Verbindliche Regeln fuer 0.8.0:

- ISO 8601 bleibt Standard
- `OffsetDateTime` bleibt offsethaltig
- `LocalDateTime` bleibt ohne Offset
- keine stille Umdeutung lokaler Date-Times in UTC
- Default-Zeitzone darf nur dort greifen, wo ein lokaler Input bewusst in
  einen zonierten Kontext ueberfuehrt wird

### Phase F - CSV-Encoding-/BOM-Konsolidierung

Ziel: Vorhandene 0.4.0-Funktionalitaet wird als Teil des i18n-Vertrags sauber
eingebettet.

Konkret:

- explizit festhalten, dass BOM-/CSV-Unterstuetzung fachlich bereits vor
  0.8.0 existiert und in diesem Milestone nicht neu erfunden, sondern als
  Teil des i18n-Vertrags konsolidiert, getestet und dokumentiert wird
- bestehende Import-BOM-Detection unangetastet weiterverwenden
- CSV-Writer-Verhalten (`--csv-bom`) gegen 0.8.0-Dokumentation spiegeln
- Tests fuer UTF-8/UTF-16 und BOM-Pfade mit nicht-lateinischen Inhalten
- klar dokumentieren:
  - BOM-Detection nur fuer UTF-8/UTF-16
  - keine Heuristik fuer andere Encodings

### Phase G - Tests und Dokumentation

Mindestens noetige Tests:

- Locale-Resolution:
  - CLI > ENV > Config > System > Fallback
- ResourceBundle-Fallback:
  - fehlender Key in `de` -> `messages.properties`
- OutputFormatter/ProgressRenderer:
  - englische und deutsche Snapshots
- Unicode-Integritaet:
  - Emoji
  - kombinierte Zeichen
  - Kyrillisch
  - CJK
- Grapheme-Counting:
  - `a`
  - `ä`
  - `👨‍👩‍👧‍👦`
  - kombinierte Akzente
- Zeitzonen:
  - `TIMESTAMP`
  - `TIMESTAMP WITH TIME ZONE`
  - `--since` mit und ohne Offset
- CSV/BOM:
  - UTF-8 BOM
  - UTF-16 LE/BE Input
  - nicht-lateinische Daten

Nachzuziehende Doku:

- `docs/cli-spec.md`
- `docs/design.md`
- `docs/connection-config-spec.md`
- ggf. `docs/guide.md`

---

## 7. Datei- und Codebasis-Betroffenheit

Voraussichtlich betroffen:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/...`
- `adapters/driving/cli/src/main/resources/messages/...`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/...` fuer Runtime-Typen
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/...`

Neue Tests voraussichtlich in:

- `adapters/driving/cli/src/test/...`
- `hexagon/application/src/test/...`
- `adapters/driven/formats/src/test/...`

---

## 8. Risiken und offene Punkte

### R1 - Lokalisierung und deterministische Tests koennen sich gegenseitig stoeren

Wenn Formatter implizit auf JVM-Locale zugreifen, werden Snapshot- und
Golden-Master-Tests fragil.

Mitigation:

- Locale/Zone immer explizit in Kontext und Formatter injizieren
- Tests nie gegen JVM-Defaults laufen lassen

### R2 - Zu aggressive Unicode-Normalisierung veraendert Nutzdaten

Das waere fachlich inakzeptabel.

Mitigation:

- Normalisierung nur fuer Vergleiche/Metadaten/opt-in Utilities
- keine stillen Datenpayload-Umschreibungen

### R3 - ICU4J kann Hot Paths verlangsamen

LN-024 verbietet signifikante Performance-Einbussen.

Mitigation:

- ICU nur an semantisch noetigen Stellen
- keine blanket ICU-Konvertierung fuer jeden Datenwert im Streaming-Pfad
- gezielte Micro-/Regressionstests fuer relevante Utilities

### R4 - `--lang` bleibt dokumentarisch heikel

Der Flag ist schon vorhanden, Roadmap-Vertrag aber erst in 0.9.0.

Mitigation:

- 0.8.0-Doku explizit annotieren
- 0.9.0 als finalen CLI-Vertrag beibehalten

### Offene Frage O1

Sollen Clikt-eigene Help- und Usage-Texte bereits in 0.8.0 lokalisiert werden,
oder bleibt das bewusst Teil des 0.9.0-Feinschliffs?

Empfehlung:

- in 0.8.0 noch nicht erzwingen
- zuerst die eigene Output-Schicht vollstaendig lokalisierbar machen

### Offene Frage O2

Brauchen wir fuer 0.8.0 bereits einen ENV-Override `D_MIGRATE_TIMEZONE`, oder
reicht Config + System + UTC-Fallback?

Empfehlung:

- optional, aber nicht noetig fuer den Kern-Milestone

---

## 9. Entscheidungsempfehlung

Empfohlen wird fuer 0.8.0 ein bewusst fokussierter Internationalisierungs-
Milestone mit folgendem Vertrag:

- englisches Root-ResourceBundle, deutsch als erste Lokalisierung
- lokalisierte menschenlesbare CLI-Meldungen statt lokalisierter JSON-/YAML-
  Schluessel
- ICU4J fuer Grapheme-Counting und Unicode-Normalisierung als Utility
- explizite, UTC-basierte Zeitzonen-Policy ohne heuristische Uminterpretation
- Wiederverwendung des bestehenden BOM-/Encoding-Unterbaus aus 0.4.0
- saubere Vorbereitung fuer den stabilen `--lang`-Vertrag in 0.9.0

Damit wird 0.8.0 ein echter Plattform-Milestone: keine Kosmetik, sondern die
Basis, damit d-migrate in spaeteren Releases international konsistent,
testbar und ohne Unicode-/Locale-Nebeneffekte weitergebaut werden kann.
