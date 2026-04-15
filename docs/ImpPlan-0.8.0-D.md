# Implementierungsplan: Phase D - Unicode-/ICU4J-Integration

> **Milestone**: 0.8.0 - Internationalisierung
> **Phase**: D (Unicode-/ICU4J-Integration)
> **Status**: Review (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.8.0.md` Abschnitt 2, Abschnitt 3,
> Abschnitt 4.4, Abschnitt 4.6, Abschnitt 5.1, Abschnitt 5.4, Abschnitt 6
> Phase D, Abschnitt 7, Abschnitt 8, Abschnitt 9; `docs/ImpPlan-0.8.0-A.md`;
> `docs/ImpPlan-0.8.0-B.md`; `docs/roadmap.md` Milestone 0.8.0;
> `docs/architecture.md` Abschnitt 9 Internationalisierung;
> `docs/connection-config-spec.md` Abschnitt `i18n.normalize_unicode`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/ResolvedI18nSettings.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/UnicodeNormalizationMode.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt`

---

## 1. Ziel

Phase D macht den Unicode-Teil des 0.8.0-Vertrags produktiv belastbar.
Ergebnis der Phase sind klar abgegrenzte ICU4J-basierte Utilities fuer
Grapheme-Counting und Unicode-Normalisierung sowie ein gezielter Review der
relevanten Callsites, an denen rohe Java-/Kotlin-String-Semantik fachlich nicht
ausreicht.

Der Teilplan liefert bewusst keine blanket Umstellung des gesamten Repos auf
ICU4J. Er fuehrt genau die Hilfskomponenten und Integrationen ein, die fuer
semantische Zeichenlaenge, Vergleichsstabilitaet und reproduzierbares
Normalisierungsverhalten in 0.8.0 wirklich noetig sind.

Nach Phase D soll klar und testbar gelten:

- `UnicodeNormalizationMode` aus Phase B ist produktiv nutzbar statt nur
  konfigurierbar
- es existiert ein `UnicodeNormalizer` fuer `NFC`, `NFD`, `NFKC`, `NFKD`
- es existiert ein `GraphemeCounter`, der Zeichencluster statt UTF-16-Codeunits
  zaehlt
- nur fachlich relevante `.length`-/Vergleichsstellen werden umgestellt
- Datenpayloads aus Export/Import/Transfer werden nicht still normalisiert
- ICU4J bleibt eine pure Utility-Abhaengigkeit ohne globalen Locale-State
- der in `ResolvedI18nSettings.normalization` aufgeloeste Modus wird von den
  produktiven Consumern explizit uebergeben statt nur indirekt "vorhanden" zu
  sein

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- Phase B hat bereits den Runtime-Unterbau geschaffen:
  - `ResolvedI18nSettings`
  - `UnicodeNormalizationMode`
  - `I18nSettingsResolver` mit produktiver Aufloesung von
    `i18n.normalize_unicode`
- der Repo-Zustand kennt heute also schon den Konfigurationsvertrag fuer
  Unicode-Normalisierung, aber noch keine produktiven Utilities, die diesen
  Modus in der Fachlogik verwenden
- ICU4J ist derzeit noch nicht als produktive Abhaengigkeit in den relevanten
  Modulen eingebunden
- im Code existieren viele rohe `.length`-, `substring(...)`- und
  Zeichenkettenpfade; nur ein kleiner Teil davon ist fuer Unicode-Semantik
  fachlich relevant
- bestehende low-level String-Operationen in Parsern, Serializern, CSV-Readern,
  Escapern oder JDBC-nahen Hilfen arbeiten heute bewusst auf Codeunit- oder
  Positionsbasis und duerfen nicht pauschal auf Grapheme-Semantik umgestellt
  werden

Konsequenz:

- ohne Phase D bleibt `i18n.normalize_unicode` ein konfigurierbarer, aber nicht
  wirklich produktiver Teil des 0.8.0-Vertrags
- ohne Grapheme-Utility bleibt die Lastenheft-Anforderung zur grapheme-aware
  Laengenberechnung nur dokumentiert, nicht belastbar umgesetzt
- ein ungezieltes Ersetzen von `.length` durch ICU-Logik wuerde dagegen
  Performance, Parserlogik und technische String-Indizes gefaehrden

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Aufnahme von ICU4J als produktive Abhaengigkeit in den Modulen, in denen die
  Unicode-Utilities leben oder direkt genutzt werden
- Implementierung eines `UnicodeNormalizer`
- Implementierung eines `GraphemeCounter`
- gezielter Audit bestehender `.length`-/Vergleichsstellen mit dokumentierter
  Entscheidung:
  - auf ICU-Utility umstellen
  - bewusst bei roher String-Semantik bleiben
- erste produktive Integration der Utilities in fachlich relevanten
  Metadaten-/Vergleichs-/Nutzertextpfaden
- Tests fuer Emoji, kombinierte Zeichen, CJK, Kyrillisch und
  Normalisierungsmodi

### 3.2 Bewusst nicht Teil von Phase D

- blanket Ersatz jeder `.length`-Nutzung im gesamten Codebestand
- stille Unicode-Normalisierung von Export-/Import-/Transfer-Payloads
- globale Ersetzung technischer `substring(...)`-/Index-Logik durch
  grapheme-aware Semantik
- vollstaendige locale-sensitive Sortierung im gesamten Produkt
- breite CLDR-/Collation-Features fuer Listen, Reports oder Datenbankinhalte
- heuristische Umformulierung oder Bereinigung freier Nutzdaten

Praezisierung:

Phase D loest "wo brauchen wir semantische Unicode-Utilities wirklich?" und
nicht "jede Zeichenkettenoperation im Repo wird unicode-magisch".

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 ICU4J bleibt Utility, kein globaler Laufzeitmodus

Phase D fuehrt ICU4J als Bibliothek fuer klar benannte Helfer ein.

Verbindliche Folge:

- keine globalen Singletons mit verstecktem Locale-Zustand
- keine implizite Abhaengigkeit auf JVM-Default-Locale
- Utilities bleiben pure Funktionen oder leicht injizierbare Helfer

### 4.2 Unicode-Normalisierung ist Vergleichsregel, keine Datenmutation

Phase A und der Masterplan haben diese Grenze bereits festgezogen.

Verbindliche Folge:

- `UnicodeNormalizer` wird fuer Vergleiche, Metadaten und
  Vergleichsstabilitaet eingesetzt
- Tabellenwerte, JSON-/YAML-/CSV-Payloads und freie Nutzdaten werden dadurch
  nicht automatisch umgeschrieben
- jede spaetere Payload-Normalisierung waere eigener Scope und nicht Teil von
  0.8.0

### 4.3 Grapheme-Counting nur dort, wo Nutzer oder Fachlogik Zeichen meinen

Nicht jede Stringlaenge braucht Grapheme-Semantik.

Verbindliche Folge:

- Nutzernahe Laengenangaben, Identifier-Grenzen oder Diagnosepfade mit
  semantischer Zeichenlaenge duerfen `GraphemeCounter` nutzen
- technische Puffer-, Escape-, Slice- und Parserlogik bleibt bei roher
  String-/Codeunit-Semantik, solange keine fachliche Unicode-Anforderung
  besteht

### 4.4 `ResolvedI18nSettings.normalization` wird explizit in Consumer verdrahtet

Phase D reicht kein "Utility existiert".

Verbindliche Folge:

- der zur Laufzeit aufgeloeste `ResolvedI18nSettings.normalization`-Wert wird
  an jede fachlich relevante Unicode-Callsite explizit weitergereicht
- Consumer lesen den Modus nicht ueber implizite JVM-Defaults, globale
  Singletons oder versteckte Fallbacks
- Phase D gilt erst dann als erfuellt, wenn mindestens eine produktive
  Vergleichs-/Metadaten-Callsite nachweislich den aufgeloesten Modus nutzt

### 4.5 Audit entscheidet explizit zwischen "umstellen" und "bewusst nicht"

Phase D darf keine implizite Halb-Loesung produzieren.

Verbindliche Folge:

- relevante Fundstellen werden explizit geprueft
- pro Fundstelle gibt es eine dokumentierte Entscheidung
- nicht umgestellte Pfade gelten nicht als vergessen, sondern als bewusst
  technisch

### 4.6 Slug-/Identity-Pfade sind standardmaessig nicht umzubauen

Bestehende Migrationsartefakt-Pfade tragen Vertragslast.

Verbindliche Folge:

- `MigrationSlugNormalizer`, `MigrationIdentityResolver` und aehnliche
  Artefaktidentitaets-Pfade werden in Phase D nicht still auf ICU-/Unicode-
  Normalisierung umgestellt
- falls der Audit dort ueberhaupt einen fachlichen Handlungsbedarf findet,
  braucht die Aenderung einen expliziten Kompatibilitaetsentscheid mit
  Regressionstests fuer bestehende Slugs/Identitaeten
- ohne diesen Zusatzentscheid gelten diese Pfade fuer 0.8.0 als bewusst
  unveraendert

### 4.7 Locale-aware Comparator bleibt fuer 0.8.0 optional und eng begrenzt

Der Masterplan nennt einen moeglichen `LocaleAwareComparator`, aber er ist fuer
0.8.0 nicht der Kern des Deliverables.

Verbindliche Folge:

- Phase D priorisiert `UnicodeNormalizer` und `GraphemeCounter`
- Comparator-/Collation-Logik kommt nur dann in Scope, wenn eine konkrete,
  bestehende 0.8.0-Callsite ohne sie fachlich falsch bleibt
- ohne solche Callsite bleibt Comparator-Support bewusst spaeterer Ausbau

---

## 5. Arbeitspakete

### D.1 ICU4J als produktive Abhaengigkeit aufnehmen

Phase D fuehrt ICU4J dort ein, wo die Unicode-Utilities leben und getestet
werden.

Leitlinie:

- die Abhaengigkeit soll nicht blind in alle Module gestreut werden
- primaerer Heimatort fuer die Helfer ist gemaess 0.8.0-Architekturschnitt
  `hexagon:application`
- nur direkte Consumer bekommen zusaetzliche Modulabhaengigkeiten

Ziel:

- produktive Unicode-Utilities ohne Copy/Paste oder CLI-spezifische
  Hilfskonstruktionen

### D.2 `UnicodeNormalizer` implementieren

Mindestens noetig:

- API fuer `normalize(input: CharSequence, mode: UnicodeNormalizationMode): String`
- Mapping aller vier Modi:
  - `NFC`
  - `NFD`
  - `NFKC`
  - `NFKD`
- deterministisches Verhalten fuer `null`-freie, pure String-Ein-/Ausgaben

Wichtig:

- keine stillen Fall-Back-Heuristiken auf andere Modi
- kein verstecktes Heranziehen der System-Locale
- bei nicht veraenderter Eingabe bleibt das Ergebnis semantisch stabil
- produktive Consumer bekommen den Modus explizit aus
  `ResolvedI18nSettings.normalization`

### D.3 `GraphemeCounter` implementieren

Mindestens noetig:

- API fuer semantische Zeichenlaenge auf Basis von Grapheme-Clustern
- belastbare Behandlung von:
  - einfachen ASCII-Zeichen
  - kombinierten Akzenten
  - Emoji
  - ZWJ-Sequenzen
  - CJK

Wichtig:

- keine Verwechslung mit Byte-Laenge oder UTF-16-Codeunits
- keine ungepruefte Nutzung in Hot Paths ohne fachlichen Bedarf

### D.4 Relevante Callsites auditieren und gezielt umstellen

Phase D braucht einen kleinen, expliziten Audit statt Repo-weitem Refactor.

Mindestens zu pruefen:

- nutzernahe Laengenpfade in Validierungs- oder Diagnosekontexten
- Identifier-/Metadatenpfade, in denen Unicode-Vergleichsstabilitaet relevant
  ist
- bestehende Normalizer-/Slug-/Vergleichshelfer, sofern sie
  unicode-sensitive Gleichheit oder Darstellung betreffen

Vertrag fuer bestehende Artefaktpfade:

- `MigrationSlugNormalizer` und `MigrationIdentityResolver` werden standardmaessig
  nur auditiert, nicht fachlich umgebaut
- jede Aenderung an Slug-/Identity-Semantik braucht explizite
  Kompatibilitaetsregressionen fuer bestehende Dateinamen und
  `MigrationIdentity`-Ableitungen

Explizit typischerweise nicht umzustellen:

- `substring(...)`-/Indexlogik in Parsern
- Escape-/Serializer-Builder mit `StringBuilder(...length...)`
- BLOB/CLOB-/Byte- oder Streamlaengen
- CSV-/JSON-/YAML-Tokenizer auf Positionsbasis

Ziel:

- echte Unicode-Verbesserung ohne technische Regressionen durch Ueberreach

### D.5 Tests fuer Unicode-Integritaet und Modusverhalten

Mindestumfang:

- `UnicodeNormalizer` fuer alle vier Modi
- `GraphemeCounter` fuer typische Mehrcodepunkt-Zeichen
- Regression-Tests fuer gezielt umgestellte Callsites
- Nachweis, dass Nutzdatenpayloads nicht still normalisiert werden

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/application/build.gradle.kts`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/...`
- fachlich relevante Consumer in `hexagon/application/...`,
  `adapters/driving/cli/...` oder `adapters/driven/formats/...`
- `docs/implementation-plan-0.8.0.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/ImpPlan-0.8.0-A.md`
- `docs/ImpPlan-0.8.0-B.md`
- `docs/connection-config-spec.md`
- `docs/architecture.md`

---

## 7. Akzeptanzkriterien

- [ ] ICU4J ist als produktive Abhaengigkeit in den benoetigten Modulen
      eingebunden.
- [ ] Es existiert ein `UnicodeNormalizer`, der `NFC`, `NFD`, `NFKC`, `NFKD`
      korrekt abbildet.
- [ ] Es existiert ein `GraphemeCounter`, der Mehrcodepunkt-Zeichen korrekt als
      semantische Einheiten behandeln kann.
- [ ] Relevante `.length`-/Vergleichsstellen wurden geprueft und explizit
      entschieden statt pauschal ersetzt.
- [ ] Mindestens die fachlich relevanten Unicode-Callsites nutzen die neuen
      Utilities produktiv.
- [ ] Der produktive Normalisierungsmodus wird aus
      `ResolvedI18nSettings.normalization` explizit in die relevanten Consumer
      verdrahtet.
- [ ] Export-/Import-/Transfer-Payloads werden durch Phase D nicht still
      normalisiert.
- [ ] Slug-/Identity-Pfade bleiben ohne expliziten Kompatibilitaetsentscheid
      semantisch unveraendert.
- [ ] Tests decken Emoji, kombinierte Zeichen, Kyrillisch und CJK ab.

---

## 8. Verifikation

Phase D wird ueber Utility-Tests und wenige gezielte Integrationsregressionen
verifiziert.

Mindestumfang:

1. `UnicodeNormalizer`-Tests:
   - `NFC`
   - `NFD`
   - `NFKC`
   - `NFKD`
   - bereits normalisierte Eingaben bleiben stabil
2. `GraphemeCounter`-Tests:
   - `a`
   - `ä`
   - kombinierter Akzent
   - `👨‍👩‍👧‍👦`
   - CJK-Beispiele
3. Integrationsregressionen:
   - mindestens eine gezielt umgestellte fachliche Callsite
   - Nachweis, dass der verwendete Modus aus
     `ResolvedI18nSettings.normalization` stammt
   - mindestens eine bewusst technische `.length`-Stelle bleibt unveraendert
   - `MigrationSlugNormalizer`/`MigrationIdentityResolver` bleiben ohne
     expliziten Zusatzentscheid vertraglich stabil
4. Negativabgrenzung:
   - Nutzdaten werden nicht automatisch normalisiert

---

## 9. Risiken

### R1 - Zu breite ICU4J-Nutzung verlangsamt oder verkompliziert Hot Paths

Wenn jede Stringoperation reflexhaft ueber ICU laeuft, verlieren wir
Verstaendlichkeit und moeglicherweise Performance ohne fachlichen Mehrwert.

### R2 - Zu aggressive Normalisierung veraendert Nutzerinhalte

Das wuerde den in Phase A festgezogenen 0.8.0-Vertrag direkt verletzen.

### R3 - Zu enger Audit verfehlt echte Unicode-Problempfade

Wenn nur die neuen Utilities gebaut, aber keine relevanten Consumer umgestellt
werden, bleibt Phase D technisch korrekt, aber fachlich zu duenn.

### R4 - Grapheme- und technische String-Semantik werden vermischt

Das kann Parser-, Tokenizer- oder Indexlogik brechen, obwohl dort bewusst keine
semantische Zeichenlaenge gebraucht wird.

---

## 10. Abschluss-Checkliste

- [ ] ICU4J ist in der final gewaelten Utility-Heimat produktiv eingebunden.
- [ ] `UnicodeNormalizer` und `GraphemeCounter` existieren als pure,
      testbare Helfer.
- [ ] Die Modi `NFC`, `NFD`, `NFKC`, `NFKD` sind produktiv nutzbar.
- [ ] Die produktive Nutzung erfolgt ueber explizite Verdrahtung von
      `ResolvedI18nSettings.normalization`.
- [ ] Relevante Unicode-Callsites wurden auditiert und explizit entschieden.
- [ ] Slug-/Identity-Vertraege wurden nicht still veraendert.
- [ ] Datenpayloads bleiben von stiller Normalisierung unberuehrt.
- [ ] Die Phase bleibt kompatibel mit `docs/ImpPlan-0.8.0-A.md`,
      `docs/ImpPlan-0.8.0-B.md`, `docs/ImpPlan-0.8.0-C.md` und dem
      0.8.0-Masterplan.
