# Qualität

## Statische Analyse (detekt)

Das Projekt nutzt [detekt](https://detekt.dev/) als Build-Gate für statische
Code-Analyse. detekt läuft automatisch als Teil von `./gradlew build` (und
damit bei jedem `docker build .`).

### Konfiguration

| Datei                            | Zweck                                              |
| -------------------------------- | -------------------------------------------------- |
| `config/detekt/detekt.yml`       | Regelwerk mit projektspezifischen Schwellenwerten  |
| `<modul>/detekt-baseline.xml`    | Bestehende Violations pro Modul (werden toleriert) |
| `build.gradle.kts` (Zeile 39-43) | Plugin-Setup für alle Submodule                    |

### Wie es wirkt

- **Neuer Code** muss alle Regeln in `detekt.yml` einhalten. Verstöße brechen
  den Build.
- **Bestehende Violations** sind in den `detekt-baseline.xml`-Dateien erfasst
  und werden vom Build ignoriert. Sie können inkrementell abgebaut werden.

### Baselines aktualisieren

| Situation                     | Baseline-Update nötig?                                              |
| ----------------------------- | ------------------------------------------------------------------- |
| Neuen Code schreiben          | Nein -- detekt prüft neuen Code unabhängig von Baselines            |
| Bestehende Violation fixen    | **Ja** -- damit die Baseline schrumpft und der Fix geschützt bleibt |
| Regeln in `detekt.yml` ändern | **Ja** -- neue Regeln/Schwellenwerte ändern die Violation-Menge     |
| Normaler Commit ohne Cleanup  | Nein                                                                |

```bash
find . -name "detekt-baseline.xml" -not -path "./.gradle/*" -delete
docker build --target detekt-baseline -t d-migrate:detekt-baseline .
docker run --rm d-migrate:detekt-baseline | tar xf -
```

Zeile 1 löscht alle bestehenden Baselines, damit stale Einträge (z. B.
für umbenannte oder aufgeteilte Klassen) nicht mitgeschleppt werden.
Zeile 2 generiert die Baselines per Gradle im Docker-Container. Zeile 3
streamt ein tar-Archiv mit allen `<modul>/detekt-baseline.xml`-Dateien nach
stdout und entpackt es direkt ins Arbeitsverzeichnis -- die Pfade im Archiv
entsprechen der Projektstruktur. Wurden alle Violations gefixt, erzeugt
der Schritt keine Baseline-Dateien und der Build läuft ohne Baselines.

### Regeln anpassen

Schwellenwerte und Regel-Toggles stehen in `config/detekt/detekt.yml`.
Nach Änderungen am Regelwerk die Baselines regenerieren (s. o.), da sich
die Violation-Menge ändern kann.

## Tests

### Tag-Steuerung

Tests sind über Kotest-Tags kategorisiert. Der Gradle-Build filtert
automatisch je nach Modus:

| Modus | Befehl | Tag-Filter |
|---|---|---|
| Unit-Tests (Default) | `docker build .` | `!integration & !perf` |
| Integration-Tests | `./scripts/test-integration-docker.sh` | `!perf` |
| Perf-Tests (opt-in) | `-Dkotest.tags=perf` | nur `perf` |
| Expliziter Filter | `-Dkotest.tags='...'` | wie angegeben |

Perf-Tests (`perf`-Tag) prüfen Memory-Budgets mit großen Fixtures
(100 MB JSON, 100k YAML) und laufen nie automatisch. Ergebnisse und
Entscheidungen sind in `docs/perf/` dokumentiert (z. B.
`0.4.0-phase-d-reorder.md`). Manueller Start:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test -Dkotest.tags=perf -PtestMaxHeapSize=4g" \
  -t d-migrate:perf .
```

### Integrations-Tests

Integrations-Tests (`integration`-Tag) benötigen Docker (Testcontainers)
und laufen über das Skript:

```bash
./scripts/test-integration-docker.sh
```

Der Output wird zusätzlich in eine Log-Datei geschrieben
(`/tmp/d-migrate-integration-*.log`, konfigurierbar über
`DMIGRATE_TEST_LOG`).

## KI Prompt

Analysiere den Code hinsichtlich seiner Qualität.

Bitte bewerte die folgenden Metriken auf einer Skala von 1-10 und begründe die Bewertung:
- Lesbarkeit & Namensgebung: (Sind Variablen/Funktionen klar benannt?)
- Modularität & Struktur: (Zu komplex? Solid-Prinzipien eingehalten?)
- Wartbarkeit: (Ist der Code leicht erweiterbar?)
- Sicherheit: (Gibt es potenzielle Schwachstellen, z. B. Injection-Risiken?)

Zusatzaufgaben:
- Schlage konkrete Verbesserungen vor, um die Qualität zu erhöhen.

Das Ergebnis im Markdown-Format darstellen und wenn möglich Tabellen verwenden.

## Result 
Gesamtüberblick                  
 
┌──────────────────────┬──────────────────────────────────┐
│      Kenngröße       │               Wert               │
├──────────────────────┼──────────────────────────────────┤
│ Module               │ 18 Gradle-Module                 │
├──────────────────────┼──────────────────────────────────┤
│ Produktivcode        │ ~480 Kotlin-Dateien, ~29.000 LOC │
├──────────────────────┼──────────────────────────────────┤
│ Architektur          │ Hexagonal (Ports & Adapters)     │
├──────────────────────┼──────────────────────────────────┤
│ Kotlin/JDK           │ 2.1.20 / 21                      │
├──────────────────────┼──────────────────────────────────┤
│ Coverage-Anforderung │ 90% (CLI: 80%)                   │
└──────────────────────┴──────────────────────────────────┘
                
---    
Metrik-Bewertung
                
1. Lesbarkeit & Namensgebung: 8/10
 
┌───────────────────────────────────────┬───────────────────────────────────────────────────────────────────────────┐ 
│                Stärke                 │                                 Beispiel                                  │ 
├───────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────┤ 
│ Konsistentes Dialect-Prefix-Muster    │ MysqlDriver, PostgresDriver, SqliteDriver                                 │
├───────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────┤
│ Parallele Klassenstruktur pro Treiber │ {Dialect}DataReader, {Dialect}TypeMapper, {Dialect}SchemaReader           │ 
├───────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────┤ 
│ Aussagekräftige Sealed Classes        │ NeutralType.Decimal(precision, scale), DefaultValue.SequenceNextVal(name) │ 
├───────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────┤ 
│ Kompakte Domain-Modelle               │ SchemaDefinition (17 Zeilen), ColumnDefinition (9 Zeilen)                 │
├───────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────┤ 
│ DSL-artige Test-Fixtures              │ schema(), table(), col()                                                  │
└───────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────┘ 
                
┌───────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────┐
│             Schwäche              │                                     Detail                                     │
├───────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────┤
│ Parameter-Inkonsistenz            │ MySQL nutzt database, PostgreSQL schema für semantisch gleiches Konzept        │
├───────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────┤
│ Test-Dateinamen                   │ SchemaComparatorTestPart2Part2b.kt — unklar, nach Größe statt Thema gesplittet │
├───────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────┤
│ Vereinzelt unklare Funktionsnamen │ lowerCaseTableNames() in MysqlIdentifiers — Query oder Config?                 │
└───────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────┘
                
---    
2. Modularität & Struktur: 9/10
      
┌───────────────────────┬───────────┬──────────────────────────────────────────────────────────────────────────────────────────┐
│     SOLID-Prinzip     │ Bewertung │                                        Begründung                                        │                
├───────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────┤
│ Single Responsibility │ Exzellent │ Validators, Comparators, Codecs jeweils fokussiert                                       │                
├───────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────┤
│ Open/Closed           │ Gut       │ Sealed Classes ermöglichen exhaustive Pattern-Matches; SchemaComparator hat Wiederholung │                
├───────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────┤                
│ Liskov Substitution   │ Exzellent │ NeutralType-Hierarchie sauber substituierbar                                             │                
├───────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────┤                
│ Interface Segregation │ Gut       │ Ports in 3 Module aufgeteilt (common, read, write)                                       │
├───────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────┤                
│ Dependency Inversion  │ Exzellent │ Core hat null externe Abhängigkeiten; Gradle erzwingt Schichtung                         │
└───────────────────────┴───────────┴──────────────────────────────────────────────────────────────────────────────────────────┘                
                
Architektur-Highlights:          
- Core-Modul ist komplett frei von externen Dependencies (nur Kotlin stdlib)
- Gradle-Abhängigkeiten garantieren die hexagonale Schichtung                        
- Application-Layer hängt nur von Core + Ports ab, nie von Adaptern
- CLI-Commands sind dünne Shells, die an testbare Runner-Klassen delegieren          
 
Strukturelle Schwäche — Duplikation in MetadataQueries:    
MySQL (543 LOC), PostgreSQL (376 LOC) und SQLite (112 LOC) implementieren fast identische Methoden (listTableRefs, listColumns, listForeignKeys, etc.) mit minimalen Dialekt-Unterschieden. ~1.000 Zeilen könnten auf ~400 reduziert werden.                   
 
---    
3. Wartbarkeit: 8/10             
                
┌──────────────────────────────────┬─────────────────────────────────────────────────────────────────────────┐
│              Stärke              │                                 Detail                                  │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Dependency Injection durchgängig │ Runner-Klassen sind vollständig injizierbar → jeder Fehlerpfad testbar  │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Immutable DTOs                   │ Request/Response als data class mit Defaults                            │        
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤        
│ Exit-Code-Strategie              │ 7-stufig: 0=OK, 2=CLI, 3=Preflight, 4=Connection, 5=Streaming, 7=Config │        
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤        
│ Idempotente Ressourcen-Freigabe  │ close() mit Guard-Flags in allen Writer/Reader-Klassen                  │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤        
│ State Machine in Import          │ OPEN → WRITTEN → FINISHED → CLOSED, mit FAILED als Terminal-State       │
├──────────────────────────────────┼─────────────────────────────────────────────────────────────────────────┤        
│ Checkpoint/Resume                │ Streaming-Pipeline mit YAML-Checkpoints und Resume-Fähigkeit            │
└──────────────────────────────────┴─────────────────────────────────────────────────────────────────────────┘        
                
┌──────────────────────────────┬───────────────────────────────────────────────────────────────────────────────┐      
│           Schwäche           │                                    Detail                                     │
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ SchemaComparator-Repetition  │ 6× fast identisches Muster für verschiedene Schema-Objekte (~150 LOC sparbar) │
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤
│ Type-Mapper-Tests dupliziert │ MysqlTypeMapperTest vs PostgresTypeMapperTest — ~350 LOC auf ~150 reduzierbar │      
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────┤      
│ Kein Structured Logging      │ Direkte System.err.println()-Aufrufe statt log-level-basiertem Logging        │      
└──────────────────────────────┴───────────────────────────────────────────────────────────────────────────────┘      
                
---    
4. Sicherheit: 8/10
                    
┌───────────────────────────┬───────────┬──────────────────────────────────────────────────────────────┐
│          Bereich          │ Bewertung │                            Detail                            │              
├───────────────────────────┼───────────┼──────────────────────────────────────────────────────────────┤
│ SQL-Injection-Schutz      │ Sehr gut  │ Durchgängig PreparedStatement mit Parameter-Binding          │              
├───────────────────────────┼───────────┼──────────────────────────────────────────────────────────────┤
│ Identifier-Quoting        │ Exzellent │ Zentralisiert in SqlIdentifiers.kt, dialekt-bewusst (" vs `) │              
├───────────────────────────┼───────────┼──────────────────────────────────────────────────────────────┤              
│ Env-Variable-Substitution │ Gut       │ ${VAR} vs $${VAR}-Escaping korrekt implementiert             │              
├───────────────────────────┼───────────┼──────────────────────────────────────────────────────────────┤              
│ Connection-Pool           │ Gut       │ HikariCP mit SQLite-Sonderbehandlung (poolSize=1)            │
└───────────────────────────┴───────────┴──────────────────────────────────────────────────────────────┘              
                
