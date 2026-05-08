# Refactoring-Plan: ICU4J hinter einen Hexagon-Port ziehen

> Status: Draft (2026-04-25)
>
> Anlass: `hexagon/application/build.gradle.kts` referenziert ICU4J direkt.
> Das ist nach der aktuellen Architektur-Doku erlaubt, weil ICU4J in der
> Tech-Stack-Tabelle fuer `application/cli` genannt ist. Nach strengerer
> Clean-Architecture-Auslegung soll `hexagon:application` aber keine
> konkreten Third-Party-Details wie ICU4J kennen.

---

## 1. Ziel

ICU4J soll aus `hexagon:application` herausgezogen und hinter eine
adapterneutrale Unicode-Abstraktion gelegt werden.

Nach dem Refactoring gilt:

- `hexagon:application` haengt nicht mehr direkt von
  `com.ibm.icu:icu4j` ab
- Unicode-Normalisierung und Grapheme-Counting bleiben fachlich unveraendert
- ICU4J-Typen tauchen nicht in Ports, Domain-Modellen oder Runner-APIs auf
- CLI und spaetere Adapter koennen dieselbe Unicode-Faehigkeit ueber einen
  Port nutzen
- bestehende Tests fuer Unicode-Normalisierung und Grapheme-Counting bleiben
  erhalten und werden gegen Port + ICU-Implementierung verschoben

---

## 2. Aktueller Stand

Direkte Dependency:

- `hexagon/application/build.gradle.kts`
  - `implementation("com.ibm.icu:icu4j:76.1")`

Direkte ICU4J-Nutzung:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/UnicodeNormalizer.kt`
  - nutzt `com.ibm.icu.text.Normalizer2`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/GraphemeCounter.kt`
  - nutzt `com.ibm.icu.text.BreakIterator`

Weitere betroffene Anwendungspfade:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/i18n/UnicodeCompare.kt`
  - nutzt `UnicodeNormalizer`
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/fingerprint/JsonCanonicalizer.kt`
  - nutzt `UnicodeNormalizer.normalize(..., NFC)` fuer JCS-Normalisierung
    von Object-Keys und Stringwerten (0.9.6 Phase A AP 6.4)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/OutputFormatter.kt`
  - nutzt `UnicodeNormalizer.normalize(...)`
- CLI-I18n-Konfiguration nutzt `UnicodeNormalizationMode`

Tests:

- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/i18n/UnicodeNormalizerTest.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/i18n/GraphemeCounterTest.kt`

---

## 3. Architekturentscheidung

### 3.1 Port im inneren Hexagon

Ein neuer Port beschreibt die benoetigten Unicode-Faehigkeiten ohne ICU4J:

```kotlin
interface UnicodeTextService {
    fun normalize(input: CharSequence, mode: UnicodeNormalizationMode): String
    fun isNormalized(input: CharSequence, mode: UnicodeNormalizationMode): Boolean
    fun graphemeCount(input: CharSequence): Int
}
```

`UnicodeNormalizationMode` bleibt ein eigener d-migrate-Typ. Er darf nicht
durch ICU4J-Typen ersetzt werden.

Empfohlener Modulort:

- `hexagon:ports-common`
  - `UnicodeTextService`
  - `UnicodeNormalizationMode`, falls dieser Typ aus `hexagon:application`
    herausgezogen werden soll

Empfohlenes neutrales Package:

- `dev.dmigrate.text`
  - keine `cli`- oder adapterbezogenen Namensbestandteile
  - geeignet fuer CLI, MCP, REST/gRPC und Tests

Ausdruecklich nicht in den Port gehoert eine generische ICU-Fassade. Hilfs-
funktionen wie `normalizedEquals(left, right, mode)` koennen als kleine
Application-/Test-Helper aus `normalize(...)` zusammengesetzt werden, sollen
aber nicht die Port-API vergroessern.

Alternative:

- `hexagon:core`, falls Unicode-Normalisierung als reine Domain-Utility
  betrachtet wird. Das ist nur sinnvoll, wenn `core` weiterhin keine externe
  Library-Dependency bekommt.

### 3.2 ICU4J als driven Infrastruktur

Die konkrete ICU4J-Implementierung lebt ausserhalb von `hexagon:application`,
z. B. in einem neuen Modul:

- `adapters:driven:text-icu`

Dieses Modul enthaelt:

- `IcuUnicodeTextService`
- Dependency auf `com.ibm.icu:icu4j`
- Tests fuer ICU-spezifisches Verhalten

