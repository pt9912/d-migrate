# EF Core 10 in der Migrations-Toolchain

> Status: Vorschlag / Entscheidungsbasis
> Ziel: Erweiterung der bestehenden Migrations-Toolchain um EF Core 10 als
> fuenftes Exportziel neben Flyway, Liquibase, Django und Knex.js.
>
> Dieses Dokument beschreibt den Vorschlag fuer den EF-Core-10-Pfad. Bis zur
> Implementierung bleiben `docs/cli-spec.md`, `docs/design.md` und
> `docs/architecture.md` die massgeblichen Quellen fuer den produktiven
> Nutzervertrag.

---

## 1. Ausgangslage

Die bestehende 0.7.0-Toolchain exportiert baseline-/full-state-
Migrationsartefakte aus genau einem neutralen Schema fuer:

- Flyway
- Liquibase
- Django
- Knex.js

Der Exportpfad ist im Code bereits als generische Tool-Architektur umgesetzt:

- `MigrationTool`
- `MigrationIdentityResolver`
- `MigrationVersionValidator`
- `ToolExportRunner`
- `ToolMigrationExporter`

EF Core 10 soll in diese Architektur als weiteres Ziel integriert werden, ohne
den Kernansatz zu aendern:

- Quelle bleibt das neutrale d-migrate-Schema
- Ziel bleibt ein deterministisches Exportartefakt
- es bleibt ein baseline-/full-state-Export und kein diff-basierter
  `schema migrate`-Pfad

---

## 2. Warum EF Core 10 ein Sonderfall ist

EF Core unterscheidet sich konzeptionell von Flyway, Liquibase, Django und
Knex in zwei Punkten:

1. EF Core erzeugt Migrationen normalerweise aus einem `DbContext`-Modell und
   einem `ModelSnapshot`, nicht aus bereits gerendertem SQL.
2. Eine regulär per `dotnet ef migrations add` erzeugte Migration besteht in
   der Praxis nicht nur aus einer Hauptdatei, sondern aus einer Migration,
   einer Designer-Datei und einem `ModelSnapshot`.

Gleichzeitig erlaubt EF Core explizit benutzerdefinierte SQL-Operationen in
Migrationen ueber `migrationBuilder.Sql(...)`. Genau dort passt d-migrate an:

- d-migrate bleibt System of Record fuer das neutrale Schema
- EF Core wird als Konsum- und Ausfuehrungsumgebung adressiert
- der erste Integrationsschritt rendert deshalb eine SQL-getriebene
  EF-Core-Migration anstatt zu versuchen, ein vollstaendiges EF-Modell samt
  Snapshot zu synthetisieren

Diese Abgrenzung ist wichtig: Der Export soll EF Core integrieren, nicht
`dotnet ef migrations add` nachbauen.

---

## 3. Ziele

- EF Core 10 als neues `d-migrate export`-Ziel einfuehren
- deterministische, versionskontrollierbare C#-Migrationsartefakte erzeugen
- dieselbe Ziel-Dialekt-Logik wie bei den bestehenden Exportern verwenden
- optionalen Rollback ueber `Down(...)` unterstuetzen
- keine Projektdateien, `DbContext`-Klassen oder bestehende Snapshots mutieren
- die bestehende Hexagon-/Adapter-Struktur weiterverwenden

## 4. Nicht-Ziele

- kein Ersatz fuer regulaeres EF-Code-First-Scaffolding
- keine automatische Erzeugung oder Mutation von `*.Designer.cs`
- keine automatische Erzeugung oder Mutation von `*ModelSnapshot.cs`
- keine automatische Erzeugung oder Mutation von `.csproj`
- keine automatische Erzeugung oder Mutation von `DbContext`
- keine Ableitung von `MigrationOperation`-Objekten aus dem neutralen Modell
- keine automatische Ausfuehrung von `dotnet ef`
- keine diff-basierten inkrementellen Migrationsketten im 0.7.0-Sinn

---

## 5. Vorschlag fuer den CLI-Vertrag

### 5.1 Neuer Subcommand

```bash
d-migrate export efcore --source schema.yaml --target postgresql --version 20260415123000_initial --output src/MyApp/Migrations
```

