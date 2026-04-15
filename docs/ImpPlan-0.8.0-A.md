# Implementierungsplan: Phase A - Spezifikationsbereinigung und Scope-Fixierung

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: A (Spezifikationsbereinigung und Scope-Fixierung)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 1, Abschnitt 2,
> Abschnitt 3, Abschnitt 4, Abschnitt 5.2 bis 5.4, Abschnitt 6 Phase A,
> Abschnitt 8, Abschnitt 9; `docs/roadmap.md` Milestone 0.8.0 und 0.9.0;
> `docs/design.md` Abschnitt 9; `docs/architecture.md` (`I18nConfig`,
> ICU4J); `docs/cli-spec.md`; `docs/connection-config-spec.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`

---

## 1. Ziel

Phase A zieht den 0.8.0-Vertrag aus dem Masterplan in eine belastbare
Dokumentationsbasis, bevor I18n-Runtime, ResourceBundles, ICU4J-Utilities oder
Zeit-/Format-Policies implementiert werden.

Der Teilplan liefert bewusst noch keine Code-Umsetzung. Ergebnis von Phase A
ist eine konsistente Spezifikation, auf der die spaeteren Phasen B bis G ohne
Milestone-Drift, Locale-Fiktionen oder widerspruechliche Nutzervertraege
aufbauen koennen.

Konkret soll nach Phase A klar und widerspruchsfrei dokumentiert sein:

- dass 0.8.0 die technische I18n-/Unicode-Basis liefert, aber nicht bereits
  den voll ausgepraegten 0.9.0-CLI-Vertrag fuer `--lang`
- dass menschenlesbare Plain-Text-Ausgaben lokalisiert werden duerfen, waehrend
  strukturierte JSON-/YAML-Vertraege stabil und englisch schluesselbasiert
  bleiben
- dass die Locale-/Timezone-/Unicode-Settings dieselbe Config-Prioritaet wie
  die bestehende CLI-Konfiguration respektieren, inklusive `--config` und
  `D_MIGRATE_CONFIG`
- dass Unicode-Normalisierung nicht als stilles Umschreiben von Nutzdaten
  fehlverstanden wird
- dass CSV-/BOM-Themen in 0.8.0 primär als konsolidierter Vertrag auf dem
  vorhandenen 0.4.0-Unterbau behandelt werden und nicht als frei schwebendes
  "vielleicht neues Feature"
- welche aelteren oder widerspruechlichen Doku-Aussagen fuer 0.8.0 explizit
  nicht mehr gelten

---

## 2. Ausgangslage

Aktueller Stand in Dokumentation und Code:

- `docs/roadmap.md` definiert fuer 0.8.0:
  - ResourceBundle-Architektur
  - lokalisierte CLI-Meldungen und Fehlertexte
  - ICU4J-Integration
  - grapheme-aware String-Laengenberechnung
  - Zeitzonen-Handling
  - BOM-Erkennung und -Behandlung bei CSV
- dieselbe Roadmap trennt in 0.9.0 explizit den finalen CLI-Vertrag fuer
  `--lang` als Nutzeroberflaeche ab.
- `docs/cli-spec.md` listet `--lang` heute bereits als globales Flag, ohne die
  Roadmap-Trennung zwischen technischer Vorbereitung und stabilem
  Nutzervertrag sichtbar zu machen.
- `docs/design.md` nennt in Abschnitt 9.1 Deutsch als Default-Bundle, waehrend
  `docs/architecture.md` und `docs/connection-config-spec.md` `en` als
  Default-Locale vorsehen.
- `docs/connection-config-spec.md` enthaelt bereits den kanonischen I18n-Block
  mit:
  - `i18n.default_locale`
  - `i18n.default_timezone`
  - `i18n.normalize_unicode`
- die produktive CLI-Konfiguration liest heute aber im Wesentlichen nur den
  `database`-Block ueber `NamedConnectionResolver`.
- `Main.kt` besitzt bereits:
  - `--lang`
  - `--config`
  - `CliContext`
  verdrahtet `--lang` aber noch nicht an eine echte Message-/Locale-Runtime.
- `OutputFormatter` und `ProgressRenderer` geben heute frei formulierte
  englische Texte aus.
- strukturierte JSON-/YAML-Ausgaben sind heute stabil, enthalten aber in
  Fehlerpfaden freie Meldungstexte, die bei unklarer Umsetzung unbeabsichtigt
  locale-abhaengig werden koennten.
- BOM-Erkennung fuer Input und optionales CSV-BOM fuer Output existieren seit
  0.4.0 bereits produktiv.

Konsequenz:

- die 0.8.0-Dokumentation ist nicht nur unvollstaendig, sondern an mehreren
  Stellen gegeneinander versetzt:
  - Roadmap trennt 0.8.0 und 0.9.0
  - CLI-Spec zeigt `--lang` schon als normales globales Flag
  - Design/Architecture/Config-Spec widersprechen sich beim Default-Bundle
  - der CSV-/BOM-Punkt ist als Roadmap-Liefergegenstand genannt, ohne dass
    klar ist, ob 0.8.0 hier neue Implementierung oder Vertragskonsolidierung
    liefern soll
- ohne Phase A wuerden die spaeteren Code-Phasen gegen einen Mix aus
  vorhandener Vorarbeit, Zielbild und missverstaendlichem Nutzervertrag
  arbeiten.

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Bereinigung der 0.8.0-Dokumentation auf den realen Internationalisierungs-
  Scope
- explizite Trennung zwischen:
  - technischer I18n-Basis in 0.8.0
  - stabilem `--lang`-CLI-Vertrag in 0.9.0
- Klarstellung des Vertrags fuer menschenlesbare vs. strukturierte Ausgaben
- Abgleich der Default-Locale-/Bundle-Aussagen zwischen `design`,
  `architecture` und `connection-config-spec`
- Festschreibung der Config-Prioritaet fuer spaetere I18n-Settings-Resolution:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`
- Klarstellung des Unicode-Normalisierungsvertrags gegenueber Datenpayloads
- Einordnung des CSV-/BOM-Punkts als Wiederverwendung und Konsolidierung des
  bestehenden 0.4.0-Unterbaus
- Benennung der regulaeren Doku-Artefakte, die spaeter in 0.8.0 nachgezogen
  werden muessen

### 3.2 Bewusst nicht Teil von Phase A

- Implementierung von `I18nSettingsResolver`, `MessageResolver` oder
  ResourceBundles im Code
- Aufnahme von ICU4J in Build-Dateien
- Erweiterung von `CliContext`
- Produktivverdrahtung von `--lang`
- Uebersetzung der bestehenden CLI-Ausgaben
- Aenderungen an `OutputFormatter`, `ProgressRenderer` oder Format-Layern
- neue Tests fuer Locale-, Unicode- oder Zeitzonenpfade
- CLDR-/Currency-/E.164-Arbeit

Praezisierung:

Phase A ist fuer 0.8.0 das, was 0.6.0-A und 0.7.5-A bereits fuer ihre
Milestones waren: Dokumentations- und Scope-Haertung vor fachlicher
Implementierung.

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 0.8.0 liefert die I18n-Basis, 0.9.0 den finalen `--lang`-CLI-Vertrag

Phase A fixiert die wichtigste Milestone-Grenze:

- 0.8.0 schafft ResourceBundle-, Locale-, Unicode- und Zeitzonen-Infrastruktur
- 0.8.0 muss nicht bereits den vollstaendigen, breit dokumentierten
  Nutzervertrag fuer `--lang` als Produktoberflaeche abschliessen
- 0.9.0 bleibt der Milestone, in dem `--lang` als finaler CLI-Vertrag
  explizit sichtbar und belastbar wird

Verbindliche Folge:

- `docs/cli-spec.md` darf `--lang` nicht so beschreiben, als sei die gesamte
  0.9.0-Semantik bereits mit 0.8.0 erledigt
- zugleich darf der Flag nicht als stiller No-op beschrieben bleiben
- benoetigt wird ein expliziter Zwischenstand:
  - technisch vorhanden
  - auf 0.8.0-I18n-Basis aufsetzend
  - finaler Milestone-Vertrag in 0.9.0

### 4.2 Strukturierte Ausgaben bleiben schema- und sprachstabil

Phase A fixiert:

- JSON-/YAML-Feldnamen, Command-IDs, Exit-Codes, Warning-/Error-Codes und
  andere API-artige Vertragsflaechen bleiben englisch und stabil
- nur menschenlesbare Plain-Text-Ausgaben werden lokalisiert

Zusatzpraezisierung fuer Fehlerpfade:

- wenn strukturierte Fehlerobjekte weiterhin freie Meldungstexte enthalten,
  muss dokumentiert sein, ob diese Texte:
  - technisch englisch bleiben
  - oder bewusst lokalisiert werden duerfen
- fuer 0.8.0 ist die sichere Default-Entscheidung:
  - strukturierte Fehlertexte bleiben englisch, bis ein expliziter,
    versionierter Alternativvertrag definiert ist

Damit verhindern wir, dass JSON/YAML-Ausgaben durch lokalisierte Error-Strings
implizit zu einer sprachabhaengigen Schnittstelle werden.

### 4.3 I18n-Settings respektieren denselben Config-Pfadvertrag wie bestehende CLI-Konfiguration

Phase A fixiert:

- spaetere I18n-Settings-Resolution darf nicht still nur `./.d-migrate.yaml`
  lesen
- sie muss denselben effektiven Config-Pfad respektieren wie bestehende
  Konfigurationslogik:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`

