# Implementierungsplan: Phase D - Profiling aus Treiber-Kernmodulen extrahieren

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: D (Profiling aus Treiber-Kernmodulen extrahieren)
> **Status**: Draft (2026-04-18)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.4, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/d-browser-integration-coupling-assessment.md`;
> `hexagon/profiling/build.gradle.kts`;
> `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/ProfilingAdapterSet.kt`;
> `hexagon/application/build.gradle.kts`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileRunner.kt`;
> `adapters/driving/cli/build.gradle.kts`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt`;
> `adapters/driven/driver-postgresql/build.gradle.kts`;
> `adapters/driven/driver-mysql/build.gradle.kts`;
> `adapters/driven/driver-sqlite/build.gradle.kts`;
> die jeweiligen `profiling/`-Pakete unter
> `adapters/driven/driver-postgresql`,
> `adapters/driven/driver-mysql` und
> `adapters/driven/driver-sqlite`.

---

## 1. Ziel

Phase D loest die transitive Profiling-Kopplung aus den JDBC-
Treiberkernen, ohne den bestehenden Profiling-Feature-Vertrag fuer
`d-migrate data profile` zu brechen.

Der Teilplan beantwortet bewusst zuerst die Struktur- und
Wiring-Fragen:

- wie die drei Treiberkerne von `hexagon:profiling` befreit werden
- wie die bestehenden Profiling-Adapter in optionale Zusatzmodule
  ueberfuehrt werden
- wie das CLI-Wiring fuer `data profile` danach weiter funktioniert
- wie Tests, Kover und Build-Graph auf den neuen Zuschnitt angepasst
  werden
- wie dabei die in Phase A gehaerteten Profiling-/Introspection-
  Adapter unveraendert sicher bleiben

Phase D liefert damit keine neue Endnutzerfunktion, sondern einen
saubereren Modulzuschnitt fuer wiederverwendbare Treiberkerne.

Nach Phase D soll klar gelten:

- `driver-postgresql`, `driver-mysql` und `driver-sqlite` haengen nicht
  mehr an `hexagon:profiling`
- die Profiling-Adapter leben in optionalen, klar benannten
  Zusatzmodulen
- `DataProfileRunner` und das CLI konsumieren Profiling weiter ueber
  `ProfilingAdapterSet`, aber nicht mehr ueber die Treiberkerne
- ein Consumer fuer Schema/Data/DDL-Treiber zieht Profiling nicht mehr
  transitiv mit

---

## 2. Ausgangslage

Im aktuellen Repo ist die Profiling-Kopplung klar sichtbar:

- alle drei JDBC-Treiberkerne haengen heute direkt an
  `hexagon:profiling`:
  - `adapters/driven/driver-postgresql/build.gradle.kts`
  - `adapters/driven/driver-mysql/build.gradle.kts`
  - `adapters/driven/driver-sqlite/build.gradle.kts`
- die eigentlichen Profiling-Klassen liegen in allen drei Modulen
  bereits in eigenen `profiling/`-Paketen:
  - `*SchemaIntrospectionAdapter`
  - `*ProfilingDataAdapter`
  - `*LogicalTypeResolver`
- ausserhalb dieser `profiling/`-Pakete importieren die
  Treiber-Main-Sources aktuell keine `dev.dmigrate.profiling`-Typen
- die Treiberkerne enthalten ansonsten fachlich:
  - `DataReader`
  - `DataWriter`
  - `SchemaReader`
  - `TableLister`
  - `DdlGenerator`
  - JDBC-URL-, Type-Mapping- und Metadata-Helfer
- `hexagon:profiling` ist als eigenes Hexagon-Modul bereits sauber
  getrennt und definiert:
  - Profiling-Ports
  - Profiling-Modell
  - Profiling-Services
  - `ProfilingAdapterSet`
- `hexagon:application` haengt legitim an `hexagon:profiling`, weil
  `DataProfileRunner` direkt mit `ProfilingAdapterSet`,
  `ProfileDatabaseService` und `ProfilingException` arbeitet
- das CLI-Wiring fuer `data profile` erfolgt heute in
  `DataProfileCommand` ueber direkte Imports aus den drei
  Treibermodulen:
  - `PostgresSchemaIntrospectionAdapter`, `PostgresProfilingDataAdapter`,
    `PostgresLogicalTypeResolver`
  - `MysqlSchemaIntrospectionAdapter`, `MysqlProfilingDataAdapter`,
    `MysqlLogicalTypeResolver`
  - `SqliteSchemaIntrospectionAdapter`, `SqliteProfilingDataAdapter`,
    `SqliteLogicalTypeResolver`
- die Treibertests fuer Profiling liegen heute ebenfalls noch in den
  Kernmodulen unter `src/test/kotlin/.../profiling/`

Wichtig fuer die Bewertung:

- `ProfilingAdapterSet` wird bereits bewusst **nicht** ueber
  `DatabaseDriver` bereitgestellt
- der eigentliche Kopplungspunkt ist deshalb heute nicht die
  Portarchitektur des Profilings, sondern der Modulzuschnitt der
  Adapterimplementierungen
- die Extraktion ist damit kein Greenfield-Design, sondern vor allem
  ein Packaging- und Build-Refactor

Konsequenz:

- die Treiberkerne transportieren derzeit mehr transitive API als fuer
  Schema/Data/DDL noetig
- `d-browser` oder andere reine Lese-Consumer ziehen Profiling heute
  unnoetig mit
- das CLI-Wiring muss nach der Extraktion gezielt auf neue
  Profiling-Module zeigen

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Einfuehrung optionaler Zusatzmodule fuer die drei Profiling-Adapter-
  Saetze, z. B.:
  - `adapters:driven:driver-postgresql-profiling`
  - `adapters:driven:driver-mysql-profiling`
  - `adapters:driven:driver-sqlite-profiling`
- Verschiebung der bestehenden Profiling-Implementierungen aus den
  Treiberkernen in diese Zusatzmodule
- Verschiebung der zugehoerigen Profiling-Tests in die neuen Module
- Entfernung der direkten `hexagon:profiling`-Abhaengigkeit aus den
  Treiberkernmodulen
- Anpassung von `settings.gradle.kts` und betroffenen `build.gradle.kts`
- Anpassung des CLI-Wirings fuer `DataProfileCommand` auf die neuen
  Profiling-Module
- Build-/Verifikationsnachweis, dass die Treiberkerne danach kein
  `hexagon:profiling` mehr transitiv exportieren
- Kover-Coverage bleibt pro betroffenem Modul bei mindestens 90 %

### 3.2 Bewusst nicht Teil von Phase D

- Umgestaltung von `hexagon:profiling` selbst
- neuer Profiling-Port auf `DatabaseDriver`
- neuer Endnutzervertrag fuer `data profile`
- Umbau von `DataProfileRunner` auf einen anderen fachlichen Vertrag
- Port-/Optionsschnitt aus Phase C
- oeffentliche Publish-/Artifact-Strategie fuer 1.0.0

Praezisierung:

Phase D loest zuerst "wie werden Profiling-Implementierungen modular von
den Treiberkernen getrennt?", nicht "wie wird Profiling fachlich neu
entworfen?".

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 Treiberkerne bleiben bei Schema/Data/DDL

Verbindliche Entscheidung:

- die Kernmodule `driver-postgresql`, `driver-mysql` und
  `driver-sqlite` tragen nach Phase D nur noch:
  - Schema-Lesen
  - Data-Lesen
  - Data-Schreiben
  - DDL-Generierung
  - JDBC-URL- und Treiberhilfen
- Profiling-Implementierungen gehoeren nicht mehr in diese Kernmodule

Folge:

- `hexagon:profiling` verschwindet aus den Kernel-`build.gradle.kts`
- die Kernel sind danach fuer reine Lese-/Migrations-Consumer kleiner
  und klarer

### 4.2 Profiling bleibt ein separater Adapterpfad, nicht Teil von `DatabaseDriver`

Verbindliche Entscheidung:

- `ProfilingAdapterSet` bleibt der bestehende Gruppierungsvertrag fuer
  Profiling-Adapter
- Phase D fuehrt **kein** `databaseDriver().profiling()` oder
  gleichwertiges neues Treiber-Port-API ein
- CLI und andere Consumer looken Profiling weiter separat auf

Damit bleibt der bisher schon sinnvolle Fachschnitt erhalten.

### 4.3 Die neuen Profiling-Module sind optional, aber voll funktionsfaehig

Verbindliche Entscheidung:

- die neuen Zusatzmodule haengen auf `hexagon:profiling` und auf die
  benoetigten Treiber-/JDBC-Bausteine
- das CLI darf fuer `data profile` explizit von ihnen abhaengen
- andere Consumer muessen sie nur dann einbinden, wenn sie Profiling
  wirklich brauchen

Nicht zulaessig ist:

- die Kernmodule weiter als versteckte Transitquelle fuer Profiling zu
  benutzen
- die Profiling-Features still aus der CLI zu degradieren

### 4.4 Paket- und Klassennamen duerfen stabil bleiben

Verbindliche Entscheidung:

- die bestehenden Profiling-Klassen duerfen ihre Paketnamen
  (`dev.dmigrate.driver.<dialect>.profiling`) behalten, auch wenn sie
  in neue Gradle-Module wandern
- Ziel ist minimale Import- und Test-Churn bei maximal klarem
  Modulschnitt

Folge:

- der Refactor ist primaer ein Modulmove, nicht eine Umbenennung der
  API

### 4.5 Phase-A-Sicherheitsstand darf nicht regressieren

Verbindliche Entscheidung:

- die in Phase A gehaerteten Profiling-/Introspection-Adapter bleiben
  semantisch unveraendert
- der Modulmove darf Identifier-Quoting, Prepared-Statement-Binding
  und SQLite-PRAGMA-Absicherungen nicht versehentlich rueckbauen

### 4.6 `hexagon:application` darf weiter direkt an `hexagon:profiling` haengen

Verbindliche Entscheidung:

- die Entkopplung betrifft die Treiberkerne, nicht den
  Application-Layer
- `DataProfileRunner` und die Profiling-Services bleiben in ihrer
  heutigen Beziehung bestehen
- Phase D muss nicht versuchen, Profiling aus `hexagon:application`
  herauszudruecken

---

## 5. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

1. **5.1** zieht den Zielzuschnitt und die neuen Module fest
2. **5.2** verschiebt die Implementierungen und Tests
3. **5.3** passt CLI- und Build-Wiring an
4. **5.4** zieht Verifikation und Doku nach

### 5.1 Modulzuschnitt fuer optionale Profiling-Anbauten festziehen

- drei neue Gradle-Module anlegen:
  - `adapters:driven:driver-postgresql-profiling`
  - `adapters:driven:driver-mysql-profiling`
  - `adapters:driven:driver-sqlite-profiling`
- `settings.gradle.kts` entsprechend erweitern
- fuer jedes neue Modul den minimalen Abhaengigkeitsschnitt festlegen:
  - `hexagon:profiling`
  - `adapters:driven:driver-common`
  - das jeweilige Treiberkernmodul oder kleine gemeinsame Hilfen, falls
    benoetigt
  - die jeweilige JDBC-Library nur dort, wo der Compile-Schnitt es
    erfordert
- die Kernmodule von `hexagon:profiling` entkoppeln
- festhalten, welche Hilfsklassen fuer den Move sichtbar oder gemeinsam
  nutzbar bleiben muessen, z. B.:
  - `SqlIdentifiers`
  - `JdbcMetadataSession`
  - `JdbcOperations`

Ergebnis:

Ein klarer, buildbarer Zielgraph statt eines blossen Datei-Moves.

### 5.2 Profiling-Implementierungen und Tests in die neuen Module verschieben

- die folgenden Implementierungen pro Dialekt aus dem Kernmodul in das
  neue Profiling-Modul verschieben:
  - `*SchemaIntrospectionAdapter`
  - `*ProfilingDataAdapter`
  - `*LogicalTypeResolver`
- die zugehoerigen Tests aus den bisherigen Treiberkernmodulen
  mitverschieben
- Paketnamen nach Moeglichkeit unveraendert lassen
- Treiberkernmodule danach von Profiling-spezifischem Quellcode
  saeubern
- Kover-Konfiguration der Alt- und Neumodule auf den neuen Zuschnitt
  anpassen

Pragmatische Zusatzregel:

- wenn einzelne kleine Helper heute nur aus Profiling-Dateien genutzt
  werden, duerfen sie in das neue Profiling-Modul mitwandern
- wenn Helper zugleich Kern- und Profiling-Code bedienen, bleiben sie
  im gemeinsamen bzw. Kernbereich

Ergebnis:

Die Treiberkerne enthalten nur noch Kernfunktionalitaet; Profiling lebt
modular anhaengbar daneben.

### 5.3 CLI- und Runtime-Wiring auf die Profiling-Zusatzmodule umstellen

- `DataProfileCommand` importiert die Profiling-Adapter danach aus den
  neuen Zusatzmodulen statt aus den Treiberkernen
- `adapters/driving/cli/build.gradle.kts` haengt explizit an den neuen
  Profiling-Modulen
- das bestehende `adapterLookup`-Muster ueber `ProfilingAdapterSet`
  bleibt erhalten
- `DataProfileRunner` bleibt unveraendert oder nur minimal angepasst,
  weil sein Vertrag bereits sauber ist
- fuer produktive Distributionen bleibt `data profile` weiterhin
  enthalten; die Optionalitaet gilt fuer Library-Consumer, nicht fuer
  die CLI-Distribution

Ergebnis:

Das Profiling-Feature bleibt nutzbar, aber die Treiberkerne sind nicht
mehr sein Tragepfad.

### 5.4 Build-Verifikation, Konsumentenprobe und Doku nachziehen

- Build-Pruefungen einfuehren oder nachziehen, dass:
  - die drei Treiberkerne nicht mehr an `hexagon:profiling` haengen
  - die neuen Profiling-Module korrekt bauen
  - die CLI mit `data profile` weiter baut
- mindestens eine kleine Konsumentenprobe anlegen, die nur einen
  Treiberkern fuer Schema/Data/DDL nutzt und dabei kein
  `hexagon:profiling` transitiv braucht
- Doku nachziehen:
  - `docs/implementation-plan-0.9.1.md`
  - `docs/architecture.md`
  - `docs/hexagonal-port.md`
  - ggf. `docs/guide.md`, falls dort Profiling-Modulzuschnitt sichtbar
    beschrieben wird

Ergebnis:

Der neue Zuschnitt ist nicht nur im Code, sondern auch im Build- und
Integrationsnachweis sichtbar.

### 5.5 Grobe Aufwandseinschaetzung

- **Mittel** fuer den reinen Modul- und Build-Schnitt
- **Mittel** fuer das Umziehen der Profiling-Tests samt Kover-
  Nacharbeit
- **Niedrig bis mittel** fuer `DataProfileRunner`, weil dessen Vertrag
  bereits gut entkoppelt ist
- **Mittel** fuer CLI-Wiring und Konsumentenprobe

Die Unsicherheit liegt weniger in der Fachlogik als in Build-Graph,
Sichtbarkeiten und testbarer Transitivitaet.

---

## 6. Verifikation

Pflichtfaelle:

- Build-Checks, dass:
  - `driver-postgresql` kein `hexagon:profiling` mehr haengt
  - `driver-mysql` kein `hexagon:profiling` mehr haengt
  - `driver-sqlite` kein `hexagon:profiling` mehr haengt
- Unit- und Adaptertests der verschobenen Profiling-Klassen laufen in
  den neuen Zusatzmodulen gruen
- bestehende Profiling-CLI-Tests bleiben funktional gruen
- eine kleine Konsumentenprobe zeigt, dass ein Treiberkern ohne
  Profiling-Anbau konsumierbar bleibt
- Sicherheitsrelevante Profiling-Tests aus Phase A bleiben semantisch
  unveraendert

Erwuenschte Zusatzfaelle:

- eine compile-nahe Pruefung, dass die neuen Profiling-Module nur dort
  eingebunden werden, wo sie wirklich benoetigt sind
- ein schlanker Smoke-Test fuer `data profile` mit mindestens einem
  Dialekt im neuen Modulzuschnitt

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `settings.gradle.kts`
- `adapters/driven/driver-postgresql/build.gradle.kts`
- `adapters/driven/driver-mysql/build.gradle.kts`
- `adapters/driven/driver-sqlite/build.gradle.kts`
- neue Build-Dateien fuer:
  - `adapters/driven/driver-postgresql-profiling`
  - `adapters/driven/driver-mysql-profiling`
  - `adapters/driven/driver-sqlite-profiling`
- die jeweiligen `profiling/`-Pakete unter den drei Treibermodulen
- `adapters/driving/cli/build.gradle.kts`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt`

