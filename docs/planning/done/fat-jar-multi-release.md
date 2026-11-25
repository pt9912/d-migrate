# Fat-JAR: `Multi-Release` fehlt — keine Datenbankverbindung über einen Hostnamen

- **Status**: **DONE / abgebrochen zugunsten des Entfernens** (2026-11-25). Das Fat-JAR wird nicht mehr ausgeliefert; siehe „Closure" unten.
- **Ziel**: `java -jar d-migrate-X.Y.Z-all.jar` verbindet sich wie ZIP, TAR, Docker und
  Homebrew mit einer Datenbank, die über einen Hostnamen angesprochen wird. Die Release-Prüfung
  belegt das an jedem Release, nicht nur `--help`.
- **Vorbedingungen**: keine. Kein ADR nötig; es ist ein Packaging-Defekt, kein Vertragswechsel.

## Befund (gemessen 2026-11-23)

Jeder Befehl, der eine Verbindung über einen Hostnamen aufbaut, scheitert mit dem Fat-JAR sofort:

```
Exception in thread "main" java.util.ServiceConfigurationError:
  java.net.spi.InetAddressResolverProvider: Provider org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider not found
```

Gemessen mit `schema compare --source file:… --target db:postgresql://…@localhost:…` an den
**veröffentlichten** JARs von v1.12.1 und v1.12.2 und am Arbeitsstand `1.13.0-SNAPSHOT`, jeweils
gleich. Dieselbe Verbindung mit der ZIP-Distribution (`bin/d-migrate`) und dem Docker-Image läuft.

**Ursache.** `dnsjava` (transitiv über `hadoop-common` im Parquet-Adapter) ist ein Multi-Release-Jar.
Seinen `InetAddressResolverProvider` meldet es über `META-INF/services/…`, die Klasse liegt aber nur
unter `META-INF/versions/18/org/xbill/DNS/spi/`. Das Shadow-Jar übernimmt beides, sein Manifest trägt
jedoch nur `Main-Class` und **kein `Multi-Release: true`**
(`adapters/driving/cli/build.gradle.kts`, Task `shadowJar`). Die JVM liest `META-INF/versions/`
deshalb nicht, der ServiceLoader findet die gemeldete Klasse nicht und bricht die Namensauflösung ab.
In den getrennten Jars der ZIP-Distribution trägt `dnsjava` sein eigenes Manifest, dort fehlt nichts.

**Warum es nicht auffiel.** Die Release-Anleitung prüft das Fat-JAR nur mit `--help`
(`docs/user/releasing.md`, 3.2, 4.4 und 4.6). `--help` löst keinen Namen auf.

**Weitere Multi-Release-Inhalte im Fat-JAR** (141 Einträge unter `META-INF/versions/`): unter anderem
die geshadete Parquet-Kopie, Jackson 2 und 3, Logback (`versions/21`), sqlite-jdbc und der
PostgreSQL-Treiber. Ohne `Multi-Release` bekommen sie heute stillschweigend ihre Basisklassen. Mit dem
Attribut wechseln sie auf die versionsspezifischen Klassen — dieselben, die die ZIP-Distribution schon
heute lädt.

## Scope-Skizze

- **P1 — Manifest.** `attributes["Multi-Release"] = "true"` am `shadowJar`. Prüfen, ob zwei Jars
  dieselbe Datei unter `META-INF/versions/<n>/` liefern (Merge-Konflikt beim Shadowing); ein
  `module-info.class` unter `versions/9` ist auf dem Klassenpfad ohne Wirkung.
- **P2 — Test, der es gefunden hätte.** Ein Smoke, der das gebaute Fat-JAR mit einer Verbindung über
  einen Hostnamen startet (SQLite reicht nicht: kein Hostname). Kandidat: ein `test/e2e-cli`-Lauf mit
  `java -jar …-all.jar` gegen den PostgreSQL-Testcontainer, oder ein Schritt im `release-assets`-Smoke,
  der `localhost` auflöst. Gegenprobe: ohne das Attribut muss er scheitern.
