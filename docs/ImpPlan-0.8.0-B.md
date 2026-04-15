# Implementierungsplan: Phase B - I18n-Runtime und Config-Resolution

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: B (I18n-Runtime und Config-Resolution)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.1 bis 4.6, Abschnitt 5.1 bis 5.4,
> Abschnitt 6 Phase B, Abschnitt 8, Abschnitt 9; `docs/ImpPlan-0.8.0-A.md`;
> `docs/roadmap.md` Milestone 0.8.0 und 0.9.0; `docs/design.md` Abschnitt 9;
> `docs/architecture.md` (`I18nConfig`); `docs/connection-config-spec.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`

---

## 1. Ziel

Phase B schafft den adapter- und runtime-seitigen Unterbau, damit ein
durchgaengiger CLI-Lauf explizite Internationalisierungs-Einstellungen kennt,
statt implizit auf JVM-Defaults, verstreute `System.getenv(...)`-Aufrufe oder
hart codierte Locale-/Timezone-Annahmen zu vertrauen.

Der Teilplan liefert bewusst noch keine lokalisierte Message-Ausgabe und keine
ResourceBundles. Er schafft die Runtime- und Config-Grundlage, auf der die
spaeteren Phasen C bis G aufsetzen.

Nach Phase B soll im CLI-/Application-Unterbau klar und testbar gelten:

- es gibt einen expliziten Runtime-Typ fuer aufgeloeste I18n-Einstellungen
- Locale, Zeitzone und Unicode-Normalisierungsmodus werden nicht mehr ad hoc
  aus JVM-Defaults oder Dokumentationsannahmen abgeleitet
