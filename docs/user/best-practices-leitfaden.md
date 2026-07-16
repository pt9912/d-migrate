# Best-Practices-Leitfaden

> **Software-Version:** 1.0.0-RC-SNAPSHOT · **Stand:** 16.07.2026
>
> **Zielgruppe:** Personen, die d-migrate produktiv einsetzen und wiederkehrende
> Entscheidungen (Performance, Sicherheit, Verifikation, Rollback) gut treffen
> wollen. Dieser Leitfaden bündelt **Empfehlungen, Faustregeln und Anti-Patterns**
> quer über die Aufgaben. Das **Schritt-für-Schritt-Vorgehen** einer Migration steht
> im [Migrations-Leitfaden](migrations-leitfaden.md), die Aufgabenreferenz im
> [Anwenderhandbuch](anwenderhandbuch.md), Betriebsdetails im
> [Administrationshandbuch](administrationshandbuch.md).

---

## 1. Zweck und Abgrenzung

Dieser Leitfaden **wiederholt** die anderen Dokumente nicht, sondern verdichtet sie
zu Handlungsempfehlungen und verweist für die Tiefe zurück:

- **Wie führe ich eine Migration durch?** → [Migrations-Leitfaden](migrations-leitfaden.md)
  (Phasenmodell, Reverse/Generate/Transfer/Compare, Playbooks).
- **Erste Schritte / kopierbare Beispiele?** → [`guide.md`](guide.md).
- **Was macht Kommando X genau?** → [Anwenderhandbuch](anwenderhandbuch.md) und
  [API-Referenz](api-referenz.md).
- **Wie betreibe ich d-migrate (Deployment, Pool, S3, MCP)?** →
  [Administrationshandbuch](administrationshandbuch.md).

Hier geht es um die Frage **„Was ist die empfohlene Wahl — und was sollte ich
vermeiden?"**

---

## 2. Vor jeder Migration: die verdichtete Pre-Flight-Prüfung