Wahrscheinlich mit betroffen:

- Kover- und Testkonfiguration der neuen und alten Module
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileRunner.kt`
  nur falls kleine Wiring-Anpassungen noetig werden
- Tests unter:
  - `adapters/driven/driver-postgresql/src/test/.../profiling`
  - `adapters/driven/driver-mysql/src/test/.../profiling`
  - `adapters/driven/driver-sqlite/src/test/.../profiling`
  - `adapters/driving/cli/src/test/...`
  - `hexagon/application/src/test/...`

Dokumentation:

- `docs/implementation-plan-0.9.1.md`
- `docs/architecture.md`
- `docs/hexagonal-port.md`
- ggf. weitere Nutzer- oder Architekturtexte mit Moduluebersicht

---

## 8. Risiken und offene Punkte

### 8.1 Ein reiner Datei-Move ohne sauberen Build-Schnitt waere nur Kosmetik

Wenn Klassen nur verschoben werden, die Kernmodule aber indirekt weiter
an `hexagon:profiling` haengen, ist das Phase-D-Ziel verfehlt.

Mitigation:

- Erfolg ueber den tatsaechlichen Abhaengigkeitsgraphen messen
- kein Abschluss ohne Build-Nachweis fuer alle drei Treiberkerne

### 8.2 Sichtbarkeiten gemeinsamer Helper koennen den Move erschweren

Profiling-Adapter nutzen heute gemeinsame Hilfen wie
`SqlIdentifiers` und `JdbcMetadataSession`. Je nach Sichtbarkeit kann
das zusaetzliche kleine Extraktionen oder API-Anpassungen ausloesen.

Mitigation:

- benoetigte gemeinsame Hilfen frueh inventarisieren
- nur kleine, echte Reuse-Helfer im Kern lassen; Profiling-spezifische
  Hilfen mitbewegen

### 8.3 Kover und Tests koennen durch den Modulsplit unerwartet kippen

Das Umziehen der Profiling-Tests veraendert Modulgrenzen, Excludes und
Coverage-Zaehlung.

Mitigation:

- Testmove und Kover-Nacharbeit als Teil des Refactors behandeln, nicht
  als spaetere Aufraeumarbeit

### 8.4 CLI-Wiring kann still brechen, obwohl die Kernmodule sauber sind

`DataProfileCommand` importiert heute die konkreten Profiling-Adapter
direkt. Ein sauberer Kernschnitt nuetzt wenig, wenn das produktive
Profiling-Wiring danach nicht mehr baut oder nur teilweise verdrahtet
ist.

Mitigation:

- `DataProfileCommand` explizit in den Phase-D-Scope aufnehmen
- mindestens einen CLI-nahen Smoke-Test behalten

### 8.5 Optionalitaet darf nicht mit Feature-Abbau verwechselt werden

Die neuen Profiling-Module sind fuer Library-Consumer optional, fuer die
CLI aber weiterhin produktiv noetig.

Mitigation:

- im Plan ausdruecklich zwischen Library-Konsum und CLI-Distribution
  unterscheiden
- Release-/CLI-Build weiter mit Profiling-Modulen bauen

---

## 9. Entscheidungsempfehlung

Phase D sollte in 0.9.1 umgesetzt werden, weil sie einen der klarsten
technischen Integrationsknoten aus dem `d-browser`-Assessment loest:
Treiberkerne werden ohne Profiling transitiv konsumierbar.

Empfohlener Zuschnitt:

1. drei optionale Profiling-Zusatzmodule pro Dialekt einfuehren
2. Profiling-Implementierungen und Tests dorthin verschieben
3. direkte `hexagon:profiling`-Abhaengigkeit aus den Treiberkernen
   entfernen
4. CLI-Wiring fuer `data profile` auf die neuen Module umstellen
5. den neuen Zuschnitt ueber Build-Checks und mindestens eine kleine
   Konsumentenprobe absichern

Damit liefert Phase D einen klareren Treiberzuschnitt, ohne Profiling
fachlich neu zu entwerfen oder den bestehenden Nutzervertrag von
`data profile` zu veraendern.
