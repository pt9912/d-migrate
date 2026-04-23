# Refactor 1: Mikro-Komplexitaet und MySQL Secure Defaults

> **Status**: Offen
> **Prioritaet**: Mittel-Hoch
> **Erstellt**: 2026-04-23

---

## Problem

Es gibt aktuell zwei getrennte, aber zusammenhaengende Technik-Themen:

1. Einige zentrale Produktionsklassen haben zu viel Verantwortung gebuendelt.
2. Die MySQL-JDBC-Defaults setzen `allowPublicKeyRetrieval=true` als
   Standardwert und sind damit nicht maximal konservativ.

Der erste Punkt verschlechtert Lesbarkeit, Reviewbarkeit und zielgerichtete
Tests. Der zweite Punkt ist kein akuter Exploit, aber ein klarer
`secure-by-default`-Nachteil.

## Aktueller Befund

### 1. Mikro-Komplexitaet in Kernklassen

| Datei | LOC | Beobachtung |
|-------|----:|-------------|
| `hexagon/application/.../DataImportRunner.kt` | 503 | Ein Teil der Importlogik ist bereits extrahiert, aber zentrale Orchestrierung, Exit-Mapping und Input-Resolution liegen weiter im Runner |
| `hexagon/core/.../SchemaValidator.kt` | 422 | Viele Validierungsregeln und Sonderfaelle zentral gebuendelt |
| `adapters/driven/driver-mysql/.../MysqlDdlGenerator.kt` | 568 | Viel Dialektlogik in einer einzelnen Generator-Klasse |
| `adapters/driven/driver-mysql/.../MysqlMetadataQueries.kt` | 579 | Viele Query-Typen und Support-Checks in einem Sammelobjekt |

Zur Priorisierung wird derzeit repo-weit ueber `*/src/main/kotlin/**/*.kt`
gezaehlt. Mit

```bash
find . -path '*/src/main/kotlin/*' -name '*.kt' -not -path '*/build/*' -print0 \
  | xargs -0 wc -l \
  | awk 'NR>1 { if ($1>300) gt300++; if ($1>=400) ge400++ } END { print gt300, ge400 }'
```

ergab der Stand vom 2026-04-23: 23 Dateien `>300` LOC und 12 Dateien
`>=400` LOC. Wenn ein engerer Scope verwendet wird, muss die Zaehlmethode im
Dokument mitgenannt werden.

### 2. MySQL-Default `allowPublicKeyRetrieval=true`

Der Default wird derzeit an mindestens zwei Stellen injiziert:

| Datei | Rolle |
|-------|-------|
| `adapters/driven/driver-mysql/.../MysqlJdbcUrlBuilder.kt` | produktiver MySQL-URL-Builder |
| `adapters/driven/driver-common/.../HikariConnectionPoolFactory.kt` | Fallback-URL-Builder fuer MySQL |

Das ist aktuell eine Code-vs-Spec-Divergenz: `docs/connection-config-spec.md`
dokumentiert `allowPublicKeyRetrieval` bereits mit Default `false`, waehrend
der produktive Code und der Fallback noch `true` injizieren.

Die aktuellen Tests erwarten dieses Verhalten explizit:

| Datei | Aktuelle Erwartung |
|-------|--------------------|
| `adapters/driven/driver-mysql/.../MysqlJdbcUrlBuilderTest.kt` | `allowPublicKeyRetrieval=true` ist Teil der Defaults |
| `adapters/driven/driver-common/.../HikariJdbcUrlTest.kt` | Fallback-Builder injiziert `allowPublicKeyRetrieval=true` |
| `test/integration-mysql/.../MysqlDataReaderIntegrationTest.kt` | Builder ist registriert; Test ist aber nicht an `allowPublicKeyRetrieval=true` gebunden, der KDoc behauptet das derzeit aber noch |

## Zielbild

### Architektur