Verbindliche Folge:

- `docs/implementation-plan-0.8.0.md`, `docs/cli-spec.md`,
  `docs/architecture.md` und `docs/connection-config-spec.md` muessen hier
  denselben Prioritaetsvertrag beschreiben
- eine "eigene kleine I18n-Config-Heuristik" ist fuer 0.8.0 nicht akzeptabel

### 4.4 Root-Bundle-Fallback ist Englisch

Phase A fixiert fuer die spaetere Umsetzung:

- `messages.properties` ist Root-/Fallback-Bundle
- `messages_de.properties` enthaelt Deutsch
- `en` bleibt Default-Locale im Produktvertrag

Verbindliche Folge:

- die widerspruechliche Design-Stelle mit deutschem Default-Bundle wird in den
  regulaeren Docs bereinigt

### 4.5 Unicode-Normalisierung ist Vergleichs-/Metadatenutility, keine Datenmutation

Phase A fixiert:

- Unicode-Normalisierung wird fuer 0.8.0 als Kompetenz und Utility
  dokumentiert
- Nutzdaten in Export-/Import-/Transfer-Payloads werden nicht still
  normalisiert oder umgeschrieben

Verbindliche Folge:

- LF-005/LN-021 werden nicht als Erlaubnis fuer inhaltliche Mutation
  fehlinterpretiert
- die spaeteren Code-Phasen muessen den Unterschied zwischen:
  - Vergleich/Anzeige/Metadaten
  - Nutzdatenpayload
  sauber halten

### 4.6 CSV-/BOM in 0.8.0 ist Vertragskonsolidierung auf vorhandenem Unterbau

Phase A fixiert:

- BOM-Erkennung und CSV-BOM-Support sind nicht erst in 0.8.0 "neu zu
  erfinden"
- 0.8.0 ordnet diese bestehende Funktionalitaet in den Internationalisierungs-
  Vertrag ein und schliesst Doku- und Testluecken

Verbindliche Folge:

- regulaere Docs duerfen den Roadmap-Punkt nicht wie ein voellig neues Feature
  formulieren
- spaetere Code-Phasen muessen klar zwischen:
  - vorhandenem 0.4.0-Unterbau
  - 0.8.0-Konsolidierung, Tests und Policy
  unterscheiden

---

## 5. Arbeitspakete

### A.1 `docs/cli-spec.md`

Bereinigen und annotieren von:

- globalem Flag `--lang`
  - technischer Zwischenstand sichtbar machen
  - finalen Nutzervertrag weiterhin in 0.9.0 verorten
- Klarstellung, dass JSON-/YAML-Schluessel nicht lokalisiert werden
- Klarstellung fuer strukturierte Fehlerausgaben:
  - englische, stabile Meldungstexte bis ein explizit versionierter
    Gegenvertrag definiert ist
- Verweis auf den kanonischen Config-Pfadvertrag statt impliziter
  `.d-migrate.yaml`-Annahme

### A.2 `docs/design.md`

Bereinigung von:

- Abschnitt 9.1 Root-/Fallback-Bundle
- Formulierungen, die Deutsch als Produktdefault nahelegen
- ggf. Formulierungen, die `--lang` wie einen bereits voll abgeschlossenen
  0.8.0-Vertrag aussehen lassen

Ziel:

- `docs/design.md` stuetzt das 0.8.0-Zielbild, statt mit Root-Bundle- und
  Default-Locale-Aussagen dagegenzulaufen

### A.3 `docs/connection-config-spec.md`

Abgleichen von:

- `i18n.default_locale`
- `i18n.default_timezone`
- `i18n.normalize_unicode`
- Prioritaet des effektiven Config-Pfads in Bezug auf:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Defaultdatei

Wichtig:

- die Config-Spec bleibt kanonische Quelle fuer diesen Pfadvertrag
- Phase A baut hier keine zweite, konkurrierende CLI-Config-Erzaehlung auf

### A.4 `docs/architecture.md`

Bereinigung und Schaerfung von:

- Einordnung von `I18nConfig`
- Trennung zwischen vorhandener Produktrealitaet und spaeterer 0.8.0-Umsetzung
- expliziter Aussage, dass strukturierte Ausgaben sprachstabil bleiben
- Einordnung von ICU4J als Utility-Baustein statt blanket Verarbeitung fuer
  alle Datenwerte

### A.5 `docs/implementation-plan-0.8.0.md`

Einpflegen der Phase-A-Entscheidungen aus diesem Teilplan:

- 0.8.0/0.9.0-Grenze bei `--lang`
- Config-Prioritaet ueber den effektiven Config-Pfad
- expliziter Vertrag fuer strukturierte Fehlerausgaben
- CSV-/BOM-Einordnung als Konsolidierung statt "neues Feature aus dem Nichts"

Ziel:

- der Masterplan und der Teilplan laufen nicht auseinander

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `docs/cli-spec.md`
- `docs/design.md`
- `docs/connection-config-spec.md`
- `docs/architecture.md`
- `docs/implementation-plan-0.8.0.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/roadmap.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`

---

## 7. Akzeptanzkriterien

- [ ] `docs/cli-spec.md` unterscheidet sauber zwischen technischer
      0.8.0-I18n-Basis und finalem 0.9.0-`--lang`-Vertrag.
- [ ] `docs/cli-spec.md` beschreibt `--lang` nicht mehr als stillen No-op,
      aber auch nicht als vollstaendig abgeschlossenen 0.8.0-Endvertrag.
- [ ] `docs/cli-spec.md` fixiert, dass JSON-/YAML-Feldnamen und andere
      strukturierte Vertragsflaechen nicht lokalisiert werden.
- [ ] Fuer strukturierte Fehlerausgaben ist dokumentiert, dass freie
      Meldungstexte bis zu einem expliziten Gegenvertrag englisch und stabil
      bleiben.
- [ ] `docs/design.md`, `docs/architecture.md` und
      `docs/connection-config-spec.md` widersprechen sich nicht mehr beim
      Root-/Fallback-Bundle und bei der Default-Locale.
- [ ] Die Doku beschreibt fuer spaetere I18n-Settings denselben effektiven
      Config-Pfadvertrag wie die bestehende CLI-Konfiguration:
      `--config` > `D_MIGRATE_CONFIG` > Defaultdatei.
- [ ] Die Doku macht explizit, dass Unicode-Normalisierung in 0.8.0 nicht als
      stille Nutzdatenmutation zu verstehen ist.
- [ ] Die Doku macht explizit, dass der CSV-/BOM-Punkt in 0.8.0 auf dem
      vorhandenen 0.4.0-Unterbau aufsetzt und diesen konsolidiert.
- [ ] `docs/implementation-plan-0.8.0.md` und dieser Teilplan beschreiben
      denselben Scope ohne neue Widersprueche.

---

## 8. Verifikation

Phase A wird dokumentationsseitig verifiziert, nicht ueber Code- oder
Integrationstests.

Mindestumfang:

1. Querlesen von `docs/cli-spec.md`, `docs/design.md`,
   `docs/connection-config-spec.md`, `docs/architecture.md`,
   `docs/implementation-plan-0.8.0.md` und `docs/roadmap.md`.
2. Abgleich gegen den realen Code-Iststand von:
   - [Main.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)
   - [OutputFormatter.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt)
   - [ProgressRenderer.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt)
   - [NamedConnectionResolver.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt)
3. Spezieller Review auf drei kritische Punkte:
   - 0.8.0/0.9.0-Schnitt fuer `--lang`
   - Config-Prioritaet inkl. `--config`/`D_MIGRATE_CONFIG`
   - Sprachstabilitaet strukturierter Fehlerausgaben

---

## 9. Risiken

### R1 - Milestone-Drift bei `--lang`

Wenn 0.8.0 dokumentarisch bereits den kompletten `--lang`-Vertrag zieht,
entwertet das 0.9.0 und verwischt den Release-Schnitt.

### R2 - Eigene I18n-Config-Heuristik neben bestehender CLI-Konfiguration

Wenn I18n-Einstellungen nicht denselben effektiven Config-Pfad respektieren wie
der Rest der CLI, entstehen schwer erklaerbare Inkonsistenzen zwischen
Verbindungen und Lokalisierung.

### R3 - Lokalisierte Error-Texte leaken in strukturierte Formate

Wenn freie Fehlermeldungstexte in JSON/YAML unklar behandelt werden, werden
maschinenlesbare Vertraege implizit sprachabhaengig.

### R4 - CSV-/BOM-Punkt wird als neues Feature statt als Vertragskonsolidierung missverstanden

Das fuehrt zu aufgeblasenem Scope und zu unklarer Abgrenzung gegenueber 0.4.0.

---

## 10. Abschluss-Checkliste

- [ ] Die 0.8.0-Doku beschreibt denselben Scope in Roadmap, Masterplan und
      Teilplan.
- [ ] Der Unterschied zwischen 0.8.0-I18n-Basis und 0.9.0-`--lang`-Vertrag
      ist explizit sichtbar.
- [ ] Strukturierte Ausgaben sind als sprachstabile Vertragsflaechen
      dokumentiert.
- [ ] Der effektive Config-Pfad fuer spaetere I18n-Settings ist explizit an
      die bestehende CLI-Konfiguration angekoppelt.
- [ ] Unicode-Normalisierung ist als Utility, nicht als Datenmutation,
      dokumentiert.
- [ ] CSV-/BOM-Themen sind als 0.8.0-Konsolidierung auf vorhandener
      Funktionalitaet eingeordnet.