Die vollständige Checkliste steht im
[Migrations-Leitfaden, Abschnitt 10.4](migrations-leitfaden.md#104-checkliste-für-pilot-migrationen).
Die drei Punkte mit dem größten Schadenspotenzial:

- **`schema validate` grün, bevor Sie generieren.** Offene Errors (fehlender
  Primärschlüssel **E008**, nicht abbildbare Typen) sind die häufigste Ursache
  späterer Import-Fehler.
- **Warnungen lesen, nicht überfliegen.** d-migrate meldet verlustbehaftete
  Abbildungen als Warnung, statt sie still durchzuführen — jede unquittierte
  Typ-Warnung ist eine Entscheidung, die Sie noch nicht getroffen haben
  ([Abschnitt 4](#4-cross-dialect-typ-fallstricke)).
- **`--include-all` beim Reverse setzen**, wenn die Quelle Views/Trigger/Functions/
  Procedures enthält. **Anti-Pattern:** ohne die `--include-*`-Flags reversen — diese
  Objekte fehlen dann **still** schon im neutralen Modell, und niemand bemerkt es bis
  zur Abnahme.

**Faustregel:** Ein Trockenlauf (`reverse` → `validate` → `generate --deterministic`)
gegen eine Kopie kostet Minuten und deckt fast alle Überraschungen vor dem ersten
echten Schreibzugriff auf.

---

## 3. Performance-Tuning

Der Streaming-Datenpfad (`data export`/`import`/`transfer`) hat **drei** Stellschrauben
plus die Pool-Größe. Alle folgen der Präzedenz **CLI-Flag > `.d-migrate.yaml`-Config >
eingebauter Default**. Werte werden nie still gekürzt: ungültige **Config-Werte**
(`.d-migrate.yaml`) werden mit **Exit 7** abgelehnt, ungültige **CLI-Flag-Werte** (z. B.
`--chunk-size 0`, `--parallel 0`) mit **Exit 2**. Details:
[Administrationshandbuch, Abschnitt 3.5](administrationshandbuch.md#35-pipeline-tuning-pipeline)
und [Abschnitt 4.3](administrationshandbuch.md#43-connection-pool-defaults-hikaricp).

| Stellschraube | CLI-Flag | Config | Default | Wirkung |
| ------------- | -------- | ------ | ------- | ------- |
| Nebenläufigkeit | `--parallel` | `pipeline.parallelism` | `1` | unabhängige Tabellen/Partitionen parallel (FK-sicher in Topo-Ebenen) |
| Chunk-Größe | `--chunk-size` | `pipeline.chunk_size` | `10000` | Zeilen pro Transaktion/Commit |
| Cursor-Prefetch | `--fetch-size` | `pipeline.fetch_size` | `1000` | JDBC-Prefetch beim Lesen der Quelle (nur Export/Transfer) |
| Pool-Größe | — | `pool.max_size` | `10` | max. gleichzeitige DB-Verbindungen |

### Faustregeln

- **Nebenläufigkeit an die Pool-Größe koppeln:** `--parallel N` sollte
  `pool.max_size` **nicht überschreiten** — mehr Worker als Pool-Verbindungen erzeugen
  nur Wartezeit in `getConnection`. Am einfachsten `pipeline.parallelism: auto` setzen:
  das deckelt automatisch auf `min(CPU-Kerne, pool.max_size)`.
- **Nebenläufigkeit ⊥ Wiederaufnahme/Atomarität.** Ein **explizit** gesetztes
  `--parallel > 1` ist mit `--resume` und `--atomic` unverträglich (**Exit 2**). Kommt
  der Wert aus der Config, fällt er in diesen Kombinationen still auf `1` zurück. Wählen
  Sie pro Lauf **eine** Priorität: maximaler Durchsatz **oder** Wiederaufnehmbarkeit/
  Clean-Load.
- **SQLite ist immer sequenziell** (Pool 1, kein paralleles Schreiben) — `--parallel`
  wird dort auf `1` geklemmt. Nicht dagegen antunen.
- **`chunk_size`:** größer → weniger Commit-Overhead, aber mehr Speicher und längere
  Locks; kleiner → robuster bei knappem Heap und kürzere Sperren. Der Default `10000`
  ist für die meisten Fälle richtig.
- **`fetch_size`:** bei **sehr breiten Zeilen** (viele/große Spalten) **kleiner** wählen,
  um den Lese-Heap zu begrenzen; bei schmalen Zeilen ruhig größer für mehr Durchsatz.
  Für SQLite ist der Wert nur ein Hint.
- **Artefakt statt Direkt-Transfer für große/Produktions-Läufe.** Nur der
  artefaktbasierte Pfad (`data export` → `data import`) unterstützt `--resume` nach
  Abbruch und ein prüf-/aufbewahrbares Zwischenformat; für sehr große Datenmengen ist
  **Parquet** (`--format parquet`) das kompakteste, typstabile Transportformat. Siehe
  [Migrations-Leitfaden, Abschnitt 2.2](migrations-leitfaden.md#22-entscheidungsbaum-direkter-transfer-vs-artefakt-basiert).

> Die drei Cancel-Reaktions-Schranken (keepalive-/statement-/network-Timeout) sind
> bewusst **nicht** über `pipeline.*` tunbar; ihre Defaults stehen im
> [Administrationshandbuch, Abschnitt 4.3](administrationshandbuch.md#43-connection-pool-defaults-hikaricp).

---

## 4. Cross-Dialect-Typ-Fallstricke

Weil jede Migration über das neutrale Modell läuft, ist die zielseitige Abbildung eine
bewusste Übersetzung — nicht jeder Quelltyp hat eine verlustfreie Entsprechung. Die
normative Lückenliste steht in [`spec/type-mapping.md`](../../spec/type-mapping.md),
Abschnitte 2–4; hier die praxisrelevanten Empfehlungen und Anti-Patterns:

| Fall | Worauf achten |
| ---- | ------------- |
| **PostgreSQL-Extension-Typen** (`citext`, `ltree`, `hstore`) | Keine native Entsprechung in MySQL/SQLite — vor der Migration entscheiden, ob sie fachlich nötig sind, sonst als `text`/`json` abbilden. |
| **`TINYINT(1)` ↔ `BOOLEAN`** (MySQL ↔ PostgreSQL) | MySQL modelliert `BOOLEAN` als `TINYINT(1)`; prüfen Sie, dass die Semantik in beide Richtungen erhalten bleibt. |
| **`DECIMAL`/Zeit-Präzision** | Präzision/Skala und Zeit-Sub-Sekunden sind dialektabhängig; SQLite bildet `DECIMAL` auf `REAL` ab (**W200**, Präzisionsverlust möglich). |
| **String-Längen** | Längenerhaltung ist dialektabhängig; unbegrenzte `TEXT`-Indizes brauchen in MySQL eine Präfixlänge. |
| **Arrays** (`text[]`) | In MySQL als `JSON`, in SQLite als `TEXT` (SQLite hat keinen JSON-Typ). |
| **ENUM** | PG-`enum` → MySQL-`ENUM` → SQLite `TEXT`. Im `schema generate`-Pfad **mit** `CHECK`-Constraint; im migrate/diff-Pfad **ohne** — dann warnt **W134**, dass die deklarierten Enum-Werte im Ziel **nicht** durchgesetzt werden (SQLite hat keinen nativen Enum-Typ). Gilt für alle SQLite-Enums und PG-Inline-`values`-Enums; MySQL-`ENUM` und PG-`refType` bleiben nativ. |
| **Identity-Spalte in zusammengesetztem PK** | SQLite kann `AUTOINCREMENT` nur beim **einspaltigen** Primärschlüssel; landet eine Serial-/Identity-Spalte in einem **zusammengesetzten** PK (z. B. via partitionierter MySQL-Zwischenstufe), wird `AUTOINCREMENT` verworfen (**W135**) — die IDs müssen dann explizit geliefert werden. |

**Faustregel:** Warnungen sind das Feature, nicht das Rauschen. Jede gemeldete
Degradation (`W…`) benennt genau, was verloren geht — quittieren oder beheben Sie sie
**vor** der Abnahme. Und rechnen Sie damit, dass ein Round-Trip (Reverse → Generate →
Reverse) **nicht** zeichengleich sein muss, wenn der Zieldialekt eine Eigenschaft nur
emuliert (siehe [Migrations-Leitfaden, Abschnitt 6.6](migrations-leitfaden.md#66-round-trip-risiko-verstehen)).

---

## 5. Verifikation und sauberer Load

Vertrauen Sie einer Migration erst nach einer Abnahme — d-migrate liefert dafür drei
sich ergänzende Ebenen ([Migrations-Leitfaden, Abschnitt 10](migrations-leitfaden.md#10-validierung-und-abnahme)):

- **Struktur:** `schema compare` Quelle↔Ziel. **Exit 1** signalisiert Unterschiede und
  eignet sich direkt als Gate ([Abschnitt 6](#6-ci-integration)).
- **Inhalt byte-genau:** `data transfer --verify` reconciliert Quelle und Ziel per
  dialekt-neutraler SHA-256-Prüfsumme und meldet Divergenzen mit **Exit 3**. Repräsentativ
  umgeformte Cross-Dialect-Spalten werden aus der Byte-Prüfung ausgeschlossen; Mechanik,
  Vorbedingung (sauberer Load) und Ausschlussregel stehen im
  [Migrations-Leitfaden, Abschnitt 10.3](migrations-leitfaden.md#103-sha-256-verifikation).
- **Plausibilität:** Zeilenzahlen pro Tabelle plus Stichproben; `data profile` liefert je
  Seite einen vergleichbaren Datenqualitäts-Report.

**Sauber laden — Empfehlungen:**

- **Atomar, wo Alles-oder-nichts zählt:** `--atomic` rollt bei einem Fehler den
  **gesamten** Load zurück (kein halb befülltes Ziel). Beachten Sie: `--atomic` schließt
  `--parallel > 1` aus ([Abschnitt 3](#3-performance-tuning)).
- **Wiederholbar:** `--on-conflict update` (statt Default `abort`) macht einen Import
  idempotent — sicher für Neuanläufe. Für Bulk-Loads `--disable-fk-checks` **plus**
  anschließende Integritätsprüfung; Trigger mit `--trigger-mode disable` stumm schalten
  und post-data erst **danach** anlegen.

---

## 6. CI-Integration

d-migrate ist zustandslos pro Lauf und über **Exit-Codes** skriptbar — ideal als Gate.
Die vollständige, kommando-spezifische Tabelle steht in der
[API-Referenz](api-referenz.md#2-allgemeine-konzepte) bzw.
[`spec/cli-spec.md`](../../spec/cli-spec.md); die für Pipelines wichtigsten:

| Exit | Bedeutung | Als Gate |
| ---- | --------- | -------- |
| `0` | Erfolg | weiter |
| `1` | `schema compare`: Unterschiede gefunden | Schema-Drift → Build brechen |
| `2` | CLI-Fehler / unzulässige Flag-Kombination | Pipeline-Bug → fixen |
| `3` | `data transfer --verify`: Prüfsummen-Divergenz | Datenintegrität verletzt → Build brechen |
| `4` / `7` | Verbindungs- / Konfigurationsfehler | Umgebung/Config prüfen |

**Empfehlungen:**

- **Reproduzierbare Artefakte:** `schema generate --deterministic` erzeugt byte-stabile
  DDL — so schlägt ein Diff nur bei echten Änderungen an, nicht bei Sortier-Rauschen.
- **`schema compare` als Merge-Gate** gegen die Ziel-DB (oder das Soll-Schema), damit
  Schema-Drift nie unbemerkt durchrutscht.
- **Artefaktbasiert für Nachvollziehbarkeit:** Export-Dateien (JSON/YAML/Parquet) sind
  prüf- und aufbewahrbar; zusammen mit `--verify`-Prüfsummen ergeben sie einen
  auditierbaren Migrations-Nachweis.

---

## 7. Rollback-Strategie

Planen Sie den Rückweg, **bevor** Sie ausrollen — nicht erst im Störfall:

- **Rollback-Artefakt gleich miterzeugen:** Diff-basierte Migrationen schreiben mit
  `--generate-rollback` ein Down-SQL-Artefakt; `schema generate --split pre-post` hält
  pre-/post-data getrennt und damit gezielt zurücknehmbar.
- **Down-SQL vor Produktion testen.** Ein ungeprüftes Rollback ist kein Rollback —
  spielen Sie es gegen eine Kopie durch (`schema rollback` mit dem Artefakt).
- **Schema- ≠ Datenrollback.** `schema rollback` nimmt Struktur zurück; verlorene/
  geänderte **Daten** holt nur ein Backup zurück. Für Produktionsläufe daher immer ein
  DB-Backup **vor** dem Schnitt.
- **Release-/Deployment-Rollback** (Image-/Artefakt-Austausch, MCP-Server-State) ist ein
  eigener Ablauf — siehe [Administrationshandbuch, Abschnitt 10.2](administrationshandbuch.md#102-rollback-szenarien)
  und [`releasing.md`](releasing.md).

---

## 8. Credential-Handling

DB-Zugangsdaten werden über eine Prioritätskette aufgelöst; Mechanik und vollständige
Kette (5 Schichten, Maskierung) stehen im
[Administrationshandbuch, Abschnitt 4.6](administrationshandbuch.md#46-credential-handling).
Die entscheidende — und sicherheitskritischste — Wahl ist die **Schicht**:

- **Interaktiver Arbeitsplatz → verschlüsselter lokaler Store**
  (`d-migrate config credentials set`; AES-256-GCM in `~/.d-migrate/`, Master-Secret nicht
  als Datei auf Platte).
- **Headless (CI/Container) → Delegation** über die Umgebung: `D_MIGRATE_DB_PASSWORD`, eine
  `${VAR}`-Referenz oder ein `credentialRef` (`env:`/`file:`, z. B. k8s-Secret-Mount) —
  damit kein Secret im Klartext-Ruhezustand liegt. **Anti-Pattern:**
  `D_MIGRATE_MASTER_PASSWORD` in CI zu setzen ist **kein** Gewinn gegenüber den
  DB-Zugangsdaten direkt per Env.

Zusätzlich:

- **`credentialRef` ist fail-closed:** ein gesetzter, aber unauflösbarer Ref bricht ab,
  statt ohne Secret zu verbinden — Fehlkonfiguration fällt sofort auf.
- **Least Privilege:** eigene DB-Konten mit genau den benötigten Rechten (Reverse braucht
  nur Katalog-Lesezugriff; ein Ziel-Load braucht kein `SUPER`/`DROP DATABASE`).
- **`~/.d-migrate/` in `.gitignore`** aufnehmen — der verschlüsselte Store gehört nicht
  ins Repo.

---

## Verwandte Dokumentation

- [Migrations-Leitfaden](migrations-leitfaden.md) · [Troubleshooting-Leitfaden](troubleshooting-leitfaden.md) · [Anwenderhandbuch](anwenderhandbuch.md) · [Administrationshandbuch](administrationshandbuch.md) · [API-Referenz](api-referenz.md) · [`guide.md`](guide.md)
- [`spec/type-mapping.md`](../../spec/type-mapping.md), [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/connection-config-spec.md`](../../spec/connection-config-spec.md), [`spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md)