`hexagon:application` bekommt nur `UnicodeTextService` injiziert oder als
Parameter in die relevanten Services/Runner verdrahtet.

### 3.3 Composition Root und Abhaengigkeitsrichtung

Die driven ICU-Implementierung darf nicht von `hexagon:application`
referenziert werden. Die Verdrahtung liegt im jeweiligen driving Adapter:

- `adapters:driving:cli` haengt auf `hexagon:application`,
  `hexagon:ports-common` und `adapters:driven:text-icu`
- der CLI-Startpfad instanziiert `IcuUnicodeTextService`
- Runner, Formatter und andere Application-nahe Services erhalten nur
  `UnicodeTextService`
- spaetere Adapter, z. B. MCP, muessen dieselbe Regel befolgen und ihre
  konkrete Implementierung am Adapterrand verdrahten

Damit bleibt die Abhaengigkeitsrichtung:

```text
driving adapter -> application -> ports/common contracts
driving adapter -> driven text-icu -> ports/common contracts
```

`hexagon:application` darf weder vom neuen driven Modul noch von ICU4J selbst
abhaengen.

---

## 4. Migrationsschritte

### Schritt 1 - Port einfuehren

- `UnicodeTextService` in einem inneren Port-Modul anlegen
- `UnicodeNormalizationMode` aus `hexagon/application/.../cli/i18n` in den
  neutralen Modul-/Packagebereich `hexagon:ports-common` /
  `dev.dmigrate.text` verschieben oder eine kompatible Kopie mit Migration
  anlegen
- bestehende Imports in CLI-Konfiguration, `ResolvedI18nSettings`,
  `CliContext`, `I18nSettingsResolver`, Tests und Output-Formatierung
  anpassen
- Package `dev.dmigrate.cli.i18n` nicht als neuer innerer Vertrag
  fortschreiben; es ist ein historischer Ort, kein Zielpackage

Abnahme:

- keine ICU4J-Typen im Port
- `hexagon:application` kompiliert noch, auch wenn die Implementierung
  zunaechst weiter im gleichen Modul liegt

### Schritt 2 - Application-Code entkoppeln

- statische Objekte `UnicodeNormalizer`, `GraphemeCounter` und
  `UnicodeCompare` durch Nutzung von `UnicodeTextService` ersetzen
- dort, wo heute direkt `UnicodeNormalizer.normalize(...)` gerufen wird,
  den Service injizieren oder durch eine klar verdrahtete Application-
  Abhaengigkeit bereitstellen
- `adapters/driving/cli/.../OutputFormatter.kt` ausdruecklich migrieren:
  der Formatter erhaelt `UnicodeTextService` ueber CLI-Wiring oder ueber
  einen bereits verdrahteten Formatierungskontext, aber nicht ueber ein
  statisches Application-Utility
- `hexagon/.../server/application/fingerprint/JsonCanonicalizer.kt` migrieren:
  der bisher `internal object` ist heute parameterlos; im Zuge der
  Portierung muss er `UnicodeTextService` als Konstruktor-Parameter
  erhalten (oder `PayloadFingerprintService` reicht den Service durch).
  Das ist ein Strukturwechsel von `object` zu `class`, deshalb separat
  einplanen und in den 6.4-Tests adaptieren.
- keine globale Singleton-Abkuerzung einfuehren, die den Port wieder
  umgeht

Abnahme:

- `hexagon:application` kennt keine `com.ibm.icu.*` Imports mehr
- Application-Tests koennen eine Fake-Implementierung des Ports verwenden

### Schritt 3 - ICU-Implementierung verschieben

- neues driven Modul fuer ICU-Textfunktionen anlegen
- Modul in `settings.gradle.kts` registrieren und Gradle-Abhaengigkeiten
  explizit setzen
- `IcuUnicodeTextService` mit `Normalizer2` und `BreakIterator`
  implementieren
- ICU4J-Dependency aus `hexagon/application/build.gradle.kts` entfernen
- ICU4J-Dependency in das neue driven Modul verschieben

Abnahme:

- Docker-basierter Dependency-Check zeigt keine ICU4J-Runtime-Dependency in
  `hexagon:application`, z. B. ueber:

  ```bash
  docker build --target build \
    --build-arg GRADLE_TASKS=":hexagon:application:dependencies" \
    -t d-migrate:dependency-check .
  ```

- ICU4J taucht nur noch im driven Text-/ICU-Modul auf

### Schritt 4 - Verdrahtung

