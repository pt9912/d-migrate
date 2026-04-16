# d-browser Integration: Coupling Assessment

> Status: Draft
> Kontext: Bewertung der aktuell sichtbaren Kopplungen zwischen `d-browser`
> und `d-migrate` vor einem dedizierten `source-d-migrate`-Adapter

## Ziel

Dieses Dokument bewertet vier aktuell sichtbare Kopplungspunkte aus Sicht von
`d-migrate` und ordnet ein, welche Themen als Core-Refactor in `d-migrate`
geloest werden sollten und welche besser im Integrationsadapter von
`d-browser` abgefangen werden.

## Kurzfazit

Von den vier benannten Punkten sind zwei klare `d-migrate`-Refactors, ein
Thema ist eher eine Adapter-Projektionsfrage, und ein Thema sollte als
begrenzte Reuse-Extraktion verstanden werden.

Priorisierte Empfehlung:

1. Profiling aus den JDBC-Treiberkernen herausloesen.
2. Read-/Write-Schnitt in `hexagon:ports` fachlich trennen.
3. FK-Dependency-/Topo-Sort-Utility in einen wiederverwendbaren Core-Helfer
   extrahieren.
4. `SchemaReadResult` vorerst im `source-d-migrate`-Adapter projizieren statt
   den `SchemaReader`-Port sofort umzubauen.

## 1. Schreib-Ports in `hexagon:ports`

### Befund

Der reine Lese-Port ist bereits als `DataReader` separat vorhanden. Im selben
Port-Modul liegen aber weiterhin import- und write-lastige Typen wie
`DataWriter`, `TableImportSession` und `ImportOptions`.

Zusaetzlich ist `ImportOptions` heute nicht nur Writer-Konfiguration, sondern
haengt auch am Format-Lesepfad, weil `DataChunkReaderFactory` denselben Typ fuer
CSV-/Encoding-/Null-Sentinel-Verhalten benutzt.

### Bewertung

Der Kopplungspunkt ist real. Fuer `d-browser` ist es unguenstig, fuer reine
Lesefunktionalitaet ein Modul mitzuziehen, das fachlich auch Write-Vertraege
traegt.

Ein blosser Interface-Split reicht aber nicht aus. Solange `ImportOptions` den
Reader-Pfad mit dem Import-/Writer-Pfad vermischt, bleibt die Abhaengigkeit
inhaltlich bestehen.

### Empfehlung

`d-migrate` sollte die Port-Oberflaeche in einen read-orientierten und einen
write-orientierten Bereich trennen, mindestens:

- read-only Port-Oberflaechen fuer Schema/Data-Lesen
- write/import Port-Oberflaechen fuer `DataWriter` und
  `TableImportSession`
- ein separates read-orientiertes Optionsmodell fuer Format-Reader
- `ImportOptions` nur noch fuer den eigentlichen Import-/Writer-Pfad

Das ist ein sinnvoller Core-Refactor.

## 2. Transitive Profiling-Kopplung in den JDBC-Treibern

### Befund

Die JDBC-Treiberadapter haengen direkt an `hexagon:profiling`, obwohl die
Profiling-Verwendung in eigenen `profiling/`-Paketen liegt. Ausserhalb dieser
Pakete wird `dev.dmigrate.profiling` in den Treiber-Main-Sources nicht genutzt.

### Bewertung

Das ist echte technische Kopplung und kein blosses Komfortproblem fuer
`d-browser`. Wer einen JDBC-Treiber fuer Schema/Data-Lesen wiederverwenden will,
zieht damit heute unnoetig Profiling-Vertraege und Modelltypen transitiv mit.

### Empfehlung

Die Profiling-Adapter sollten aus den Treiber-Kernmodulen in optionale
Zusatzmodule verschoben werden, z. B.:

- `driver-postgresql-profiling`
- `driver-mysql-profiling`
- `driver-sqlite-profiling`

Die eigentlichen Treiberkerne koennen dann bei Schema/Data/DDL bleiben, ohne
`hexagon:profiling` zu exportieren.