- **P3 — Release-Anleitung.** In `releasing.md` (3.2, 4.4, 4.6, Checkliste) neben `--help` einen
  Aufruf mit Hostname, damit der Punkt am Artefakt geprüft wird.
- **P4 — CHANGELOG** unter `Fixed`, mit den betroffenen Versionen.

## Akzeptanz (DoD)

**Fachlich:**
* `java -jar d-migrate-X.Y.Z-all.jar schema compare --source file:… --target db:postgresql://…@<host>:…`
  läuft gegen eine erreichbare Datenbank durch, ohne `ServiceConfigurationError`. Dasselbe gilt für einen
  Befehl, der schreibt (`data transfer` oder `schema migrate --execute`).
* Das Manifest des Fat-JARs trägt `Multi-Release: true` (`unzip -p …-all.jar META-INF/MANIFEST.MF`).
* Kein Merge-Konflikt unter `META-INF/versions/` (P1): Jede Datei dort stammt aus genau einem Jar, oder
  der Konflikt ist benannt und aufgelöst.

**Rot-Beleg:** Der neue Smoke aus P2 scheitert ohne das Attribut mit genau dieser Meldung und ist mit
dem Attribut grün. Den Fehlschlag einmal sehen, dann zurücknehmen und die Rücknahme an der Ausgabe
prüfen.

**Artefakt:** `make release-assets` ist grün, und `sha256sum -c` stimmt. Die Jar-Liste der
ZIP-Distribution ist gegenüber dem Stand davor unverändert (Liste vergleichen, nicht zählen).

**Gates:**
* `make docker-check MODULES=":adapters:driving:cli"`. Liegt der Smoke in `test/e2e-cli`, zusätzlich
  `make integration INTEGRATION_TASKS=":test:e2e-cli:test"`.
* `make solid-suppression-gate`, `make docs-check`, `make doc-immutable RANGE=origin/main..HEAD`.
* Auf dem Push sind „Build & Test“ und „Integration Tests“ grün.

**Doku:** `releasing.md` (3.2, 4.4, 4.6 und die Checkliste) prüft das Fat-JAR mit einer Verbindung über
einen Hostnamen. Der CHANGELOG nennt unter `Fixed` die betroffenen Versionen.

## Offene Frage

- Soll das Fat-JAR weiter ausgeliefert werden? (Beantwortet in der Closure unten: nein.)

## Stand (2026-11-24)

- **P1:** `attributes["Multi-Release"] = "true"` am `shadowJar` (`adapters/driving/cli/build.gradle.kts`).
  Geprüft: Unter `META-INF/versions/` liefern mehrere Jars nur `module-info.class` doppelt, sonst ist jede
  Datei eindeutig. Auf dem Klassenpfad hat `module-info.class` keine Wirkung.
- **P2:** Der Task `smokeReleaseJar` hängt an `assembleReleaseAssets`. Er startet das Fat-JAR mit
  `schema reverse --source postgresql://smoke:smoke@localhost:1/smoke` und erwartet Exit 4 ohne
  `ServiceConfigurationError`. Er läuft damit bei `make release-assets`, im Release-Preflight und an jedem
  Tag (`build.yml`, `release-homebrew.yml`). Ein eigener Server ist nicht nötig. Rot-Beleg: Ohne das
  Attribut scheitert `make release-assets` an diesem Task mit genau der Meldung aus dem Befund (Exit 1).
  Mit dem Attribut ist er grün.
- **Gegen eine echte Datenbank** (PostgreSQL 18, gebautes JAR): `schema compare` meldet `IDENTICAL`, und
  `schema migrate --execute` schreibt den geänderten CHECK.
- **Artefakt:** `make release-assets` ist grün, `sha256sum -c` stimmt. Die Jar-Liste der ZIP gleicht der
  von v1.12.2 (Liste verglichen).
- **P3/P4:** `releasing.md` 3.2, 4.4 und Checkliste geprüft und ergänzt; CHANGELOG unter `Fixed`.

