# Fat-JAR: `Multi-Release` fehlt — keine Datenbankverbindung über einen Hostnamen

- **Status**: Draft mit Scope (2026-11-24). Aktiv beim ersten Implementierungs-Commit.
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

## Akzeptanzkriterien

1. `java -jar d-migrate-X.Y.Z-all.jar schema compare --source file:… --target db:postgresql://…@<host>:…`
   läuft gegen eine erreichbare Datenbank ohne `ServiceConfigurationError`.
2. Der neue Smoke ist grün und scheitert nachweislich ohne das Attribut.
3. Die übrigen Tests und `make release-assets` sind grün; die Jar-Liste der ZIP-Distribution ist
   unverändert.
4. `releasing.md` prüft das Fat-JAR mit einer Verbindung, nicht nur mit `--help`.

## Offene Frage

- Soll das Fat-JAR weiter ausgeliefert werden? Es ist der einzige Weg ohne Launcher-Skript, aber die
  Distributionsklasse, die die Release-Prüfung am wenigsten abdeckt. Diese Frage entscheidet der Eigner;
  der Plan setzt voraus, dass es bleibt.
