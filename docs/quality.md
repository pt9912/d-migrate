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

| Modus                | Befehl                                 | Tag-Filter             |
| -------------------- | -------------------------------------- | ---------------------- |
| Unit-Tests (Default) | `docker build .`                       | `!integration & !perf` |
| Integration-Tests    | `./scripts/test-integration-docker.sh` | `!perf`                |
| Perf-Tests (opt-in)  | `-Dkotest.tags=perf`                   | nur `perf`             |
| Expliziter Filter    | `-Dkotest.tags='...'`                  | wie angegeben          |

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
Gesamtübersicht                                                                                                                                                                                                    
                                                                                                                                                                                                                     
  ┌───────────────────────────┬───────────┬─────────────────────────────────────────────────────┐                                                                                                                    
  │          Metrik           │ Bewertung │                       Tendenz                       │
  ├───────────────────────────┼───────────┼─────────────────────────────────────────────────────┤                                                                                                                    
  │ Lesbarkeit & Namensgebung │   9/10    │ Durchgehend stark                                   │
  ├───────────────────────────┼───────────┼─────────────────────────────────────────────────────┤
  │ Modularität & Struktur    │   7/10    │ Architektur exzellent, SOLID-Verletzungen im Detail │                                                                                                                    
  ├───────────────────────────┼───────────┼─────────────────────────────────────────────────────┤                                                                                                                    
  │ Wartbarkeit               │   8/10    │ Gut erweiterbar, einige God-Class-Kandidaten        │                                                                                                                    
  ├───────────────────────────┼───────────┼─────────────────────────────────────────────────────┤                                                                                                                    
  │ Sicherheit                │   9/10    │ Keine kritischen Schwachstellen                     │
  └───────────────────────────┴───────────┴─────────────────────────────────────────────────────┘                                                                                                                    
                                                            
  ---
  1. Lesbarkeit & Namensgebung — 9/10
                                                                                                                                                                                                                     
  Stärken:
                                                                                                                                                                                                                     
  ┌────────────────────┬───────────┬────────────────────────────────────────────────────────────────────────────────────────┐
  │       Aspekt       │ Bewertung │                                       Beispiele                                        │                                                                                        
  ├────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────────────────────┤
  │ Klassen-Benennung  │ Exzellent │ SchemaComparator, ViewDependencyResolver, FilterDslParser — Intention sofort klar      │
  ├────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────────────────────┤
  │ Methoden-Benennung │ Exzellent │ streamTable(), generateRollback(), resolveSequenceDefault() — verb-basiert, konsistent │                                                                                        
  ├────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────────────────────┤                                                                                        
  │ Properties         │ Exzellent │ Plural für Collections (tables, columns), Adjektive für Booleans (required, unique)    │                                                                                        
  ├────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────────────────────┤                                                                                        
  │ Test-Naming        │ Exzellent │ Narrative Form: "W001: FLOAT column with monetary name generates warning"              │
  ├────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────────────────────┤                                                                                        
  │ Sealed Classes     │ Exzellent │ FilterDslParseResult.Success/Failure — algebraisch sauber                              │
  └────────────────────┴───────────┴────────────────────────────────────────────────────────────────────────────────────────┘                                                                                        
                                                            
  Einziger Abzug: Die *TestPart2.kt / *TestPart2Part2b.kt-Namensgebung bei 27 Test-Dateien ist nicht semantisch.                                                                                                     
                                                            
  ---                                                                                                                                                                                                                
  2. Modularität & Struktur — 7/10                          
                                  
  Hexagonale Architektur: Exzellent (9.5/10)
                                                                                                                                                                                                                     
  hexagon/core          (Domain-Modelle, keine Abhängigkeiten)
     ↓                                                                                                                                                                                                               
  hexagon/ports-common  (Shared Port-Typen)                 
     ↓                                                                                                                                                                                                               
  hexagon/ports-read    hexagon/ports-write                 
     ↓                     ↓                                                                                                                                                                                         
  hexagon/application   (CLI Runner, Use Cases)             
     ↓                                                                                                                                                                                                               
  adapters/driving/cli  adapters/driven/driver-*
                                                                                                                                                                                                                     
  - Keine zirkulären Abhängigkeiten                         
  - Kein Adapter importiert von einem anderen Adapter                                                                                                                                                                
  - Port/Adapter-Grenzen konsequent eingehalten                                                                                                                                                                      
                                                                                                                                                                                                                     
  SOLID-Verletzungen: Verbesserungswürdig (6/10)                                                                                                                                                                     
                                                                                                                                                                                                                     
  ┌─────────┬─────────┬────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────┐                                                           
  │ Prinzip │ Schwere │           Fundstelle           │                                             Problem                                             │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ SRP     │ Hoch    │ ValueDeserializer.kt (586 LOC) │ 20+ Typ-Konvertierungen in einer Klasse; dispatch() ist ein Monolith                            │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ SRP     │ Hoch    │ FilterDslParser.kt (562 LOC)   │ Tokenizer + Parser + Fehlerbehandlung in einem Object                                           │                                                           
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ SRP     │ Hoch    │ DataImportRunner.kt (477 LOC)  │ CLI-Validierung, Format-Auflösung, Checkpoint-Management, Streaming-Execution in einer Klasse   │                                                           
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ OCP     │ Hoch    │ DataImportRunner.kt:202-217    │ Harte Dialekt-Checks (dialect == DatabaseDialect.POSTGRESQL) statt Capability-Interface         │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ LSP     │ Hoch    │ MysqlSchemaSync.kt             │ disableTriggers() wirft UnsupportedTriggerModeException statt den Interface-Vertrag zu erfüllen │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ ISP     │ Mittel  │ SchemaSync.kt                  │ Mischt Sequence-Reseeding mit Trigger-Management — MySQL kann nur Ersteres                      │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ ISP     │ Mittel  │ DatabaseDriver.kt              │ Fat Interface (DDL + DataRead + DataWrite + TableLister + UrlBuilder + SchemaReader)            │
  ├─────────┼─────────┼────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤                                                           
  │ DIP     │ Mittel  │ Main.kt:26-28                  │ Harte Driver-Registrierung ohne Plugin-Mechanismus                                              │
  └─────────┴─────────┴────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────┘                                                           
                                                            
  ---                                                                                                                                                                                                                
  3. Wartbarkeit — 8/10                                     

  Stärken:

  ┌─────────────────────────────────┬───────────┬──────────────────────────────────────────────────────────────────────────────────────┐                                                                             
  │             Aspekt              │ Bewertung │                                       Details                                        │
  ├─────────────────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────┤                                                                             
  │ Erweiterbarkeit (neuer Dialekt) │ Gut       │ AbstractDdlGenerator + TypeMapper-Interface ermöglichen schrittweise Implementierung │
  ├─────────────────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────┤
  │ Exception-Hierarchie            │ Exzellent │ Domänen-spezifisch, saubere Exit-Code-Zuordnung (0-7), kein printStackTrace()        │                                                                             
  ├─────────────────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────┤                                                                             
  │ Test-Abdeckung                  │ Exzellent │ Kover-enforced pro Modul, umfangreiche Unit- und Integrationstests                   │                                                                             
  ├─────────────────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────┤                                                                             
  │ Immutability                    │ Exzellent │ Kotlin Data Classes durchgängig, minimale mutable State                              │
  ├─────────────────────────────────┼───────────┼──────────────────────────────────────────────────────────────────────────────────────┤                                                                             
  │ Dokumentation                   │ Sehr gut  │ KDoc-Header, Block-Kommentare für Algorithmen, Cleanup-Verträge dokumentiert         │
  └─────────────────────────────────┴───────────┴──────────────────────────────────────────────────────────────────────────────────────┘                                                                             
                                                            
  Schwächen:                                                                                                                                                                                                         
                                                            
  ┌────────────────────────────┬─────────────────────────────────────────────────┬─────────────────────────────────────────────────────┐                                                                             
  │           Aspekt           │                     Problem                     │                     Auswirkung                      │
  ├────────────────────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────────────────┤                                                                             
  │ Template Method Bloat      │ AbstractDdlGenerator hat 20+ abstrakte Methoden │ Neuer Dialekt muss alles implementieren             │
  ├────────────────────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────────────────┤
  │ God-Class-Kandidaten       │ 3 Klassen > 500 LOC                             │ Änderungen an einem Concern riskieren Seiteneffekte │                                                                             
  ├────────────────────────────┼─────────────────────────────────────────────────┼─────────────────────────────────────────────────────┤                                                                             
  │ Starre Trigger-Architektur │ Feature-Checks per Enum statt Capabilities      │ Neues Feature erfordert Änderungen an Runners       │                                                                             
  └────────────────────────────┴─────────────────────────────────────────────────┴─────────────────────────────────────────────────────┘                                                                             
                                                            
  ---                                                                                                                                                                                                                
  4. Sicherheit — 9/10                                      
                      
  Keine kritischen Schwachstellen gefunden.
                                                                                                                                                                                                                     
  ┌─────────────────┬────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │   Prüfbereich   │ Status │                                                                 Details                                                                  │                                            
  ├─────────────────┼────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ SQL Injection   │ Sicher │ Durchgehend Prepared Statements mit ?-Platzhaltern; SqlIdentifiers.quoteStringLiteral() für PRAGMA-Argumente                             │
  ├─────────────────┼────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ Path Traversal  │ Sicher │ ArtifactRelativePath validiert: normalize(), rejektiert .., rejektiert absolute Pfade; Resume-Checkpoints validieren startsWith(baseDir) │                                            
  ├─────────────────┼────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                            
  │ Deserialization │ Sicher │ Jackson: FAIL_ON_READING_DUP_TREE_KEY aktiv, kein enableDefaultTyping(); SnakeYAML: YAML-Aliases explizit rejektiert                     │                                            
  ├─────────────────┼────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                            
  │ Credentials     │ Sicher │ ConnectionConfig.toString() maskiert Passwörter als ***; keine Secrets in Logs                                                           │
  ├─────────────────┼────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                            
  │ Dependencies    │ Sicher │ Alle Versionen aktuell (Jackson 2.21.2, Kotlin 2.1.20, HikariCP 6.2.1); nur Maven Central                                                │
  └─────────────────┴────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘                                            
                                                            
  Einzige theoretische Risiken (Low):                                                                                                                                                                                
                                                            
  ┌─────────────────────────────────────────────────────────────────┬─────────────────────────────────┬─────────────────────────────────────────────────────────┐                                                    
  │                             Risiko                              │               Ort               │                        Bewertung                        │
  ├─────────────────────────────────────────────────────────────────┼─────────────────────────────────┼─────────────────────────────────────────────────────────┤                                                    
  │ Partition-Boundary-Werte werden unvalidiert in DDL interpoliert │ PostgresDdlGenerator.kt:166-167 │ Low — Quelle ist geparste Schema-Datei, kein User-Input │
  ├─────────────────────────────────────────────────────────────────┼─────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ CHECK-Constraint-Ausdrücke direkt in DDL übernommen             │ PostgresDdlGenerator.kt:55      │ Low — Aus Schema-Definition, nicht extern               │                                                    
  └─────────────────────────────────────────────────────────────────┴─────────────────────────────────┴─────────────────────────────────────────────────────────┘                                                    
                                                                                                                                                                                                                     
  ---                                                                                                                                                                                                                
  Konkrete Verbesserungsvorschläge                          
                                  
  Priorität 1 — Hoher Impact
                                                                                                                                                                                                                     
  ┌─────┬───────────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────────┬───────────────────────────────────────────────────────────────┐   
  │  #  │                                         Vorschlag                                         │             Betroffene Dateien             │                       Erwarteter Effekt                       │   
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤   
  │ 1   │ ValueDeserializer aufspalten: Typ-Konverter in TypeConverter<T>-Interface + Registry      │ ValueDeserializer.kt                       │ 586 → ~150 LOC pro Klasse; neue Typen ohne Monolith-Änderung  │
  │     │ extrahieren                                                                               │                                            │                                                               │
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤   
  │ 2   │ DialectCapabilities-Interface einführen: supportsTriggerMode(), supportsDisableFkChecks() │ DataImportRunner.kt, SchemaSync.kt,        │ Eliminiert alle dialect == DatabaseDialect.X-Checks aus       │   
  │     │  statt Enum-Checks                                                                        │ DataWriter.kt                              │ Application-Layer                                             │   
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤   
  │ 3   │ SchemaSync splitten: SequenceReseedStrategy + TriggerManagementStrategy                   │ SchemaSync.kt, MysqlSchemaSync.kt          │ LSP-Verletzung behoben; MySQL implementiert nur was es kann   │
  └─────┴───────────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────────┴───────────────────────────────────────────────────────────────┘   
  
  Priorität 2 — Mittlerer Impact                                                                                                                                                                                     
                                                            
  ┌─────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────┬─────────────────────────────────────────────────────────┐   
  │  #  │                                                Vorschlag                                                │         Betroffene Dateien         │                    Erwarteter Effekt                    │
  ├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ 4   │ FilterDslParser trennen: Separater FilterDslTokenizer + FilterDslParser                                 │ FilterDslParser.kt                 │ Tokenizer ~100 LOC, Parser ~250 LOC; unabhängig testbar │
  ├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ 5   │ DataImportRunner entflechten: Checkpoint-Logic in CheckpointCoordinator, Validierung in ImportValidator │ DataImportRunner.kt                │ 477 → ~200 LOC Runner + fokussierte Helfer              │   
  ├─────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼─────────────────────────────────────────────────────────┤   
  │ 6   │ ServiceLoader für Driver-Registrierung statt hartem register() in Main.kt                               │ Main.kt, DatabaseDriverRegistry.kt │ Neuer Dialekt ohne Main-Änderung                        │   
  └─────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────┴─────────────────────────────────────────────────────────┘   
                                                            
  Priorität 3 — Nice-to-Have                                                                                                                                                                                         
                                                            
  ┌─────┬───────────────────────────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────┬──────────────────────────────────────────────────────────┐ 
  │  #  │                                               Vorschlag                                               │         Betroffene Dateien         │                    Erwarteter Effekt                     │ 
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼──────────────────────────────────────────────────────────┤    
  │ 7   │ AbstractDdlGenerator Template-Methoden gruppieren: TableDdlStrategy, ViewDdlStrategy etc. als         │ AbstractDdlGenerator.kt            │ Reduziert Kopplung; neuer Dialekt muss nicht 20+         │ 
  │     │ Composition statt Inheritance                                                                         │                                    │ Methoden overriden                                       │    
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼──────────────────────────────────────────────────────────┤    
  │ 8   │ DatabaseDriver Interface verschlanken: Separate DatabaseReadDriver / DatabaseWriteDriver              │ DatabaseDriver.kt                  │ Minimale Implementierungen möglich (z.B.                 │ 
  │     │                                                                                                       │                                    │ Read-Only-Profiler)                                      │    
  ├─────┼───────────────────────────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────┼──────────────────────────────────────────────────────────┤
  │ 9   │ FAIL_ON_UNKNOWN_PROPERTIES aktivieren in Jackson-Konfiguration                                        │ JsonSchemaCodec.kt,                │ Strikte Deserialisierung, frühere Fehlererkennung        │    
  │     │                                                                                                       │ YamlSchemaCodec.kt                 │                                                          │
  └─────┴───────────────────────────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────┴──────────────────────────────────────────────────────────┘    
                                                            