### 5.2 Flags

| Flag | Pflicht | Typ | Beschreibung |
|---|---|---|---|
| `--source` | Ja | Pfad | Schema-Datei (YAML/JSON) |
| `--output` | Ja | Pfad | Ausgabeverzeichnis fuer Migrationsdateien |
| `--target` | Ja | Dialekt | Ziel-Datenbank (`postgresql`, `mysql`, `sqlite`) |
| `--version` | Ja | String | EF-Core-taugliche Migrations-ID, empfohlen `yyyyMMddHHmmss[_slug]` |
| `--namespace` | Nein | String | C#-Namespace; Default `Migrations` |
| `--generate-rollback` | Nein | Boolean | Generiert `Down(...)` aus dem bestehenden full-state-Rollback |
| `--spatial-profile` | Nein | String | Wie bei den bestehenden Exportern |
| `--report` | Nein | Pfad | Transformationsbericht (YAML-Sidecar) |

### 5.3 Versionsstrategie

EF Core 10 soll wie Django und Knex ein explizites `--version` verlangen.

Begruendung:

- EF-Core-Migrationen werden ueblicherweise chronologisch sortiert
- `schema.version` ist semantisch oft eine Release-Version, aber keine saubere
  Migrations-ID
- ein impliziter Fallback auf `schema.version` wuerde leicht zu unpassenden
  Dateinamen und Migration-IDs fuehren

Empfohlene Validierungsregel:

- `^\d{14}(_[a-z][a-z0-9_]*)?$`

Beispiele:

- `20260415123000`
- `20260415123000_initial`
- `20260415123000_create_customer_tables`

Exit-Code-Verhalten sollte analog zu Django/Knex bleiben:

- `0` Erfolg
- `2` ungueltige Flags oder fehlendes `--version`
- `3` Schema-Validierungsfehler
- `7` Parse-/I/O-/Render-Fehler

---

## 6. Artefaktvertrag

### 6.1 Phase-1-Artefakt

Der erste EF-Core-10-Exporter erzeugt genau eine C#-Datei:

- `<version>_<slug>.cs`

Der `slug` folgt derselben Normalisierung wie bei den bestehenden Exportzielen.

Beispiel:

- `20260415123000_customer_portal.cs`

### 6.2 Dateiinhalt

Die Datei enthaelt:

- `using Microsoft.EntityFrameworkCore.Migrations;`
- optional `#nullable disable`
- Namespace gemaess `--namespace`
- `[Migration("<version>_<slug>")]`
- eine partielle Migrationsklasse mit `Up(MigrationBuilder)`
- `Down(MigrationBuilder)` nur dann, wenn `--generate-rollback` gesetzt ist

Die Klasse enthaelt keine modellbasierte EF-Core-Semantik, sondern sequenzielle
`migrationBuilder.Sql(...)`-Aufrufe aus dem bestehenden DDL-Statement-Stream.

### 6.3 Beispielskizze

```csharp
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MyApp.Migrations;

[Migration("20260415123000_customer_portal")]
public partial class M20260415123000CustomerPortal : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("""
            CREATE TABLE customers (
                id INTEGER PRIMARY KEY
            );
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.Sql("""
            DROP TABLE customers;
            """);
    }
}
```

### 6.4 Rendering-Regeln

- Reihenfolge der Statements bleibt identisch zur `DdlResult.statements`-
  Sequenz
- SQL wird standardmaessig in C#-Raw-String-Literale gerendert
- die Zahl der Raw-String-Begrenzer muss dynamisch so gewaehlt werden, dass sie
  strikt groesser ist als die laengste zusammenhaengende Folge von `"` im
  eingebetteten SQL
- falls ein spaeterer Ziel-Compiler oder Stilentscheid Raw-Strings verbietet,
  ist als dokumentierter Fallback ein Verbatim-String mit verdoppelten `"`
  zu verwenden
- bei `--generate-rollback` wird `Down(...)` aus dem bestehenden
  `generateRollback()`-Pfad erzeugt
- ohne `--generate-rollback` wird `Down(...)` nicht erzeugt; damit gilt das
  Standardverhalten der EF-Basisklasse