- Kleine, klar abgegrenzte Komponenten statt grosser Sammelklassen
- Reine Logik frueher aus Orchestrierungs- und Adapterklassen extrahieren
- Mehr Unit-Tests auf Regel- oder Helper-Ebene, weniger Verhalten nur ueber
  grosse End-to-End-Pfade absichern

### Security Default

- `allowPublicKeyRetrieval` soll **nicht** mehr implizit aktiv sein
- Der Parameter soll nur noch explizit per `ConnectionConfig.params` oder
  bewusst dokumentiertem Opt-in gesetzt werden
- TLS-faehige Pfade sollen der bevorzugte Default bleiben

## Vorschlag

### A. Mikro-Komplexitaet schrittweise abbauen

#### A1. `DataImportRunner`

Die erste Entflechtung ist bereits erfolgt:

- `ImportPreflightValidator`
- `ImportCheckpointManager`
- `ImportResumeCoordinator`

Der naechste Schritt ist daher **nicht** eine zweite parallele Preflight- oder
Checkpoint-Abstraktionsrunde, sondern das Praezisieren der verbleibenden
Hotspots im Runner:

| Bereich | Aktueller Hotspot | Moegliche Entlastung |
|--------|-------------------|---------------------|
| `validateAndResolve()` | CLI-Validierung, Format-/Input-Aufloesung, URL-/Charset-Parsing, Dialekt-Capabilities | kleine Resolver fuer Input, Target und Exit-Mapping |
| `executeWithPool()` | Verdrahtung von Preflight, Checkpoint, Resume, Streaming und Finalisierung | orchestrierender Ablauf mit weniger inline Branching |
| `finalizeAndReport()` | Exit-Klassifikation, Checkpoint-Cleanup, Summary-Ausgabe | separater Result-/Exit-Mapper |
| `resolveImportInput()` / `resolveFormat()` | Topologie- und Dateiformatregeln | kleine reine Helper mit klaren Fehlerkontrakten |

Ziel ist, die bereits extrahierten Komponenten zu erhalten und den Runner um
die noch verbliebenen Mischverantwortungen herum schlanker zu machen.

#### A2. `SchemaValidator`

Regeln als eigene Einheiten extrahieren, z. B.:

```kotlin
interface SchemaValidationRule {
    fun validate(schema: SchemaDefinition): ValidationResult
}
```

Moegliche Gruppen:

- Tabellen-/Spaltenregeln
- Referenz- und Constraintregeln
- Default-/Sequenzregeln
- Dialekt- bzw. Migrationskompatibilitaet

Wichtig dabei: Der bestehende Rueckgabevertrag von `SchemaValidator` bleibt
stabil. Das Refactoring darf weder die `ValidationResult`-Shape noch die
Trennung in `ValidationError` und `ValidationWarning`, noch die bestehenden
Codes und ihre Error-/Warning-Semantik aendern. Die Ledger- und
Code-gebundenen Tests bleiben damit explizite Guardrails.

#### A3. `MysqlDdlGenerator`

Nach Objektarten oder DDL-Phasen splitten:

- Tabellen
- Constraints und Indizes
- Views / Routinen / Trigger
- Sequences / Support-Objekte
- Rollback-Erzeugung

#### A4. `MysqlMetadataQueries`

Query-Sammelobjekt in kleinere Bereiche trennen:

- Tabellen- und Spaltenmetadaten
- Constraint- und Indexqueries
- View-/Routinequeries
- Sequence-Support-Checks

Das Ziel ist nicht maximale Fragmentierung, sondern kleinere, lokal
verstehbare Dateien mit engerem Testfokus.

### B. MySQL-Default haerten

#### B1. Default aendern

In `MysqlJdbcUrlBuilder` und im MySQL-Zweig des `FallbackJdbcUrlBuilder`
`allowPublicKeyRetrieval=true` aus den Defaults entfernen.

#### B2. Opt-in erhalten

Explizite Benutzer-Parameter muessen weiterhin durchgereicht werden:

```kotlin
ConnectionConfig(
    ...,
    params = mapOf("allowPublicKeyRetrieval" to "true")
)
```

#### B3. Tests anpassen

Folgende Testaenderungen sind zu erwarten:

- Builder-Tests: nicht mehr auf implizites `allowPublicKeyRetrieval=true`
  pruefen
- Stattdessen absichern:
  - Default enthaelt den Parameter nicht
  - explizites Opt-in wird korrekt uebernommen
- Fallback-Builder-Tests analog anpassen

#### B4. Spec-Alignment und Folgeartefakte

Hier geht es primaer um eine **Code-an-Spec-Angleichung**, nicht um eine
neue Spezifikation: `docs/connection-config-spec.md` dokumentiert den MySQL-
Default bereits als `false`.

Betroffene Doku:

- `docs/connection-config-spec.md`
- `docs/quality-report.md`, falls das Review-Artefakt mit dem Code-Stand
  synchron gehalten werden soll
- betroffene MySQL-KDocs, insbesondere der Hinweis in
  `MysqlDataReaderIntegrationTest.kt`
- ggf. MySQL-bezogene Integrations- oder Betriebsdoku

Die Doku sollte klar sagen:

- Default ist konservativ
- ohne TLS kann fuer bestimmte MySQL-8-Setups ein explizites
  `allowPublicKeyRetrieval=true` noetig sein
- bevorzugter Pfad ist TLS statt Lockerung des Connectors

## Reihenfolge

| Schritt | Thema | Risiko | Kommentar |
|--------|-------|--------|-----------|
| 1 | MySQL-Default und zugehoerige Tests/Doku | niedrig-mittel | Kleine, klar pruefbare Verhaltensaenderung |
| 2 | `MysqlMetadataQueries` aufteilen | mittel | Gute Startstelle, weil rein lesend und query-zentriert |
| 3 | `MysqlDdlGenerator` modularisieren | mittel-hoch | Hohe Testabdeckung wichtig |
| 4 | `DataImportRunner` entflechten | mittel | Viele Orchestrierungspfade |
| 5 | `SchemaValidator` regelbasiert schneiden | hoch | Grosste fachliche Streuung |

## Risiken

- Der MySQL-Defaultwechsel kann bestehende Non-TLS-Setups brechen, die sich
  bisher auf das implizite `allowPublicKeyRetrieval=true` verlassen.
- Ein uebereiltes Architektur-Refactoring kann Testcode und Fehlerpfade
  verschlechtern, wenn nur umorganisiert statt wirklich entkoppelt wird.
- Zu feine Zerlegung ohne klares Ziel produziert nur mehr Dateien, aber nicht
  mehr Verstaendlichkeit.

## Akzeptanzkriterien

### Architektur

- Die priorisierten Grossklassen sind in kleinere Einheiten mit klarer
  Verantwortung zerlegt.
- Neue Helper oder Regelobjekte sind direkt unit-testbar.
- Kein Rueckgang der bestehenden Testabdeckung in den betroffenen Modulen.
- Fuer `SchemaValidator` bleiben `ValidationResult`, die Error/Warning-
  Aufteilung, die bestehenden Codes und ihre Ledger-verankerte Semantik
  stabil.

### Security Default

- Generierte MySQL-JDBC-URLs enthalten `allowPublicKeyRetrieval` nicht mehr
  implizit.
- Ein explizit gesetzter Parameter wird weiterhin korrekt uebernommen.
- Tests und Doku spiegeln das neue Default-Verhalten konsistent wider.

## Nicht Teil dieses Refactorings

- Die bereits separat behobenen Query-Praezedenzfehler in
  `MysqlMetadataQueries` und `SqliteProfilingDataAdapter`
- Allgemeine Compilerwarnungen ausserhalb dieses Scopes
- Vollstaendige Neuorganisation aller grossen Klassen in einem einzigen Schritt