┌─────────────────────────────────────┬─────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐   
│               Risiko                │ Schwere │             Detail             │
├─────────────────────────────────────┼─────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ SQLite PRAGMA String-Interpolation  │ Mittel  │ SqliteMetadataQueries.kt:35,50,57,73,78 — nutzt lokales escapeSql() statt zentrales SqlIdentifiers.quoteStringLiteral(). SQLite PRAGMA unterstützt kein Parameter-Binding, aber die lokale               │
│                                     │         │ Escape-Implementierung ist fehleranfälliger.                                                                        │   
├─────────────────────────────────────┼─────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ DefaultValue.FunctionCall           │ Niedrig │ Alle TypeMapper fallen auf "${default.name}()" zurück — keine Validierung des Funktionsnamens (Werte stammen aber aus Schema-Metadaten)                                                                  │
│ unvalidiert                         │         │                                │   
├─────────────────────────────────────┼─────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Stille Exception-Unterdrückung      │ Niedrig │ HikariConnectionPoolFactory:56 — catch (_: IllegalArgumentException) verschluckt Driver-Registrierungs-Fehler ohne Logging                                                                               │   
└─────────────────────────────────────┴─────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘   

---    
Zusammenfassung 
                
┌───────────────────────────┬────────┬───────────────────────────────────────────────────┐
│          Metrik           │ Score  │                       Trend                       │  
├───────────────────────────┼────────┼───────────────────────────────────────────────────┤
│ Lesbarkeit & Namensgebung │ 8/10   │ Konsistent, vereinzelt Inkonsistenzen             │  
├───────────────────────────┼────────┼───────────────────────────────────────────────────┤
│ Modularität & Struktur    │ 9/10   │ Vorbildliche Hexagonal-Architektur                │  
├───────────────────────────┼────────┼───────────────────────────────────────────────────┤  
│ Wartbarkeit               │ 8/10   │ Hohe Testbarkeit, etwas Duplikation               │  
├───────────────────────────┼────────┼───────────────────────────────────────────────────┤  
│ Sicherheit                │ 8/10   │ Durchgängig parametrisiert, ein SQLite-Sonderfall │
├───────────────────────────┼────────┼───────────────────────────────────────────────────┤  
│ Gesamt                    │ 8.3/10 │ Produktionsreife, professionelle Codebasis        │
└───────────────────────────┴────────┴───────────────────────────────────────────────────┘  
                