Diese Regeln sollen garantieren, dass auch SQL mit eingebetteten Anfuehrungs-
sequenzen zu gueltigem C# fuehrt und der Exportvertrag nicht von heuristisch
"meistens passenden" String-Literalen abhaengt.

---

## 7. Bewusste Grenzen der ersten EF-Core-10-Integration

### 7.1 Kein `ModelSnapshot`

Ein von d-migrate erzeugter EF-Core-Export soll in Phase 1 keinen
`*ModelSnapshot.cs` schreiben oder mutieren.

Begruendung:

- der Snapshot repraesentiert EF-intern ein C#-basiertes Modell, nicht nur
  SQL-DDL
- d-migrate besitzt heute kein kanonisches EF-Metadatenmodell
- eine synthetische Snapshot-Generierung wuerde die Komplexitaet gegenueber den
  anderen Exportzielen unverhaeltnismaessig stark erhoehen

### 7.2 Keine `*.Designer.cs`-Datei

Die Designer-Datei wird ebenfalls bewusst nicht erzeugt.

Begruendung:

- sie ist eng an EF-Scaffolding und Snapshot-Metadaten gekoppelt
- fuer den ersten Schritt ist die eigentliche `Migration`-Klasse das
  relevante, versionskontrollierbare Lieferartefakt

### 7.3 Kein Versprechen von Roundtrip-Scaffolding

Die neue Integration darf dokumentarisch nicht so klingen, als koenne d-migrate
ein EF-Core-Projekt vollstaendig in einem Zustand halten, in dem anschliessend
beliebig weitere `dotnet ef migrations add`-Aufrufe ohne manuelle Pflege des
Snapshots funktionieren.

Der erste Vertrag ist enger:

- d-migrate liefert ein EF-Core-kompatibles, SQL-basiertes
  Migrationsartefakt fuer ein bestehendes EF-Core-10-Projekt
- die Einbindung in die langfristige EF-Modellhistorie bleibt Verantwortung des
  einbindenden Projekts

---

## 8. Einordnung pro Ziel-Dialekt

Die bestehende d-migrate-Exportlogik bleibt provider-spezifisch. Das passt gut
zu EF Core, weil EF ohnehin migrationsbezogen provider-spezifische Sets kennt.

Fuer den ersten Scope gelten dieselben Dialekte wie im heutigen Projekt:

- PostgreSQL
- MySQL
- SQLite

Nicht im Scope dieses Dokuments:

- SQL Server
- Oracle
- mehrere provider-spezifische EF-Migrationssets in einem Lauf

Ein spaeterer Ausbau kann mehrere EF-Core-Migrationsordner nebeneinander
unterstuetzen, z. B.:

- `Migrations/Postgresql`
- `Migrations/MySql`
- `Migrations/Sqlite`

---

## 9. Auswirkungen auf die Codebasis

### 9.1 Ports und gemeinsame Datentypen

- `MigrationTool` um `EFCORE` erweitern
- `requiresExplicitVersion` fuer `EFCORE` auf `true` setzen
- `MigrationVersionValidator` um EF-Core-Regel erweitern

### 9.2 Integrations-Adapter

Neu:

- `EfCoreMigrationExporter`

Hilfsfunktionen:

- Dateiname aus `<version>` und `slug`
- C#-Identifier-Normalisierung fuer Klassennamen
- Escaping fuer C#-Raw-String-Literale oder normale Verbatim-Strings

### 9.3 CLI

- `ExportEfCoreCommand`
- Root-`export`-Command bekommt `efcore` als weiteres Subcommand
- Hilfe-Text nennt EF Core 10 explizit

### 9.4 Tests

Mindestens noetig:

- Help-/Bootstrap-Test fuer `export efcore --help`
- Runner-Test fuer fehlendes `--version`
- Exporter-Test fuer Dateiname, Attribut, Namespace und `Up(...)`
- Rollback-Test fuer `Down(...)`
- Escaping-Test fuer problematische SQL-Inhalte
- Kollisions- und Report-Test analog zu den bestehenden Exportern

### 9.5 Dokumentation

