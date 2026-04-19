# MCP-Spezifikation fuer KI-Umgebungen: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf fuer die kuenftige KI-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/design.md`,
> `docs/lastenheft-d-migrate.md`, `docs/beispiel-stored-procedure-migration.md`

---

## 1. Ziel

Fuer KI-gestuetzte Arbeitsumgebungen braucht `d-migrate` keine generische
Remote-API allein, sondern eine agentenfreundliche, sichere und auditierbare
Werkzeugschnittstelle. Dafuer ist MCP besser geeignet als rohe REST- oder
gRPC-Aufrufe.

Das MCP-Zielbild ist:

- ein zusaetzlicher driving adapter unter `adapters/driving/mcp`
- Werkzeuge fuer klar umrissene `d-migrate`-Operationen
- Ressourcen fuer Schemas, Reports, Diffs und Job-Status
- sichere Defaults, damit ein LLM nicht versehentlich destruktive oder
  unkontrollierte Operationen startet
- volle Nachvollziehbarkeit fuer KI-gestuetzte Aktionen

---

## 2. Warum MCP

MCP passt besser zu KI-Clients als REST oder gRPC, weil es:

- Werkzeuge statt generischer Endpunkte anbietet
- Ressourcen fuer gezielte Kontextbereitstellung definiert
- Agenten erlaubt, Faehigkeiten zu entdecken
- lokale und entfernte Laufzeitmodelle unterstuetzt
- besser zu Chat-, IDE- und Copilot-artigen Oberflaechen passt

REST und gRPC bleiben wichtig, aber sie adressieren primaer klassische
Systemintegration. MCP adressiert den Arbeitsmodus "Agent analysiert,
fragt nach, fuehrt gezielte Migrationsschritte aus".

---

## 3. Architekturposition

```text
AI Client / IDE / Agent Runtime
              |
              v
     adapters/driving/mcp
              |
              v
      hexagon/application
              |
              +--> hexagon/ports-common
              +--> hexagon/ports-read
              +--> hexagon/ports-write
              |
              v
       adapters/driven/*
```

Der MCP-Adapter soll keine eigene Fachlogik enthalten. Er kapselt:

- Tool-Definitionen
- Request-Mapping
- Ergebniszuschnitt fuer Agenten
- Sicherheits- und Audit-Regeln

### 3.1 Gemeinsamer Kernvertrag fuer Jobs und Artefakte

MCP nutzt denselben Kernvertrag wie REST und gRPC:

- `jobId` und `artifactId` sind opake, stabile String-IDs
- Jobs und Artefakte tragen immer `createdAt` und `expiresAt`
- Artefakte sind immutable
- nur derselbe Mandant / Principal oder ein Administrator darf Job und
  Artefakt lesen, abbrechen oder herunterladen
- grosse Ergebnisse werden nicht inline in Tool-Responses gehalten, sondern
  ueber `resourceUri` oder `artifactId` referenziert
- Jobs und Artefakte werden nach `expiresAt` serverseitig aufgeraeumt

---

## 4. Zielbild des MCP-Servers

Der MCP-Server soll drei Dinge bereitstellen:

- `tools` fuer aktive Operationen
- `resources` fuer gezielten Read-Kontext
- optional spaeter `prompts`, falls kuratierte Analyse- oder
  Transformationsworkflows gebraucht werden

### 4.1 Tool-Grundsaetze

Tools muessen:

- klein und eindeutig sein
- moeglichst idempotent oder read-only sein
- begrenzte und erklaerbare Resultate liefern
- keine ungefilterten Massendaten in den Chat kippen

Verbindliche Antwortgrenzen:

- ein Tool-Resultat darf hoechstens `64 KiB` serialisierte Nutzdaten inline
  liefern
- ein Tool-Resultat darf hoechstens `200` strukturierte Findings inline
  liefern
- row-basierte Export- oder Importdaten duerfen nie inline in eine Tool-
  Antwort geschrieben werden
- bei Ueberschreitung muss die Antwort auf Summary plus `resourceUri` und/oder
  `artifactId` umschalten

### 4.2 Ressourcen-Grundsaetze

Ressourcen sollen:

- stabile URIs haben
- auf Artefakte, Reports und Schemata zeigen
- grosse Inhalte bei Bedarf chunkbar oder referenzierbar machen

---

## 5. Vorgeschlagene Tools

### 5.1 Read-only Kernset

| Tool | Zweck |
| --- | --- |
| `schema_validate` | neutrales Schema validieren |
| `schema_generate` | DDL fuer ein Zielsystem erzeugen |
| `schema_reverse_start` | Reverse-Engineering-Job starten |
| `schema_compare` | Unterschiede zwischen zwei Schemata oder Umgebungen ermitteln |
| `data_profile_start` | Profiling-Job fuer Analyse und Migrationsplanung starten |
| `capabilities_list` | Dialekte, Formate, Features, KI-Backends anzeigen |
| `job_status_get` | Status eines langen Laufs abfragen |

### 5.2 Kontrollierte Write-Tools

| Tool | Zweck | Default-Schutz |
| --- | --- | --- |
| `data_import_start` | Importjob anlegen | bestaetigungspflichtig / policy-gesteuert |
| `data_transfer_start` | DB-zu-DB-Transfer starten | bestaetigungspflichtig / policy-gesteuert |
| `artifact_upload` | Eingabe-Artefakt fuer spaetere Jobs hochladen | policy-gesteuert |
| `job_cancel` | langen Lauf abbrechen | nur fuer eigene oder erlaubte Jobs |

### 5.3 KI-nahe Spezialtools

Spaeter sinnvoll, wenn der KI-Pfad im Produkt wirklich ausgebaut wird:

| Tool | Zweck |
| --- | --- |
| `procedure_transform_plan` | Stored-Procedure-Transformation analysieren |
| `procedure_transform_execute` | KI-gestuetzte Transformation mit Audit-Trail |
| `testdata_plan` | Testdatengenerierung aus Schema und Regeln planen |

Diese Tools muessen strikt an die in `docs/design.md` beschriebene
Provider- und Audit-Strategie gekoppelt sein.

---

## 6. Vorgeschlagene Ressourcen

| Resource URI | Inhalt |
| --- | --- |
| `dmigrate://capabilities` | unterstuetzte Dialekte, Formate, Features |
| `dmigrate://jobs/{jobId}` | Job-Metadaten und Status |
| `dmigrate://artifacts/{artifactId}` | generierte DDL, Reports, Exporte |
| `dmigrate://schemas/{name}` | bekannte oder erzeugte Schema-Artefakte |
| `dmigrate://profiles/{name}` | Profiling-Reports |
| `dmigrate://diffs/{id}` | Schema-Vergleichsergebnisse |

Wichtig:

- Connection-Secrets sind **keine** MCP-Ressourcen.
- Rohdaten-Exporte sollten nur referenziert, nicht blind inline geliefert
  werden.

---

## 7. Tool-Vertraege

MCP-Tool-Inputs sollen klein, fachlich und agentenfreundlich sein.

Beispiel `schema_compare`:

```json
{
  "left": {
    "schemaRef": "dmigrate://schemas/source"
  },
  "right": {
    "connectionRef": "target-staging"
  },
  "options": {
    "includeCompatibleChanges": true
  }
}
```

Beispiel `schema_generate`:

```json
{
  "schemaRef": "dmigrate://schemas/source",
  "targetDialect": "postgresql",
  "options": {
    "spatialProfile": "postgis",
    "generateRollback": true
  }
}
```

Antworten sollten standardmaessig liefern:

- kompaktes Summary
- strukturierte Findings oder Artefakt-Referenzen
- nicht die komplette Rohdatei, wenn sie gross ist

Fuer `v1` gilt verbindlich:

- langlaufende oder grosse Operationen werden als Start-Tool plus
  `jobId` modelliert
- grosse Ergebnisse werden nur ueber `resourceUri` oder `artifactId`
  bereitgestellt
- Importdaten werden ueber `artifact_upload` vorbereitet und anschliessend per
  `data_import_start` referenziert
- ein Tool darf keine komplette Exportdatei oder ganze Tabelleninhalte inline
  zurueckgeben

---

## 8. Sicherheitsmodell

MCP ist besonders sensibel, weil ein Agent autonom handeln kann. Deshalb:

- read-only Tools standardmaessig freigeben
- write- oder kostenintensive Tools separat freischalten
- keine direkte Uebergabe von JDBC-Passwoertern an das Modell
- kein freier SQL-Toolzugang
- harte Limits fuer Datenmengen, Tabellenanzahl, Laufzeit und Parallelitaet
- explizite Policy fuer produktive Verbindungen

### 8.1 Destruktive oder teure Operationen

Folgende Aktionen duerfen nie stillschweigend passieren:

- `data import`
- `data transfer`
- KI-Aufrufe gegen externe Provider
- grossvolumige Datenexports

Diese Operationen brauchen mindestens:

- Policy-Freigabe
- Audit-Eintrag
- klare Kennzeichnung im Tool-Resultat

### 8.2 Prompt- und Datenhygiene

Der MCP-Adapter muss verhindern, dass:

- Secrets in Tool-Responses auftauchen
- ganze Tabellen ungefiltert in den Prompt-Kontext fliessen
- sensible SQL-, DDL- oder Prozedurtexte ohne Audit an externe Modelle gehen

Lokale Modelle (`Ollama`, `LM Studio`) bleiben fuer sensible KI-Pfade die
bevorzugte Option, analog zu `docs/design.md`.

---

## 9. Auditierbarkeit

Jede MCP-Aktion sollte nachvollziehbar sein:

- wer oder welcher Agent hat das Tool aufgerufen
- mit welchen Parametern
- gegen welche Verbindung oder welches Artefakt
- mit welchem Ergebnis
- bei KI-Transformationen: welches Modell, welcher Provider, welche Version

Fuer KI-generierte Inhalte gilt derselbe Audit-Trail wie im Design-Dokument:

- Quelle
- Zwischenformat
- Zielartefakt
- Modell-Metadaten

---

## 10. Transport

Sinnvolle Betriebsmodi:

- `stdio` fuer lokale IDE-/CLI-Agenten
- streambares HTTP fuer entfernte Agent-Plattformen

Empfehlung:

- lokal zuerst mit `stdio`
- spaeter optional ein remote-faehiger MCP-Server fuer Team- oder
  Plattformbetrieb

---

## 11. Einfuehrungsreihenfolge

### Phase 1

- `capabilities_list`
- `schema_validate`
- `schema_generate`
- `schema_compare`
- `job_status_get`

### Phase 2

- `schema_reverse_start`
- `data_profile_start`
- Artefakt-Ressourcen

### Phase 3

- kontrollierte Write-Tools fuer `artifact_upload`, `data_import_start` und
  `data_transfer_start`

### Phase 4

- KI-nahe Spezialtools fuer Procedure-Transformation und Testdaten

---

## 12. Entscheidung

Fuer eine KI-Umgebung sollte `d-migrate` nicht nur "auch per REST erreichbar"
sein. Es sollte einen eigenen MCP-Adapter bekommen. Damit werden die
bestehenden Migrationsfunktionen fuer Agenten nutzbar, ohne die Sicherheits-
und Audit-Anforderungen einer autonomen KI-Nutzung zu verwischen.