---    
Konkrete Verbesserungsvorschläge
      
┌─────┬───────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────┬─────────────────────────────────────────┬──────────────────────────────┐
│  #  │ Priorität │                                                Vorschlag                                                 │           Betroffene Dateien            │      Geschätzter Effekt      │    
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤
│ 1   │ Hoch      │ SQLite PRAGMA: lokales escapeSql() durch SqlIdentifiers.quoteStringLiteral() ersetzen                    │ SqliteMetadataQueries.kt                │ Zentralisierte Sicherheit    │    
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤
│ 2   │ Hoch      │ Generische Vergleichsmethode in SchemaComparator extrahieren (z.B. compareObjectMaps<T>())               │ SchemaComparator.kt                     │ ~150 LOC weniger Duplikation │    
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 3   │ Hoch      │ MetadataQueries: Gemeinsame Basis-Logik in driver-common extrahieren, nur SQL-Strings dialekt-spezifisch │ Mysql/Postgres/SqliteMetadataQueries.kt │ ~600 LOC weniger Duplikation │    
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 4   │ Mittel    │ Parameter-Naming vereinheitlichen: schemaName statt database/schema Wechsel                              │ Alle MetadataQueries                    │ Konsistenz                   │
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 5   │ Mittel    │ Test-Dateien nach Thema statt Größe splitten (z.B. SchemaComparatorConstraintTest.kt)                    │ *TestPart2*.kt                          │ Bessere Auffindbarkeit       │
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 6   │ Mittel    │ TypeMapper-Tests parametrisieren (data-driven) statt pro Dialekt zu duplizieren                          │ *TypeMapperTest.kt                      │ ~200 LOC gespart             │
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 7   │ Mittel    │ Structured Logging einführen (SLF4J ist bereits Dependency) statt System.err.println()                   │ CLI-Adapter                             │ Log-Level-Steuerung          │
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 8   │ Niedrig   │ HikariConnectionPoolFactory:56 — Driver-Registrierungs-Fehler loggen statt verschlucken                  │ HikariConnectionPoolFactory.kt          │ Bessere Fehlerdiagnose       │
├─────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────┼─────────────────────────────────────────┼──────────────────────────────┤    
│ 9   │ Niedrig   │ Monetäre Spalten-Erkennung in SchemaValidator präzisieren (_price, _amount statt Substring-Match)        │ SchemaValidator.kt:275-278              │ Weniger False Positives      │
└─────┴───────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────┴─────────────────────────────────────────┴──────────────────────────────┘    
                
---    
Fazit: Die Codebasis ist architektonisch vorbildlich — die hexagonale Schichtung wird durch Gradle-Abhängigkeiten erzwungen, Domain-Modelle sind schlank und immutabel, und die Testabdeckung liegt bei einem Test/Source-Ratio von 1.7x. Die Haupthebel für
weitere Qualitätssteigerung liegen bei der Reduktion von MetadataQueries-Duplikation und der Zentralisierung des SQLite-PRAGMA-Escapings.  