# Tracker: `persistence-jdbc` — 421 Produktivzeilen nur integrations-gedeckt

> **Status:** Befund mit Messung (Draft) / Trigger Watch (2026-07-31)
> **Trigger:** Diagnose-Ausgabe des Coverage-Gates. Die JDBC-Server-Adapter stehen im
> Standard-Lauf bei **0 %** — nicht weil sie ungetestet wären, sondern weil ihre Tests
> Testcontainers brauchen und der Gate-Lauf keine bekommt.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung für einen der drei Wege
> unten. Bei Weg 3 zusätzlich die Frage, ob die Contract-Suiten danach doppelt laufen
> (einmal gegen den Fake, einmal gegen die echte DB) oder ob die Integrationsschicht auf
> die Ausführungspfade eingedampft wird.

## Messung (2026-07-31, aggregierter Kover-Report)

Produktivklassen aus `adapters/driven/persistence-jdbc/src/main/` mit **0 %** Line-Coverage:

| Klasse | ungedeckte Zeilen |
| --- | --- |
| `idempotency/JdbcIdempotencyStore` | 145 |
| `job/JdbcJobStore` | 109 |
| `quota/JdbcQuotaReservationOwnerStore` | 76 |
| `quota/JdbcOwnerAwareQuotaService` | 35 |
| `quota/JdbcQuotaStore` | 30 |
| `job/JdbcJobStartTransaction` | 15 |
| `migration/JdbcMigrationRunner` | 11 |
| **Summe** | **421** |

Dazu: von **22** Contract-Suiten in `hexagon/ports-common/src/testFixtures/…/server/ports/contract`
werden im Standard-Lauf **4** ausgeführt (diese stehen bei 100 %), **18** nie (0 %). Die
Fixture-Prozente sind damit **kein Messrauschen, sondern ein Signal**: sie zeigen, welche
Contracts ohne Integrationslauf niemand prüft. Ein Exclude dieser Fixtures würde das Signal
löschen und die vier legitim abgedeckten Suiten gleich mit — deshalb **kein** Exclude hier.

## Mechanik

| Lauf | Docker-Socket | Testcontainers | Folge für die Messung |
| --- | --- | --- | --- |
| Gate (`docker build --target coverage-verify`) | nein — Gradle läuft *im* Build-Container | starten nicht | die 421 Zeilen zählen als ungedeckt |
| [`scripts/test-integration-docker.sh`](../../../scripts/test-integration-docker.sh) (`make integration`) | ja (`-v /var/run/docker.sock`) | laufen | dieselben Klassen sind abgedeckt |

Die Abdeckung existiert also — sie wird im falschen Lauf gemessen. Dass der Gate trotzdem
grün ist, liegt an der Aggregation über alle Module: 421 Zeilen mitteln sich aus.

## Wege

1. **Coverage im Integrations-Pfad mitmessen.** `test-integration-docker.sh` ist ein
   `docker run` mit Socket; `koverVerify` kann dort genauso laufen. Misst die Wahrheit,
   kostet einen zweiten Coverage-Lauf und setzt den Socket voraus.
2. **`persistence-jdbc` aus der Standard-Aggregation nehmen**, mit Ledger-Eintrag, der
   benennt, wo stattdessen gemessen wird. Ehrliche Trennung — verschiebt das Problem aber,
   statt es zu lösen.
3. **Die Naht ziehen, die halb schon existiert** — *bevorzugt*. Im selben Paket steht
   `job/JobRecordJson` (Serialisierung, Wire-Mapping) bei **100 %**, während die Stores bei
   0 % stehen: der reine Teil ist bereits ohne Datenbank prüfbar, der SQL-**Bau** ist es nur
   deshalb nicht, weil er mit der SQL-**Ausführung** in derselben Klasse steckt. Trennt man
   beides, wird der Bau-Teil im Standard-Lauf abgedeckt; integrationsgebunden bleibt nur,
   was wirklich eine Datenbank braucht.

## Warum nicht „einfach ausschließen"

Weder Weg 2 noch ein Fixture-Exclude macht die 421 Zeilen geprüft — sie verschieben nur, wo
die Lücke sichtbar ist. Weg 3 ist der einzige, der die Abdeckung im Standard-Lauf **echt**
macht, ohne das Gate um einen Testcontainers-Lauf zu verlangsamen.

## Nebenbefund: ein legitimer Exclude

`hexagon/application/src/testFixtures/…/cli/commands/testing/MigrationExecutorTestSupportKt`
(152 Zeilen, 0 %) ist reiner Test-Support **ohne** Produktiv-Gegenstück und ohne
Signalcharakter — anders als die Contract-Suiten oben. Ein Exclude mit Ledger-Eintrag ist
hier vertretbar, aber es sind 152 Zeilen: kein Hebel, nur Hygiene. Getrennt entscheiden.