- CLI-Adapter verdrahtet `IcuUnicodeTextService` fuer produktive CLI-Laeufe
- CLI-Adapter bleibt der Composition Root fuer CLI-Ausfuehrungen; weder
  `hexagon:application` noch `hexagon:ports-common` erzeugen selbst eine
  ICU-Implementierung
- Tests koennen je nach Ebene nutzen:
  - Fake-Service fuer Application-Tests
  - ICU-Service fuer driven Contract-Tests
  - End-to-End-Tests mit CLI-Verdrahtung

Abnahme:

- bestehende CLI-Ausgabe bleibt unveraendert
- Normalisierungsmodus aus I18n-Konfiguration wird weiterhin respektiert

---

## 5. Teststrategie

### Port-Contract-Tests

Diese Tests laufen gegen jede `UnicodeTextService`-Implementierung. Der
gemeinsame Testvertrag soll wiederverwendbar abgelegt werden, z. B. als
abstrakte Testsuite oder Testkit in einem inneren Test-Source-Set. Das driven
ICU-Modul bindet diesen Vertrag ein und liefert nur die konkrete
`IcuUnicodeTextService`-Factory.

- NFC normalisiert kombinierte Zeichen korrekt
- NFD zerlegt kombinierte Zeichen korrekt
- NFKC/NFKD behandeln Kompatibilitaetszeichen
- `isNormalized` erkennt normalisierte und nicht normalisierte Eingaben
- Grapheme-Counting fuer:
  - ASCII
  - kombinierte Zeichen
  - Emoji
  - ZWJ-Sequenzen
  - Flaggen
  - CJK
  - Kyrillisch
- Nutzdaten werden nicht still mutiert; Normalisierung bleibt expliziter
  Funktionsaufruf

### Application-Tests

- Runner/Formatter nutzen den Port, nicht ICU4J direkt
- Fake-Service kann Normalisierung sichtbar simulieren
- keine Tests in `hexagon:application` benoetigen ICU4J-Klassen

### Adapter-/Integrationstests

- CLI verdrahtet ICU-Service produktiv
- `OutputFormatter` nutzt den verdrahteten `UnicodeTextService` und importiert
  keine alten Application-Utilities
- bestehende Unicode-Ausgaben bleiben stabil
- Dependency-Check verhindert erneute ICU4J-Dependency in
  `hexagon:application`

---

## 6. Risiken

### R1 - Zu viel Verdrahtung fuer reine Utility

ICU4J ist fachlich eine Utility-Library. Ein Port kann ueberdimensioniert
wirken.

Gegenmassnahme:

- Port klein halten
- nur tatsaechlich genutzte Funktionen aufnehmen
- keine generische ICU-Fassade bauen

### R2 - CLI-nahe I18n-Typen wandern zu tief

`UnicodeNormalizationMode` wird heute aus CLI-Konfiguration genutzt. Wenn der
Typ nach `ports-common` wandert, darf er nicht CLI-spezifisch benannt oder
semantisch ueberladen sein.

Gegenmassnahme:

- Typ neutral benennen und dokumentieren
- CLI-Konfiguration mappt nur auf diesen neutralen Typ

### R3 - Verhalten aendert sich unbemerkt

Grapheme-Counting und Normalisierung sind detailreich.

Gegenmassnahme:

- bestehende Tests vor dem Verschieben sichern
- Contract-Tests gegen die neue ICU-Implementierung laufen lassen
- Unicode-Edge-Cases aus den heutigen Tests uebernehmen

---

## 7. Definition of Done

Das Refactoring ist abgeschlossen, wenn:

- `hexagon/application/build.gradle.kts` keine ICU4J-Dependency mehr enthaelt
- `hexagon/application` keine `com.ibm.icu.*` Imports mehr enthaelt
- ein innerer Port die benoetigten Unicode-Funktionen beschreibt
- eine ICU4J-basierte driven Implementierung existiert
- CLI-Produktpfad die ICU-Implementierung verdrahtet
- Application-Tests ohne ICU4J laufen koennen
- ICU-Contract-Tests die bisherigen Unicode-/Grapheme-Faelle abdecken
- `spec/architecture.md` die neue Modulgrenze dokumentiert
  - Tech-Stack-Scope fuer ICU4J von `application/cli` auf driven ICU-Modul
    und driving Composition Root umstellen
  - historische Implementierungsplaene duerfen abweichen, sofern
    `spec/architecture.md` als aktuelle Norm festgelegt ist