## Closure (2026-11-25)

**Entscheidung (Eigner, auf Empfehlung): Das Fat-JAR wird nicht mehr ausgeliefert, ab 1.14.0.**

Der Befund dieses Slice war nur die Spitze. Beim Beantworten der offenen Frage zeigte sich, dass das Fat-JAR
**nur PostgreSQL** konnte, nicht nur keinen Hostnamen auflösen. Gemessen am Arbeitsstand `1.13.0`:

| Aufruf | ZIP-Distribution | Fat-JAR |
| --- | --- | --- |
| `schema reverse` gegen SQLite | Exit 0 | `Failed to get driver instance`, Exit 4 |
| `schema reverse` gegen MySQL, Oracle, SQL Server | Verbindungsfehler wie erwartet | `Failed to get driver instance` |
| `schema generate --target mysql` (nur Datei) | läuft | `IllegalStateException: No DialectReadCapabilityProvider for MYSQL` |

**Ursache:** Das Shadow-Plugin war ohne `mergeServiceFiles()` konfiguriert; von jeder `META-INF/services`-Datei
blieb nur eine. 13 Service-Dateien kommen aus mehreren Jars (`DatabaseDriver`, `java.sql.Driver`,
`DialectCapabilityProvider`, `DialectReadCapabilityProvider`, die Jackson-Module, die Flyway-Plugins u. a.); bei
den Treibern gewann PostgreSQL.

**Gründe fürs Entfernen:**
1. Es bietet nichts, was die ZIP/TAR nicht auch bietet: beide brauchen JVM 21, die ZIP ist kleiner (111 gegen 119 MB)
   und läuft vollständig.
2. Es nutzte niemand: 1–3 Downloads je Release, kein einziges Issue in all den Releases, obwohl vier von fünf
   Dialekten nicht liefen.
3. Reparieren (`mergeServiceFiles()` plus `Multi-Release`) ist möglich, aber der Kanal bleibt anfällig: Jede
   Bibliothek mit eigener Service- oder Resource-Datei kann das JAR wieder still beschädigen.

**Geliefert:**
- `shadowJar`, das Shadow-Plugin und `smokeReleaseJar` sind aus `adapters/driving/cli/build.gradle.kts`
  entfernt, das JAR aus `stageReleaseAssets`. Das `Multi-Release`-Attribut aus 1.13.0 entfällt damit mit.
- **Der Smoke blieb, für die ZIP:** `smokeReleaseZip` hängt an `assembleReleaseAssets`. `schema generate` je Ziel
  (fünf Dialekte, ohne Datenbank) prüft das Dialekt-Register, `schema reverse` gegen eine unerreichbare Quelle je
  Dialekt prüft den JDBC-Treiber und die Namensauflösung (erwartet Exit 4, ohne `Failed to get driver
  instance`). Rot-Beleg: Ohne das Dialekt-Modul `driver-mysql` scheitert `generate --target mysql`; ohne
  `mssql-jdbc` scheitert der `reverse`-Aufruf gegen SQL Server; mit falschem Erwartungswert scheitert er sofort.
  Ein Fehlen nur des `driver-mysql`-Moduls bemerkt `reverse` allein nicht (der Verbindungsaufbau läuft vorher am
  JDBC-Treiber): deshalb beide Aufrufe.
- Doku: README (de/en), `guide.md`, `administrationshandbuch.md`, `spec/architecture.md`, `releasing.md` (3.2, 4.4,
  4.5, 4.6, Checkliste) und die Roadmap ohne Fat-JAR; CHANGELOG unter `Removed` und `Changed`. Die ADRs 0044 und 0052
  nennen das Fat-JAR im eingefrorenen Kern und bleiben unverändert.
- **Offen, nicht Teil dieses Slice:** Die Release-Notes und das README-Statusblock von 1.13.0 behaupten, das Fat-JAR
  erreiche Datenbanken wieder über einen Hostnamen. Das stimmt nur für PostgreSQL.