Nach der Implementierung muessen mindestens folgende regulaeren Dokumente
aktualisiert werden:

- `docs/cli-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- ggf. `docs/lastenheft-d-migrate.md`

---

## 10. Empfohlene Implementierungsreihenfolge

1. `MigrationTool` und Validator um `EFCORE` erweitern.
2. `EfCoreMigrationExporter` mit reinem Rendering ohne Dateisystemlogik bauen.
3. CLI-Subcommand `export efcore` anschliessen.
4. Help-, Runner- und Exporter-Tests ergaenzen.
5. Regulaere Doku aktualisieren, sobald der Codepfad produktiv vorhanden ist.

---

## 11. Risiken und offene Fragen

### R1 - EF-Core-Snapshot-Luecke ist fachlich sichtbar

Ohne `ModelSnapshot` und Designer-Datei ist die Integration bewusst schmaler als
regulaeres EF-Scaffolding. Das muss im Nutzervertrag klar bleiben.

### R2 - Statementbasierte SQL-Einbettung bleibt SQL-zentriert

Die Integration nutzt EF Core als Huelle fuer SQL, nicht als eigentlichen
Modell-Differ. Das ist gewollt, darf aber nicht als vollwertige
Code-First-Ableitung missverstanden werden.

### R3 - `Down(...)` ist nur so gut wie der bestehende Rollback-Pfad

Der Down-Pfad bleibt baseline-/full-state-basiert. Das ist konsistent mit den
anderen Exportzielen, aber nicht identisch mit einem spaeteren diff-basierten
Rollback.

### Offene Frage O1

Es sollte vor der Implementierung einmal praktisch verifiziert werden, wie
reibungslos ein nur von d-migrate erzeugtes einzelnes Migrationsfile in einem
minimalen EF-Core-10-Projekt kompiliert, gelistet und ausgefuehrt werden kann,
wenn kein `ModelSnapshot` mitgeliefert wird.

Das ist kein Grund, den Entwurf zu verwerfen, aber ein notwendiger technischer
Spike vor einer produktiven Implementierungsfreigabe.

---

## 12. Entscheidungsempfehlung

Empfohlen wird vorbehaltlich eines erfolgreichen technischen Spikes aus O1 eine
bewusst schmale EF-Core-10-Integration mit folgendem Vertrag:

- neuer Exportpfad `d-migrate export efcore`
- explizites `--version`
- genau eine deterministische C#-Migrationsdatei pro Export
- `migrationBuilder.Sql(...)` als Renderziel
- optionales `Down(...)` ueber den bestehenden Rollback-Pfad
- kein Snapshot, keine Designer-Datei, keine Projektmutation

Damit erweitert d-migrate die bestehende Migrations-Toolchain sinnvoll in das
.NET-Oekosystem, ohne den neutralen Kern zu verlassen oder EF-Scaffolding
heuristisch nachzubauen.

---

## 13. Referenzen

- Lokaler Projektkontext: `docs/cli-spec.md`
- Lokaler Projektkontext: `docs/design.md`
- Lokaler Projektkontext:
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/ExportCommands.kt`
- Lokaler Projektkontext:
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ToolExportRunner.kt`
- Lokaler Projektkontext:
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/migration/MigrationIdentityResolver.kt`
- Lokaler Projektkontext:
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/migration/MigrationVersionValidator.kt`
- Microsoft Learn: https://learn.microsoft.com/en-us/ef/core/managing-schemas/migrations/managing
- Microsoft Learn: https://learn.microsoft.com/en-us/ef/core/managing-schemas/migrations/
- Microsoft Learn: https://learn.microsoft.com/en-us/ef/core/managing-schemas/migrations/providers
- Microsoft Learn: https://learn.microsoft.com/en-us/ef/core/managing-schemas/migrations/operations
- Microsoft Learn:
  https://learn.microsoft.com/en-us/dotnet/api/microsoft.entityframeworkcore.migrations.migrationattribute?view=efcore-10.0
- Microsoft Learn:
  https://learn.microsoft.com/en-us/dotnet/api/microsoft.entityframeworkcore.migrations.migration.down?view=efcore-10.0