- der Config-Pfadvertrag fuer `i18n.*` ist identisch zur bestehenden
  CLI-Konfiguration:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`
- `D_MIGRATE_LANG`, `LC_ALL`, `LANG` und spaeter konfigurierbare
  Default-Werte sind sauber in einer Resolver-Reihenfolge modelliert
- `--lang` bleibt zwar im Root-Command vorhanden, ist aber fuer 0.8.0 noch
  **nicht** Teil des freigegebenen Resolution-Vertrags
- parse- und Config-Fehler fuer I18n-Settings sind klar attribuierbar und
  laufen in denselben lokalen Fehlerpfaden wie andere Config-Probleme
- Tests koennen Locale-/Timezone-Verhalten deterministisch fahren, ohne von
  JVM-Defaults abzuhaengen

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- `Main.kt` besitzt bereits globale CLI-Flags:
  - `--config`
  - `--lang`
  - `--output-format`
  - `--quiet`, `--verbose`, `--no-color`, `--no-progress`
- `CliContext` transportiert heute aber nur:
  - `outputFormat`
  - `verbose`
  - `quiet`
  - `noColor`
  - `noProgress`
- `--lang` wird aktuell weder validiert noch an eine Locale-Runtime
  weitergereicht.
- `NamedConnectionResolver` besitzt bereits einen belastbaren Config-Pfad-
  Vertrag:
  - CLI-Override `--config`
  - ENV `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`
- dieselbe Konfiguration wird heute aber im Produktivpfad im Wesentlichen nur
  fuer `database.*` gelesen.
- `connection-config-spec.md` beschreibt bereits:
  - `i18n.default_locale`
  - `i18n.default_timezone`
  - `i18n.normalize_unicode`
  ohne dass dafuer bereits eine produktive Runtime-Resolution existiert.
- `OutputFormatter` und `ProgressRenderer` sind heute noch rein englisch und
  haben keinen Zugriff auf eine Locale-/Timezone-Runtime.
- einzelne Formatter und Helfer nutzen bereits explizite, stabile Locales:
  - `ProgressRenderer` formatiert Zahlen via `Locale.US`
  - `DataExportHelpers.formatProgressSummary(...)` nutzt aktuell noch
    locale-abhaengiges `"%.2f".format(...)`

Konsequenz:

- ohne Phase B wuerde Phase C zwar Message-Keys und ResourceBundles bauen,
  haette aber keinen belastbaren Laufzeitkontext, in dem diese Bundles sauber
  aufgeloest werden koennen.
- besonders kritisch ist die Config-Seite:
  wenn `i18n.*` nicht denselben effektiven Config-Pfad respektiert wie
  `database.*`, entstehen schwer erklaerbare Unterschiede zwischen
  Verbindungsauflösung und Lokalisierung.
- ebenso kritisch ist die Milestone-Grenze:
  `--lang` steht bereits im Root-Command, darf aber laut Phase A den finalen
  0.9.0-CLI-Vertrag nicht vorwegnehmen.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Einfuehrung eines expliziten Runtime-Typs fuer I18n-Einstellungen
- Einfuehrung eines `I18nSettingsResolver` oder gleichwertigen Services
- Aufloesung von:
  - Locale
  - Zeitzone
  - Unicode-Normalisierungsmodus
- Config-Lookup fuer `i18n.*` ueber denselben effektiven Config-Pfad wie
  bestehende CLI-Konfiguration
- Mapping und Validierung fuer:
  - Locale-Strings (`de`, `de_DE.UTF-8`, `en_US`, `C`, `C.UTF-8`, `POSIX`)
  - ZoneId-Strings (`UTC`, `Europe/Berlin`)
  - Unicode-Normalisierungsmodi (`NFC`, `NFD`, `NFKC`, `NFKD`)
- Erweiterung von `CliContext` um die aufgeloesten I18n-Einstellungen
- Testbare, injizierbare Resolution ohne harte JVM-/ENV-Abhaengigkeit
- klare Fehlersemantik fuer:
  - ungueltige Locale
  - ungueltige Zeitzone
  - ungueltigen Normalisierungsmodus
  - Parse-/Konfigurationsfehler

### 3.2 Bewusst nicht Teil von Phase B

- ResourceBundles oder Message-Keys
- Lokalisierung von `OutputFormatter`, `ProgressRenderer` oder Command-Texten
- ICU4J-Utilities wie `GraphemeCounter` oder `UnicodeNormalizer`
- Produktivverdrahtung von `--lang` als aktive Override-Quelle
- neuer CLI-Flag fuer Zeitzonensteuerung
- CLDR-/Currency-/Date-Formatting im User-Output
- Aenderungen an JSON-/YAML-Ausgabeformaten

Praezisierung:

Phase B loest "woher kommen I18n-Einstellungen und wie werden sie stabil
aufgeloest?", nicht "wie werden Texte spaeter lokalisiert?".

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 Phase B liefert einen expliziten Runtime-Typ statt verstreuter Primitive

Phase B fuehrt keinen losen Dreiklang aus `Locale`, `ZoneId` und rohem String
in verschiedenen Layern ein.

Verbindliche Folge:

- ein Lauf kennt ein explizites `ResolvedI18nSettings`
- dieser Typ transportiert mindestens:
  - `locale`
  - `timezone`
  - `normalization`
- Commands, Formatter und spaetere Resolver bekommen diesen Typ statt
  ad-hoc-Zugriff auf ENV oder Config

### 4.2 Config-Pfadvertrag wird nicht neu erfunden

Phase B fixiert:

- `i18n.*` nutzt denselben effektiven Config-Pfad wie die bestehende CLI
- die Aufloesung folgt:
  - `--config`
  - `D_MIGRATE_CONFIG`
  - Default `./.d-migrate.yaml`

Verbindliche Folge:

- Phase B baut **keinen** zweiten, konkurrierenden Config-Loader nur fuer I18n
- entweder wird `NamedConnectionResolver`-Logik in einen kleineren
  wiederverwendbaren Pfadresolver extrahiert
- oder es gibt einen gleichwertigen, explizit gemeinsamen Resolver fuer den
  effektiven Config-Pfad

Nicht akzeptabel ist:

- I18n-Config direkt aus `./.d-migrate.yaml` zu lesen und `--config` zu
  ignorieren
- ENV/CLI-Prioritaet fuer I18n anders zu definieren als fuer bestehende
  Konfiguration

### 4.3 0.8.0 respektiert die 0.9.0-Grenze fuer `--lang`

Phase B fixiert die wichtigste Runtime-Grenze:

- `--lang` bleibt im Root-Command sichtbar, weil er im Code bereits existiert
- Phase B zieht diesen Flag aber **nicht** in den produktiven
  Locale-Resolution-Vertrag fuer 0.8.0
- produktive Quellen fuer 0.8.0 sind:
  - `D_MIGRATE_LANG`
  - `LC_ALL`
  - `LANG`
  - `i18n.default_locale`
  - JVM/System-Fallback
- die in realen UNIX-/CI-Umgebungen haeufigen Locale-Werte `C`, `C.UTF-8`
  und `POSIX` gelten dabei nicht als Fehler, sondern als gueltige
  Root-/English-Aliaswerte

Verbindliche Folge:

- Phase B implementiert die Runtime so, dass `--lang` spaeter in 0.9.0 additiv
  als oberste Override-Quelle eingeschoben werden kann
- Phase B selbst darf aber nicht schon Dokumentation und Runtime so bauen, als
  sei dieser 0.9.0-Schritt bereits abgeschlossen

### 4.4 Parse-Fehler werden als lokale Konfigurationsfehler behandelt

Phase B fuehrt keine neue Exit-Code-Klasse fuer I18n ein.

Verbindliche Folge:

- ungueltige Locale-/Zone-/Normalization-Werte aus Config laufen als lokale
  Fehler
- ENV-Werte mit ungueltigen I18n-Settings fuehren ebenfalls zu klar
  attribuierten lokalen Fehlern
- der Fehlerpfad bleibt kompatibel mit Exit `7`

### 4.5 Normalisierungsmodus ist Konfigurationsbestandteil, nicht sofortige Datenmutation

Phase B fixiert:

- `normalize_unicode` wird als aufgeloester Modus transportiert
- Phase B selbst fuehrt noch **keine** blanket Anwendung auf alle
  Datenpfade ein

Verbindliche Folge:

- spaetere Phasen koennen gezielt entscheiden, wo dieser Modus fachlich
  relevant ist
- es entsteht kein impliziter Seiteneffekt im Runtime-Unterbau

### 4.6 Tests muessen JVM- und ENV-Defaults komplett kontrollieren koennen

Phase B fixiert:

- Resolver duerfen nicht schwer testbar direkt auf globale JVM-/ENV-Werte
  zugreifen
- alle externen Quellen muessen als injizierbare Provider modellierbar sein

Verbindliche Folge:

- Resolver-Tests koennen konkrete Prioritaetsketten ohne Prozess-Manipulation
  abdecken
- Build-Stabilitaet bleibt unabhaengig von Host-Locale und Host-Timezone

---

## 5. Arbeitspakete

### B.1 Runtime-Typen in `hexagon:application`

Einzufuehren sind mindestens:

- `ResolvedI18nSettings`
- `UnicodeNormalizationMode`

Empfohlener Mindestvertrag:

- `ResolvedI18nSettings.locale: Locale`
- `ResolvedI18nSettings.timezone: ZoneId`
- `ResolvedI18nSettings.normalization: UnicodeNormalizationMode`

Ziel:

- spaetere Formatter und Message-Resolver arbeiten gegen einen expliziten
  Laufzeitkontext

### B.2 Effektiven Config-Pfad aufloesen

Phase B braucht einen wiederverwendbaren Vertrag fuer den effektiven
Config-Pfad.

Moegliche Umsetzung:

- kleine Extraktion aus `NamedConnectionResolver`
- oder ein neuer gemeinsamer Helfer wie `EffectiveConfigPathResolver`

Mindestens abzudecken:

- CLI-Pfad `--config`
- ENV `D_MIGRATE_CONFIG`
- Default `./.d-migrate.yaml`
- Fehlermeldungen fuer nicht vorhandene explizite Config-Dateien

Ziel:

- `database.*` und `i18n.*` teilen sich denselben Pfadvertrag

### B.3 `i18n.*` aus YAML lesen

Phase B fuehrt den produktiven Lookup fuer:

- `i18n.default_locale`
- `i18n.default_timezone`
- `i18n.normalize_unicode`

Wichtige Regeln:

- top-level YAML muss Mapping sein
- `i18n` muss, falls vorhanden, ein Mapping sein
- alle drei Werte sind optional
- falsche Typen muessen klare Fehler liefern

Bewusst nicht Ziel:

- Vollmodell der gesamten Config-Datei
- Ersetzung des bestehenden `NamedConnectionResolver`

### B.4 Locale-/Timezone-/Normalization-Parsing

Phase B modelliert und testet die Mapping-Regeln:

- Locale:
  - `de`
  - `de_DE`
  - `de_DE.UTF-8`
  - `en_US`
- Zeitzone:
  - `UTC`
  - `Europe/Berlin`
- Normalisierung:
  - `NFC`
  - `NFD`
  - `NFKC`
  - `NFKD`

Verbindliche Regeln:

- Charset-Suffixe wie `.UTF-8` werden fuer Locale-Resolution abgestrippt
- die Sonderwerte `C`, `C.UTF-8` und `POSIX` werden explizit als gueltige
  Aliaswerte auf den Root-/English-Pfad gemappt statt als Parse-Fehler
- ungueltige Locale-/Zone-Strings sind Fehler, keine stillen Fallbacks
- Normalisierungswerte sind case-insensitive nur dann, wenn das explizit
  dokumentiert und getestet wird; sonst upper-case-only

### B.5 `I18nSettingsResolver`

Der Resolver fuehrt die gesamte Prioritaetskette zusammen.

Produktive Locale-Reihenfolge fuer 0.8.0:

1. `D_MIGRATE_LANG`
2. `LC_ALL`
3. `LANG`
4. `i18n.default_locale` aus der effektiv aufgeloesten Config-Datei
5. JVM-/System-Locale
6. Fallback `en`

Explizite Locale-Regeln fuer reale ENV-Werte:

- `C`
- `C.UTF-8`
- `POSIX`

werden als gueltige Aliaswerte fuer den Root-/English-Pfad behandelt.

Produktive Zeitzonen-Reihenfolge fuer 0.8.0:

1. `i18n.default_timezone`
2. JVM/System-Zeitzone
3. Fallback `UTC`

Produktive Normalisierungs-Reihenfolge fuer 0.8.0:

1. `i18n.normalize_unicode`
2. Fallback `NFC`

Wichtig:

- `--lang` ist in Phase B **nicht** Teil der aktiven Resolution-Kette
- `D_MIGRATE_TIMEZONE` ist fuer 0.8.0 bewusst **nicht** Teil des aktiven
  Resolution-Vertrags
- der Resolver soll so geschnitten sein, dass 0.9.0 spaeter einen
  CLI-Override additiv vorne einfuegen kann

### B.6 `CliContext` erweitern

`CliContext` ist um die aufgeloesten I18n-Einstellungen zu erweitern.

Mindestens noetig:

- `locale`
- `timezone`
- `normalization`

Ziel:

- spaetere Formatter muessen keinen eigenen Resolver-Layer mehr aufziehen
- die Root-CLI transportiert den Kontext einmal zentral in die Commands

### B.7 Root-Wiring vorbereiten

Phase B fuehrt noch keine lokalisierte Ausgabe ein, aber der Root-Command muss
den Resolver einmalig anbinden koennen.

Betroffene Stelle:

- [Main.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)

Verbindliche Folge:

- `cliContext()` darf nicht laenger nur Render-Flags enthalten
- das Root-Wiring bleibt zentral und testbar
- spaetere Commands bekommen den aufgeloesten Kontext ueber denselben Pfad wie
  bisher

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/...` fuer Runtime-Typen
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/...`
- `docs/implementation-plan-0.8.0.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/ImpPlan-0.8.0-A.md`
- `docs/connection-config-spec.md`
- `docs/architecture.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt`

---

## 7. Akzeptanzkriterien

- [ ] Es existiert ein expliziter Runtime-Typ fuer aufgeloeste
      I18n-Einstellungen.
- [ ] Locale, Zeitzone und Normalisierungsmodus werden ueber einen zentralen,
      testbaren Resolver statt ueber verteilte Direktzugriffe aufgeloest.
- [ ] Der effektive Config-Pfad fuer `i18n.*` folgt demselben Vertrag wie
      bestehende CLI-Konfiguration:
      `--config` > `D_MIGRATE_CONFIG` > Defaultdatei.
- [ ] `i18n.default_locale`, `i18n.default_timezone` und
      `i18n.normalize_unicode` koennen aus YAML gelesen und validiert werden.
- [ ] Ungueltige I18n-Settings liefern klare, attribuierbare lokale Fehler.
- [ ] Die Runtime ist in Tests voll kontrollierbar, ohne Abhaengigkeit von
      Host-Locale oder Host-Timezone.
- [ ] `CliContext` transportiert die aufgeloesten I18n-Einstellungen fuer
      spaetere Formatter mit.
- [ ] `--lang` ist in Phase B noch nicht Teil des aktiven 0.8.0-
      Resolution-Vertrags, bleibt aber fuer 0.9.0 additiv vorbereitbar.
- [ ] `C`, `C.UTF-8` und `POSIX` sind als gueltige Locale-Aliaswerte explizit
      geregelt.
- [ ] `D_MIGRATE_TIMEZONE` ist fuer 0.8.0 nicht Teil des aktiven
      Resolution-Vertrags.

---

## 8. Verifikation

Phase B wird primär ueber Unit- und kleine CLI-Context-Tests verifiziert.

Mindestumfang:

1. Resolver-Tests fuer Locale-Prioritaet:
   - `D_MIGRATE_LANG`
   - `LC_ALL`
   - `LANG`
   - Config
   - System
   - Fallback
2. Resolver-Tests fuer Config-Pfad-Prioritaet:
   - `--config`
   - `D_MIGRATE_CONFIG`
   - Defaultdatei
3. Parsing-Tests fuer:
   - `de_DE.UTF-8`
   - `en_US`
   - `C`
   - `C.UTF-8`
   - `POSIX`
   - `UTC`
   - `Europe/Berlin`
   - `NFC` / `NFD` / `NFKC` / `NFKD`
4. Negativtests fuer:
   - ungueltige Locale
   - ungueltige Zeitzone
   - ungueltigen Normalisierungswert
   - kaputte YAML-Datei
   - falsche YAML-Typen
5. Root-/Context-Tests dafuer, dass `CliContext` den aufgeloesten
   Laufzeitkontext traegt.
6. Vertragstest dafuer, dass `D_MIGRATE_TIMEZONE` in 0.8.0 nicht als aktive
   Override-Quelle ausgewertet wird.

---

## 9. Risiken

### R1 - Versehentliche Vorverlegung des 0.9.0-`--lang`-Vertrags

Wenn Phase B `--lang` bereits produktiv in die Resolution aufnimmt, ist die
Milestone-Grenze aus Phase A sofort wieder aufgeweicht.

### R2 - Zweiter Config-Pfadvertrag

Wenn I18n-Settings einen eigenen Config-Lookup bekommen, divergiert das
Produktverhalten zwischen Verbindungen und Lokalisierung.

### R3 - Host-abhaengige Tests

Nicht injizierte Locale-/Timezone-/ENV-Zugriffe machen den Build fragil und
plattformabhaengig.

### R4 - Uebermodellierung der Config

Phase B soll nicht versehentlich den grossen Wurf eines generischen
Gesamtkonfig-Layers implementieren, wenn fuer 0.8.0 ein fokussierter
I18n-Resolver ausreicht.

---

## 10. Abschluss-Checkliste

- [ ] Es gibt einen expliziten, testbaren Runtime-Typ fuer I18n-Einstellungen.
- [ ] Die Config-Prioritaet fuer `i18n.*` ist identisch zur bestehenden CLI.
- [ ] Locale-/Timezone-/Normalization-Parsing ist klar modelliert und getestet.
- [ ] `CliContext` transportiert den neuen Laufzeitkontext.
- [ ] `--lang` bleibt fuer Phase B ausserhalb des aktiven 0.8.0-
      Resolution-Vertrags.
- [ ] Der Teilplan bleibt kompatibel mit `docs/ImpPlan-0.8.0-A.md` und dem
      0.8.0-Masterplan.
