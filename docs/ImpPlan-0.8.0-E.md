# Implementierungsplan: Phase E - Zeitzonen- und Format-Policy

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: E (Zeitzonen- und Format-Policy)
> **Status**: Planned (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 4.5, Abschnitt 5.3, Abschnitt 6 Phase E, Abschnitt 7,
> Abschnitt 8, Abschnitt 9; `docs/ImpPlan-0.8.0-A.md`;
> `docs/ImpPlan-0.8.0-B.md`; `docs/ImpPlan-0.8.0-C.md`;
> `docs/ImpPlan-0.8.0-D.md`; `docs/roadmap.md` Milestone 0.8.0;
> `docs/design.md` Abschnitt 6.3 und Abschnitt 9.4;
> `docs/connection-config-spec.md` Abschnitt `i18n.default_timezone`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/ResolvedI18nSettings.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/ValueSerializer.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/ValueDeserializer.kt`

---

## 1. Ziel

Phase E konsolidiert den 0.8.0-Vertrag fuer temporaere Werte. Ergebnis der
Phase ist keine neue Nutzeroberflaeche, sondern eine explizite, testbare
Policy dafuer, wie `LocalDate`, `LocalDateTime`, `OffsetDateTime` und
vergleichbare Zeittypen im CLI-, Format- und Import/Export-Pfad gelesen,
serialisiert und bewusst in einen zonierten Kontext ueberfuehrt werden.

Der Teilplan liefert bewusst keine neue CLI-Option fuer Zeitzonen-Overrides
und keine locale-abhaengigen Datumsformate. Er schafft die verbindliche
Semantik hinter den bereits vorhandenen ISO-8601-Pfaden.

Nach Phase E soll klar und testbar gelten:

- ISO 8601 bleibt das Standardformat fuer temporale Textreprasentationen
- `OffsetDateTime` bleibt offsethaltig und verliert den Offset nicht still
- `LocalDateTime` bleibt lokal und wird nicht heuristisch zu UTC oder
  JVM-Lokalzeit umgedeutet
- eine Default-Zeitzone darf nur dort greifen, wo ein lokaler Input bewusst in
  einen zonierten Kontext ueberfuehrt wird
- Export, Import und `--since` folgen demselben fachlichen Regelwerk statt
  separater Einzelfalllogik
- strukturierte Formate bleiben sprachstabil; Locale steuert hier nicht die
  Datenform der Zeitwerte

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- Phase B liefert bereits einen expliziten Runtime-Kontext:
  - `ResolvedI18nSettings.locale`
  - `ResolvedI18nSettings.timezone`
  - `ResolvedI18nSettings.normalization`
- die Config-Resolution fuer die Default-Zeitzone ist bereits produktiv und
  testbar; `D_MIGRATE_TIMEZONE` bleibt in 0.8.0 bewusst ohne Wirkung
- `ValueSerializer` serialisiert temporale Werte bereits ISO-basiert:
  - `Timestamp` -> `ISO_LOCAL_DATE_TIME`
  - `LocalDateTime` -> `ISO_LOCAL_DATE_TIME`
  - `OffsetDateTime` -> `ISO_OFFSET_DATE_TIME`
  - `ZonedDateTime` -> `ISO_OFFSET_DATE_TIME`
- `ValueDeserializer` trennt `TIMESTAMP` und `TIMESTAMP WITH TIME ZONE`
  bereits bewusst:
  - `TIMESTAMP` nimmt `LocalDateTime`
  - ein String mit Offset/Zone wird dort explizit abgewiesen
  - `TIMESTAMP WITH TIME ZONE` nimmt `OffsetDateTime`
- `DataExportHelpers.parseSinceLiteral(...)` erkennt bereits:
  - `OffsetDateTime`
  - `LocalDateTime`
  - `LocalDate`
  - numerische Literale
  - sonst String-Fallback

Der aktuelle Stand ist fachlich bereits in die richtige Richtung unterwegs,
aber die Policy ist noch nicht als gemeinsame Komponente dokumentiert und nicht
explizit zwischen CLI-Helpern und Format-Layer vereinheitlicht.

Konkrete Luecken vor Phase E:

- es gibt noch keinen zentral benannten Vertrag wie
  `TemporalFormatPolicy`
- `ValueSerializer` und `DataExportHelpers.parseSinceLiteral(...)` tragen
  dieselbe Semantik, aber nicht ueber denselben Policy-Einstiegspunkt
- die Rolle von `ResolvedI18nSettings.timezone` fuer lokale vs. zonierte
  Inputs ist noch nicht explizit genug dokumentiert
- Tests decken Einzelfaelle ab, aber noch nicht den Gesamtvertrag der Phase

---

## 3. Scope und Nicht-Ziele

In Scope fuer Phase E:

- ein expliziter temporaler Policy-Vertrag fuer 0.8.0
- gemeinsame Helper oder ein dedizierter `TemporalFormatPolicy`-Typ
- Verdrahtung in den Format-Layer und in `DataExportHelpers.parseSinceLiteral`
- klare Regeln fuer lokale, offsethaltige und zonierte Zeittypen
- gezielte Tests fuer Export-, Import- und CLI-Literale
- Dokumentationsangleich fuer Zeit-/Format-Regeln

Bewusst nicht in Scope:

- neue CLI-Flags wie `--timezone`
- locale-abhaengige Eingabeformate wie `16.04.2026 10:15`
- stille Umrechnung aller Zeitwerte nach UTC
- neue Gradle-Modulstruktur
- tiefe Dialekt-Sonderfaelle jenseits des 0.8.0-Kernvertrags

---

## 4. Verbindliche Leitlinien

### 4.1 ISO 8601 bleibt der einzige Standardpfad

Phase E fuehrt keine mehrdeutigen Datums-/Zeitformate ein.

Verbindliche Folge:

- Textreprasentationen fuer Datum/Zeit bleiben ISO 8601
- JSON, YAML und CSV nutzen fuer dieselben temporalen Werte dieselbe
  semantische Darstellung
- Locale darf menschenlesbare CLI-Meldungen beeinflussen, nicht aber die
  strukturierten Zeitpayloads

### 4.2 Offsethaltige Werte bleiben offsethaltig

Ein offsethaltiger Wert darf seinen Offset nicht auf dem Weg durch Serializer,
Parser oder Helper verlieren. Fuer `ZonedDateTime` bleibt der strukturierte
0.8.0-Datenvertrag dabei bewusst offsetbasiert; die `ZoneId` ist nicht Teil
der garantierten Serialisierungsform.

Verbindliche Folge:

- `OffsetDateTime` wird mit Offset gerendert und wieder als solcher gelesen
- `ZonedDateTime` wird im Datenpfad offsetbasiert serialisiert; die Region/
  `ZoneId` wird dabei nicht garantiert transportiert
- `TIMESTAMP WITH TIME ZONE` bleibt im Anwendungspfad ein explizit
  offsethaltiger Typ
- ein zonierter Eingabewert wird nicht auf `LocalDateTime` zusammengestutzt

### 4.3 Lokale Date-Times bleiben lokal

Naive Zeitwerte tragen absichtlich keinen Offset.

Verbindliche Folge:

- `LocalDateTime` bleibt ohne Offset
- `TIMESTAMP` ohne Zeitzone bleibt ein lokaler Wert
- es gibt keine stille Interpretation als UTC oder als lokale JVM-Zeit
- Parser duerfen einen String mit Offset nicht still in `LocalDateTime`
  umdeuten

### 4.4 Default-Zeitzone ist nur ein expliziter Konvertierungsbaustein

Die in Phase B aufgeloeste Default-Zeitzone ist kein Freibrief fuer
Heuristiken.

Verbindliche Folge:

- `ResolvedI18nSettings.timezone` darf nur dort benutzt werden, wo ein
  lokaler Input bewusst in einen zonierten Kontext ueberfuehrt werden soll
- reine Serialisierung lokaler Werte liest die Default-Zeitzone nicht aus
- `parseSinceLiteral(...)` darf lokale ISO-Werte weiterhin als
  `LocalDateTime` bzw. `LocalDate` erhalten, solange kein expliziter
  zonierter Vertrag verlangt wird

### 4.5 Ein Regelwerk fuer Export, Import und `--since`

Phase E ist erst dann erreicht, wenn dieselben fachlichen Regeln an den
relevanten Callsites wiedererkennbar sind.

Verbindliche Folge:

- Format-Layer und CLI-Helfer nutzen denselben begrifflichen Vertrag
- Import bleibt strikt bei der Trennung von `TIMESTAMP` vs.
  `TIMESTAMP WITH TIME ZONE`
- `--since` bleibt konservativ typisiert und fuehrt keine implizite
  Zeitzoneninjektion ein

### 4.6 Technischer Default bleibt UTC, aber nur fuer explizite Zonierung

Der technische Fallback aus Phase B bleibt gueltig, darf aber nicht mit einer
generellen Umdeutung lokaler Werte verwechselt werden.

Verbindliche Folge:

- Config/System/Fallback-Aufloesung fuer `timezone` bleibt unveraendert
- der Fallback `UTC` greift nur bei explizitem Bedarf an einer zonierten
  Ableitung
- das Vorhandensein einer Default-Zeitzone aendert die Semantik vorhandener
  `LocalDateTime`-Werte nicht

---

## 5. Arbeitspakete

### E.1 Temporalen Policy-Typ definieren

Ziel ist ein klar benannter Einstiegspunkt fuer den 0.8.0-Vertrag.

Leitlinie:

- bevorzugt in `hexagon:application`, weil dort bereits die gemeinsamen
  I18n-/Timezone-Runtime-Typen leben
- moeglich als:
  - `TemporalFormatPolicy`
  - kleine Policy-Objekte plus Helper-Funktionen
  - dokumentierte Utility-API ohne globalen Zustand

Das Policy-Modell soll mindestens ausdruecken:

- wie lokale Werte gerendert werden
- wie offsethaltige Werte gerendert werden
- wann eine Default-Zeitzone ueberhaupt verwendet werden darf
- welche Konvertierungen explizit verboten sind

### E.2 Format-Layer auf die Policy ausrichten

`ValueSerializer` und angrenzende Helfer sollen nicht nur "zufaellig" korrekt
sein, sondern lesbar gegen den Vertrag implementiert werden.

Ziel:

- ISO-Formatter zentralisieren, statt verstreut direkt auf
  `DateTimeFormatter.ISO_*` zuzugreifen, wo die Fachsemantik mehrfach benoetigt
  wird
- Verhalten fuer `Timestamp`, `LocalDateTime`, `OffsetDateTime`,
  `ZonedDateTime`, `LocalDate` und `LocalTime` dokumentiert festziehen
- keine locale-abhaengigen Datumsformatter im Datenpfad einbauen

### E.3 `DataExportHelpers.parseSinceLiteral(...)` konsolidieren

Der `--since`-Pfad ist die sichtbarste CLI-Callsite fuer die Zeit-Policy.

Ziel:

- das konservative Parsing-Verhalten explizit gegen die Phase-E-Regeln
  dokumentieren
- vorhandene Logik entweder direkt auf den Policy-Helper aufsetzen oder
  nachweislich an denselben Vertrag anbinden
- keine automatische Umwandlung eines lokalen `YYYY-MM-DDTHH:MM:SS` in einen
  zonierten Typ nur wegen vorhandener Default-Zeitzone

### E.4 Konvertierungspunkte mit Default-Zeitzone explizit markieren

Falls es in 0.8.0 Callsites gibt, die lokale Eingaben bewusst in einen
zonierten Kontext ueberfuehren, muessen diese Stellen klar markiert und
begruendet sein.

Ziel:

- keine "heimlichen" `atZone(...)`-/`toInstant(...)`-Pfade
- klare API-Grenze zwischen:
  - lokal erhalten
  - bewusst zonieren
- explizite Uebergabe der aufgeloesten `ZoneId` statt implizitem Zugriff auf
  JVM-Defaults

### E.5 Dokumentation angleichen

Nach Phase E muessen Code und Doku denselben Vertrag sprechen.

Ziel:

- `docs/cli-spec.md` fuer den konservativen `--since`-Pfad scharfziehen
- `docs/design.md` und ggf. `docs/guide.md` fuer ISO-/Timezone-Regeln
  angleichen
- `docs/connection-config-spec.md` klar darauf festziehen, dass
  `i18n.default_timezone` keine blanket Umdeutung lokaler Daten ausloest

---

## 6. Teststrategie

Phase E braucht keine riesige neue Testmatrix, aber eine geschlossene
Vertragsabdeckung.

Pflichtfaelle:

- Serializer:
  - `LocalDateTime` -> ISO ohne Offset
  - `OffsetDateTime` -> ISO mit Offset
  - `ZonedDateTime` -> offsethaltige Ausgabe; `ZoneId` ist dabei nicht Teil
    des 0.8.0-Vertrags
- Deserializer:
  - `TIMESTAMP` akzeptiert lokale ISO-Werte
  - `TIMESTAMP` lehnt Strings mit Offset/Zone ab
  - `TIMESTAMP WITH TIME ZONE` akzeptiert offsethaltige ISO-Werte
- `parseSinceLiteral(...)`:
  - Offset-Input bleibt `OffsetDateTime`
  - lokaler DateTime-Input bleibt `LocalDateTime`
  - Date-Input bleibt `LocalDate`
  - keine Auto-Zonierung aus `ResolvedI18nSettings.timezone`
- Runtime-/Policy-Tests:
  - explizite Konvertierung mit Default-Zeitzone ist reproduzierbar
  - fehlende explizite Konvertierung laesst lokale Werte unveraendert

Zusatznutzen:

- die Tests muessen klar zwischen "lokal" und "offsethaltig" unterscheiden,
  damit spaetere Refactorings keinen heuristischen UTC-Pfad einschmuggeln

---

## 7. Datei- und Codebasis-Betroffenheit

Voraussichtlich betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/ValueSerializer.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/ValueDeserializer.kt`
- `docs/cli-spec.md`
- `docs/design.md`
- `docs/connection-config-spec.md`
- ggf. `docs/guide.md`

Neue oder angepasste Tests voraussichtlich in:

- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataExportHelpersTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/ValueSerializerTest.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/data/ValueDeserializerTest.kt`
- ggf. neue Policy-spezifische Tests in `hexagon/application/src/test/...`

---

## 8. Risiken und offene Punkte

### R1 - Implizite UTC-Normalisierung wuerde fachliche Daten verfalschen

Wenn lokale Zeitwerte bei Refactorings still zoniert werden, veraendert sich
der Nutzwert semantisch.

Mitigation:

- lokale und offsethaltige Typen strikt getrennt halten
- Default-Zeitzone nur ueber explizite API benutzen

### R2 - Mehrere kleine Zeit-Helper fuehren wieder zu Drift

Wenn `ValueSerializer`, Import-Pfade und CLI-Helper jeweils ihre eigene
Mini-Regel behalten, divergiert das Verhalten spaeter erneut.

Mitigation:

- zentral benannte Policy-Komponente
- Audit der relevanten Callsites in derselben Phase

### R3 - ZonedDateTime kann semantisch mehr tragen als OffsetDateTime

Ein `ZonedDateTime` enthaelt neben dem Offset auch eine Region/ZoneId.
Fuer 0.8.0 wird dieser Mehrwert im strukturierten Datenpfad bewusst nicht voll
abgebildet; der Vertrag bleibt offsetbasiert.

Festlegung fuer diesen Plan:

- fuer 0.8.0 bleibt der strukturierte Datenvertrag offsetbasiert
- volle ZoneId-Serialisierung ist ein moeglicher spaeterer Ausbau, nicht Kern
  dieses Milestones

### Offene Frage O1

Brauchen wir fuer 0.8.0 ueberhaupt einen oeffentlichen Typnamen
`TemporalFormatPolicy`, oder reicht ein kleiner Satz klar dokumentierter Helper?

Empfehlung:

- Namensgebung pragmatisch waehlen
- entscheidend ist der gemeinsame Vertrag, nicht der exakte Klassenname

### Offene Frage O2

Soll `parseSinceLiteral(...)` kuenftig einen expliziten zonierten Modus
unterstuetzen, falls ein spaeterer CLI-Vertrag dies braucht?

Empfehlung:

- in 0.8.0 noch nicht
- zuerst den konservativen Local-vs-Offset-Vertrag stabilisieren

---

## 9. Entscheidungsempfehlung

Empfohlen wird fuer 0.8.0 eine bewusst enge, explizite Zeit-Policy mit
folgendem Vertrag:

- ISO 8601 bleibt das einzige Standardformat
- `OffsetDateTime` bleibt offsethaltig
- `LocalDateTime` bleibt ohne Offset
- `ZonedDateTime` wird im Datenvertrag offsetbasiert serialisiert; die `ZoneId`
  gehoert nicht zu 0.8.0
- es gibt keine stille Umdeutung lokaler Date-Times in UTC
- die Default-Zeitzone greift nur bei expliziter Ueberfuehrung in einen
  zonierten Kontext
- `ValueSerializer`, `ValueDeserializer` und `DataExportHelpers.parseSinceLiteral(...)`
  werden lesbar gegen denselben Vertrag ausgerichtet

Damit schliesst Phase E die konzeptionelle Luecke zwischen Phase B
(Runtime-/Config-Zeitzone) und den produktiven Datenpfaden: nicht durch neue
Features, sondern durch einen klaren, belastbaren Vertrag fuer temporaere
Werte in 0.8.0.