Das ist der klarste kurzfristige Refactor mit direktem Integrationsnutzen.

## 3. `SchemaReadResult` ist reverse-orientiert

### Befund

`SchemaReader.read(...)` liefert nicht nur `SchemaDefinition`, sondern einen
Envelope mit `schema`, `notes` und `skippedObjects`.

Diese Zusatzinformationen sind fuer Reverse-/Migration-Workflows sinnvoll, fuer
`d-browser` aber voraussichtlich meist nur Diagnostics-Rauschen.

### Bewertung

Der Punkt ist nachvollziehbar, aber kein akuter Core-Blocker. Der Typ ist als
Envelope bewusst modelliert und verletzt fuer sich genommen noch keine saubere
Integrationsgrenze.

### Empfehlung

Im ersten Schritt sollte der `source-d-migrate`-Adapter in `d-browser` diesen
Typ lokal projizieren:

- `schema` in das eigene Fachmodell uebernehmen
- `notes` und `skippedObjects` ignorieren oder optional als Diagnostics
  mitfuehren

Ein zusaetzlicher schlanker `SchemaReader`-Vertrag in `d-migrate` lohnt sich
erst dann, wenn mehrere Consumer denselben Bedarf haben.

## 4. Keine extrahierten FK-Graph-/Zyklen-Utilities im Core

### Befund

Es gibt heute bereits wiederverwendbare Logik fuer FK-basierte Sortierung und
Zyklen:

- `AbstractDdlGenerator.topologicalSort(...)`
- eine aehnliche Sortierung in `ImportDirectoryResolver`
- eine weitere Sortierung in `DataTransferRunner`

Damit ist die Logik bereits mehrfach vorhanden, aber nicht als stabiler
Core-Helfer extrahiert.

### Bewertung

Der Wunsch nach Reuse ist berechtigt. Gleichzeitig sollte der Scope klar
bleiben: Eine FK-Topo-Sort-Utility hilft bei Tabellenreihenfolge und
Zyklusdiagnostik auf Schemaebene, aber nicht automatisch bei
Baumprojektion/Traversal einzelner Datensaetze in `d-browser`.

### Empfehlung

`d-migrate` sollte einen kleinen, wiederverwendbaren Core-Helfer fuer
Tabellenabhaengigkeiten extrahieren, z. B.:

- `TableDependencyGraph`
- `ForeignKeyTopoSort`
- Result-Typ mit sortierter Reihenfolge und zyklischen Kanten

Die vorhandene duplizierte Sortierlogik in DDL-, Import- und Transfer-Pfaden
kann dann auf denselben Helfer aufsetzen.

Wichtig: Das waere Reuse fuer Tabellenordnung und FK-Zyklus-Checks, nicht fuer
die komplette Baumprojektion von `d-browser`.

## Empfohlene Reihenfolge fuer `d-migrate`

1. Profiling aus Treiber-Kernmodulen extrahieren.
2. Reader-/Writer-Portflaeche inklusive Optionsmodellen trennen.
3. FK-Dependency-/Topo-Sort-Helfer nach `hexagon:core` ziehen.
4. `SchemaReadResult` vorerst unveraendert lassen und ueber den
   `source-d-migrate`-Adapter projizieren.

## Konsequenz fuer `source-d-migrate`

Auch bei erfolgreicher Entkopplung in `d-migrate` bleibt ein eigener
Integrationsadapter fuer `d-browser` sinnvoll. Er sollte:

- nur stabile Lesevertraege konsumieren
- `d-migrate`-Toolmodelle auf `d-browser`-eigene Fachmodelle projizieren
- Diagnostics bewusst filtern
- keine implizite Abhaengigkeit auf Profiling-, Import- oder CLI-Details
  aufbauen

Damit bleibt die in `d-browser` geforderte Integrationsgrenze erhalten:
Wiederverwendung ja, aber nicht gegen interne Tooloberflaechen.
