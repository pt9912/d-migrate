# Implementierungsplan: Phase E - Zeitzonen- und Format-Policy

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: E (Zeitzonen- und Format-Policy)
> **Status**: Implemented (2026-04-16)
> **Referenz**: `docs/planning/implementation-plan-0.8.0.md` Abschnitt 2,
> Abschnitt 4.5, Abschnitt 5.3, Abschnitt 6 Phase E, Abschnitt 7,
> Abschnitt 8, Abschnitt 9; `docs/planning/ImpPlan-0.8.0-A.md`;
> `docs/planning/ImpPlan-0.8.0-B.md`; `docs/planning/ImpPlan-0.8.0-C.md`;
> `docs/planning/ImpPlan-0.8.0-D.md`; `docs/planning/roadmap.md` Milestone 0.8.0;
> `spec/design.md` Abschnitt 6.3 und Abschnitt 9.4;
> `spec/connection-config-spec.md` Abschnitt `i18n.default_timezone`;
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

### 2.1 Stand der Codebasis **vor** Phase E

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

Der Stand vor Phase E war fachlich bereits in die richtige Richtung
unterwegs, aber die Policy war noch nicht als gemeinsame Komponente
dokumentiert und nicht explizit zwischen CLI-Helpern und Format-Layer
vereinheitlicht.

Konkrete Luecken vor Phase E:

- es gab noch keinen zentral benannten Vertrag wie
  `TemporalFormatPolicy`
- `ValueSerializer` und `DataExportHelpers.parseSinceLiteral(...)` trugen
  dieselbe Semantik, aber nicht ueber denselben Policy-Einstiegspunkt
- die Rolle von `ResolvedI18nSettings.timezone` fuer lokale vs. zonierte
  Inputs war noch nicht explizit genug dokumentiert
- Tests deckten Einzelfaelle ab, aber noch nicht den Gesamtvertrag der Phase

### 2.2 Stand der Codebasis **nach** Phase E

