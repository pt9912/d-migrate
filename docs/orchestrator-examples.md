# Plan: Orchestrator-Beispiele fuer Airflow, Dagster und Prefect

> Dokumenttyp: Integrations- und Dokumentationsplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/roadmap.md`, `docs/cli-spec.md`,
> `docs/guide.md`, `docs/job-contract.md`

---

## 1. Ziel

`d-migrate` soll in bestehende Data-Engineering-Orchestrierung passen, ohne
selbst ein Orchestrator zu werden. Dafuer sollen dokumentierte, getestete
Beispiele fuer Airflow, Dagster und Prefect entstehen.

Die Beispiele sollen zeigen, wie `d-migrate` in produktionsnahe Workflows
eingebettet wird:

- Schema validieren
- DDL generieren
- Daten exportieren
- Daten importieren oder transferieren
- Profiling-Reports erzeugen
- Exit-Codes und Artefakte auswerten

---

## 2. Motivation

Viele Teams steuern Migrationen und Datenjobs nicht manuell ueber einzelne
Shell-Kommandos, sondern ueber Orchestratoren. Gute Beispiele senken die
Integrationshuerde und klaeren, wie `d-migrate` mit Retries, Artefakten,
Secrets und Failures umgehen soll.

Der Schwerpunkt liegt auf stabilen CLI-Vertraegen. Eigene Operatoren oder
SDKs sind erst sinnvoll, wenn die Beispiele zeigen, welche Abstraktion sich
wiederholt.

---

## 3. Scope

### 3.1 In Scope

- Beispiel-DAG fuer Airflow
- Beispiel-Asset/Job fuer Dagster
- Beispiel-Flow fuer Prefect
- Smoke-Tests, die die Beispiele syntaktisch oder minimal ausfuehrbar pruefen
- Dokumentation fuer Secrets, Volumes, Artefakte und Exit-Codes
- Empfehlung fuer Docker-/OCI-Nutzung

### 3.2 Nicht in Scope

- vollwertige Airflow-/Dagster-/Prefect-Provider-Pakete
- eigener Scheduler in `d-migrate`
- Cloud-spezifische Managed-Orchestrator-Setups als erste Iteration
- dauerhafte CDC-Orchestrierung

---

## 4. Ziel-Workflows

### 4.1 Schema-Gate in CI/CD

```text
schema validate
schema generate --target postgresql
schema compare --source file:schema.yaml --target db:staging
```

Zweck: Migration vor Deployment blockieren, wenn Schema oder DDL nicht passt.

### 4.2 Bulk-Migration mit Artefakten

```text
schema generate --split pre-post
data export --output artifacts/export
data import --input artifacts/export
schema compare --source db:source --target db:target
```

Zweck: reproduzierbarer Migrationslauf mit getrennten Artefakten.

### 4.3 Profiling als Quality-Gate

```text
data profile --source prod --output profile.json
```

Zweck: Profiling-Warnungen und Report-Artefakte im Orchestrator sichtbar
machen.

---

## 5. Orchestrator-spezifische Hinweise

| Orchestrator | Beispielziel | Wichtige Punkte |
| ------------ | ------------ | --------------- |
| Airflow | DAG mit BashOperator oder KubernetesPodOperator | Exit-Codes, XCom nur fuer kleine Resultate, Artefakte extern speichern |
| Dagster | Asset/Op fuer Schema und Datenartefakte | Asset-Metadaten, Materializations, Ressourcen fuer Secrets |
| Prefect | Flow mit Shell- oder Docker-Tasks | Retries, Blocks fuer Secrets, Result Storage |

In allen Beispielen sollten grosse d-migrate-Ausgaben als Dateien oder
Artefakte behandelt werden, nicht als Orchestrator-Task-Return-Value.

---

## 6. Repository-Struktur

Vorgeschlagene spaetere Struktur:

```text
examples/orchestrators/
  airflow/
    README.md
    dag_d_migrate.py
  dagster/
    README.md
    definitions.py
  prefect/
    README.md
    flow_d_migrate.py
```

Smoke-Tests koennen zunaechst nur Syntax und erwartete Kommandozeilen pruefen.
Ein vollstaendiger End-to-End-Test mit laufenden Orchestratoren waere spaeter
ein separater Integrations-Scope.

---

## 7. Akzeptanzkriterien

- Jedes Beispiel nutzt den dokumentierten CLI-Vertrag.
- Secrets werden nicht in Beispielcode hardcodiert.
- Grosse Artefakte werden ueber Dateien, Volumes oder externe Stores
  modelliert.
- Exit-Codes werden explizit behandelt.
- Mindestens ein Beispiel laeuft gegen eine kleine Testdatenbank als Smoke.
- Die Dokumentation erklaert klar, wann CLI, REST, gRPC oder MCP geeigneter
  sind.

---

## 8. Arbeitspakete

1. Gemeinsamen Beispiel-Workflow definieren.
2. Airflow-DAG auf Basis des CLI-Images skizzieren.
3. Dagster-Asset/Job skizzieren.
4. Prefect-Flow skizzieren.
5. Smoke-Test-Strategie festlegen.
6. Dokumentation fuer Secrets, Artefakte und Exit-Codes schreiben.

---

## 9. Risiken

- Orchestrator-Beispiele koennen schnell veralten, wenn sie zu stark auf
  spezifische Versionen oder Deployments zugeschnitten sind.
- Zu fruehe Provider-Pakete wuerden Wartungsaufwand erzeugen, bevor der
  wiederverwendbare API-Schnitt klar ist.
- Beispiele muessen bewusst klein bleiben, damit sie als Integrationsmuster
  dienen und nicht als zweite Produktdokumentation.