Mit Abschluss der Phase (Status „Implemented", 2026-04-16) gilt:

- zentral benannter Vertrag existiert als
  [`TemporalFormatPolicy`](../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/TemporalFormatPolicy.kt)
  (stateless `object` in `hexagon:application`) mit
  - ISO-Formatter-Konstanten (`ISO_LOCAL_DATE`, `ISO_LOCAL_TIME`,
    `ISO_LOCAL_DATE_TIME`, `ISO_OFFSET_DATE_TIME`),
  - Render-Helfern fuer `LocalDate`/`LocalTime`/`LocalDateTime`/
    `OffsetDateTime`/`ZonedDateTime`,
  - `parseSinceLiteral`/`parseOffsetDateTime`/`parseLocalDateTime`/
    `parseLocalDate` als gemeinsame Parser-API,
  - `hasOffsetOrZone`-Heuristik und der einzigen explizit-zonierten API
    `toZoned(local, zone)`.
- `DataExportHelpers.parseSinceLiteral(...)` delegiert an
  `TemporalFormatPolicy.parseSinceLiteral(...)` — derselbe Einstiegspunkt
  fuer CLI und Policy.
- `ValueSerializer` und `ValueDeserializer` sind unveraendert im Verhalten,
  aber mit Phase-E-Kontrakt-Kommentaren annotiert, die den Vertrag mit
  `TemporalFormatPolicy` benennen (modulgrenz-konform: keine
  Ruckwaertsabhaengigkeit von `formats` auf `application`).
- Vertragsabdeckung liegt in
  [`TemporalFormatPolicyTest`](../../hexagon/application/src/test/kotlin/dev/dmigrate/cli/i18n/TemporalFormatPolicyTest.kt)
  sowie in ergaenzten Faellen in `ValueSerializerTest`/
  `ValueDeserializerTest`/`DataExportHelpersTest`.
- Doku ist in `spec/cli-spec.md`, `spec/design.md`,
  `spec/connection-config-spec.md` und `docs/planning/roadmap.md` angeglichen.

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

### 4.1 Kanonische erweiterte ISO-8601-Profile bleiben der einzige Standardpfad

Phase E fuehrt keine mehrdeutigen Datums-/Zeitformate ein. Der Vertrag bindet
sich bewusst eng an die JDK-Profile `DateTimeFormatter.ISO_LOCAL_DATE`,
`ISO_LOCAL_TIME`, `ISO_LOCAL_DATE_TIME` und `ISO_OFFSET_DATE_TIME` — also die
erweiterte Kalender-/Zeit-Form mit Bindestrichen bzw. Doppelpunkten.

**Lese- vs. Schreibpfad sind bewusst asymmetrisch:**

- Schreibpfad ist kanonisch: `ValueSerializer` und die Render-Helfer aus
  `TemporalFormatPolicy` emittieren immer die volle erweiterte Form
  (Datum mit Tag, Zeit mit Sekunden, ggf. Fraktionssekunden nach JDK-Default,
  ggf. Offset).
- Lesepfad folgt exakt dem oben genannten JDK-ISO-Profil. Dadurch werden auch
  die im Profil legal definierten reduzierten Zeitformen akzeptiert,
  insbesondere Minuten-Praezision ohne Sekunden:
  - `2026-01-01T10:15` parst als `LocalDateTime`
  - `10:15` parst als `LocalTime` (nur im `TIME`-Pfad des `ValueDeserializer`)
  - `2026-01-01T10:15+02:00` parst als `OffsetDateTime`

Verbindliche Folge:

- Textreprasentationen fuer Datum/Zeit nutzen ausschliesslich die oben
  genannten kanonischen erweiterten ISO-Profile; `TemporalFormatPolicy`
  exportiert genau diese vier Formatter unter stabilen Namen
- JSON, YAML und CSV nutzen fuer dieselben temporalen Werte dieselbe
  semantische Darstellung
- Locale darf menschenlesbare CLI-Meldungen beeinflussen, nicht aber die
  strukturierten Zeitpayloads

Explizit **nicht** Teil des Phase-E-Vertrags:

- ISO-8601-Basic-Form ohne Trenner (`20260116T101530`) — weder Lese- noch
  Schreibpfad
- Wochen-Datum (`2026-W16-4`) und Ordinal-Datum (`2026-106`)
- Datumsformen ohne Tag (`2026-01`, `2026-01T10:15:30`) — das Datum muss
  immer `YYYY-MM-DD` tragen
- dialekt-lokale Varianten (`16.04.2026`, `04/16/2026`, `16 Apr 2026`)

Solche Eingaben werden von `TemporalFormatPolicy.parseSinceLiteral` bzw. vom
`ValueDeserializer` als Rohstring behandelt oder abgelehnt — ob sie spaeter
unterstuetzt werden, entscheidet ein Folge-Milestone explizit, nicht
Phase E.

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

### 4.6 Default-Zeitzone folgt der Phase-B-Resolve-Kette, UTC ist nur Error-Fallback

Der technische Fallback aus Phase B bleibt gueltig, darf aber nicht mit einer
generellen Umdeutung lokaler Werte verwechselt werden. Wichtig ist dabei die
tatsaechliche Reihenfolge aus
`adapters/driving/cli/.../I18nSettingsResolver.resolveTimezone`:

1. `i18n.default_timezone` aus der effektiven Konfigurationsdatei
2. `ZoneId.systemDefault()` des Hosts
3. `UTC` **nur** als Fallback, wenn das System keine Zone liefert bzw. der
   Provider wirft

Das bedeutet: auf einem ungekonfigurierten Host ist die aufgeloeste
`ResolvedI18nSettings.timezone` typischerweise die Host-Zone, nicht UTC.
UTC ist der Safety-Net am Ende der Kette, nicht der allgemeine Default.

Verbindliche Folge:

- Config/System/Fallback-Aufloesung fuer `timezone` bleibt unveraendert
  gegenueber Phase B
- der Fallback `UTC` greift nur als Error-/Leer-Safety-Net in Schritt 3
- Phase E darf UTC nicht zum impliziten Serialisierungs-Offset fuer
  `LocalDateTime` machen; die aufgeloeste Zone fliesst nur in die **explizite**
  Konvertierung ueber [`TemporalFormatPolicy.toZoned`] ein
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

- `spec/cli-spec.md` fuer den konservativen `--since`-Pfad scharfziehen
- `spec/design.md` und ggf. `docs/user/guide.md` fuer ISO-/Timezone-Regeln
  angleichen
- `spec/connection-config-spec.md` klar darauf festziehen, dass
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
- `spec/cli-spec.md`
- `spec/design.md`
- `spec/connection-config-spec.md`
- ggf. `docs/user/guide.md`

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

### Entscheidung D1 (ehem. Offene Frage O1)

Die Umsetzung waehlt den oeffentlichen Namen
[`TemporalFormatPolicy`](../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/TemporalFormatPolicy.kt)
als benannten Einstieg in den Phase-E-Vertrag. Realisiert als stateless
`object` mit ISO-Formatter-Konstanten, reinen Format-/Parse-Helfern und
der einzigen explizit-zonierten API `toZoned(local, zone)`.

Begruendung:

- benannte API macht den Vertrag an Callsites (`ValueSerializer`,
  `ValueDeserializer`, `DataExportHelpers.parseSinceLiteral`) referenzierbar
- stateless Object vermeidet globalen Zustand und Singleton-Magie
- kein Policy-Objekt pro Tabelle noetig, weil die Regeln statisch sind

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
