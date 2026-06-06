# S10a — Dependency-Hygiene + Footprint-Inventar

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S10a / §4.1).
>
> Status: Closed (2026-06-06). Reine Build-Skript-/
> Plan-Doc-Arbeit; kein Produktivcode angefasst.

---

## 1. Scope

Zwei Befunde aus dem Umbrella §1.1 adressieren:

- **Befund 3** (Avro transitiv ueber Hadoop) — schliessen
  per Pfad A (Reject + Exclude). Alternativen-Entscheidung
  begruendet in §3.
- **Befund 4** (Hadoop-Footprint) — **nur inventarisieren**
  und in
  [`parquet-libraries.md`](parquet-libraries.md) §11 als
  1.0.0-Input zurueckspielen. Excludes selbst sind
  bewusst 1.0.0-Arbeit (AP13 §8.3, Umbrella §4
  Eingangsabsatz).

## 2. Lieferumfang

### 2.1 Build-Aenderung

`adapters/driven/formats-parquet/build.gradle.kts`:

- `org.apache.hadoop:hadoop-common`-Block plus
  `org.apache.hadoop:hadoop-mapreduce-client-core`-Block
  bekommen je einen `exclude(group = "org.apache.avro")`
  mit S10a-Kommentar.
- Neuer Constraint-Eintrag `org.apache.avro:avro` mit
  `version { rejectAll() }` plus `because(...)`. Zweck:
  Belt-and-Suspenders gegen kuenftige Dependency-Updates,
  die einen dritten/vierten transitiven Avro-Pfad einziehen
  koennten.

### 2.2 Plan-Doc-Aenderungen

- `docs/planning/done/parquet-libraries.md` §6 AP1.b:
  S10a-Befund-Rueckspiel-Block ergaenzt — dokumentiert den
  `dependencyInsight`-Snapshot vor/nach, begruendet die
  Erweiterung gegenueber dem urspruenglichen
  AP1.b-Constraint und nennt den AP3-Spike-Test als
  Verifikation.
- `docs/planning/done/parquet-libraries.md` §11
  (Footprint-Inventar) neu — gesamtzahl (142
  Runtime-Deps), Hadoop-Footprint-Schwergewichte und die
  drei erwarteten 1.0.0-Massnahmen.

## 3. Pfad A vs. Pfad B — Entscheidung

Umbrella §4.1 verlangt eine eindeutige Wahl. Vorgehensweise
und Beleg fuer **Pfad A**:

1. `dependencyInsight --dependency org.apache.avro:avro
   --configuration runtimeClasspath` auf
   `:adapters:driven:formats-parquet` aufgenommen
   (siehe §4 Snapshot). Ergebnis: `org.apache.avro:avro:1.9.2`
   transitiv ueber `hadoop-common` **und**
   `hadoop-mapreduce-client-core`.
2. Probeweise Pfad A im Build-Skript verdrahtet (Exclude
   auf beide Hadoop-Deps + zusaetzlicher Constraint).
3. `make docker-test MODULES=":adapters:driven:formats-parquet"`
   gegen den geschaerften Classpath: **gruen** (49s Build
   + 25s Test). Damit ist die Bedingung aus Umbrella §4.1
   ("AP3-Spike-Tests bleiben gruen ohne Avro") belegt.
4. Pfad A bleibt drin; Pfad B (akzeptierte Rest-Dependency
   mit `because(...)`) wird nicht gebraucht.

**Konsequenz fuer die AP1.b-Garantie:** die urspruengliche
Aussage in
[`parquet-libraries.md`](parquet-libraries.md) §6 AP1.b
("kein Avro-/Protobuf-Reflection-Pfad im Klassenpfad") war
vor S10a zu stark formuliert — die `parquet-avro`-
Constraint allein verhinderte den `parquet-avro`-Jar,
liess aber den `org.apache.avro:avro`-Jar transitiv ueber
Hadoop drin. Nach S10a ist die Aussage **tatsaechlich**
erfuellt; `dependencyInsight` liefert "No dependencies
matching given input were found".

## 4. Belege

### 4.1 `dependencyInsight` vor S10a

```
org.apache.avro:avro:1.9.2
+--- org.apache.hadoop:hadoop-common:3.4.1
|    \--- runtimeClasspath
\--- org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1
     \--- runtimeClasspath
```

(Befehl: `docker build --target build --build-arg
GRADLE_TASKS=":adapters:driven:formats-parquet:dependencyInsight
--dependency org.apache.avro:avro --configuration
runtimeClasspath"`.)

### 4.2 `dependencyInsight` nach S10a

```
No dependencies matching given input were found in
configuration ':adapters:driven:formats-parquet:runtimeClasspath'
```

### 4.3 AP3-Spike-Test-Lauf nach S10a

`make docker-test MODULES=":adapters:driven:formats-parquet"`:

- Build stage: BUILD SUCCESSFUL in 49s.
- Test stage: BUILD SUCCESSFUL in 25s.
- AP3/AP4/AP5/AP6-Spike-Tests laufen alle gruen ohne
  `org.apache.avro:avro` im Classpath.

### 4.4 Footprint-Snapshot (verifiziert)

Reproduzierbar via:

```bash
docker build --no-cache --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats-parquet:dependencies --configuration runtimeClasspath" \
  -t d-migrate:s10a-deps-snapshot .
```

Zaehlmethode siehe
[`parquet-libraries.md`](parquet-libraries.md) §11.1.

- **Nach S10a**: 129 externe `group:artifact`-
  Koordinaten + 4 interne Projekt-Module = **133 resolved
  Knoten**. Vollstaendige Liste siehe §8 unten.
- **Hadoop-Footprint-Block**: 66 Eintraege ueber 7
  Gruppen-Klassen (siehe
  [`parquet-libraries.md`](parquet-libraries.md) §11.2).
- **Vorher-Vergleich**: vor S10a war
  `org.apache.avro:avro:1.9.2` zusaetzlich Teil des
  Tree (transitiv ueber zwei Hadoop-Wege); danach ist
  der Knoten weg. Das ist der einzige direkte
  Dependency-Delta, den S10a einfuehrt — der
  Avro-Knoten plus seine Resolution-Kette.

## 5. Bewusst NICHT in S10a

- Keine `exclude`/`reject`-Eintraege fuer
  Hadoop-Footprint-Transitive
  (HDFS/YARN/Jersey/reload4j/Zookeeper/Netty). Sind
  1.0.0-Arbeit (AP13 §8.3 / Umbrella §4 / §6).
- Keine Distributions-Cut-Entscheidung (Default-JAR vs.
  `--parquet`-Variante). Liegt in 1.0.0 (AP13 §6.2 i.V.m.
  §8.4 — 0.9.8 bleibt Default-JAR-Modell).
- Keine Native-Image-Probe (S10b).

## 6. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| Avro-Klemme geschlossen | `dependencyInsight … avro` | "No dependencies matching given input were found" |
| AP3-Spike-Tests gruen | `make docker-test MODULES=":adapters:driven:formats-parquet"` | BUILD SUCCESSFUL |
| Bestandsformate reagieren nicht | `make docker-test MODULES=":adapters:driven:formats"` | BUILD SUCCESSFUL (implizit ueber `make docker-check`) |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Footprint-Snapshot als 1.0.0-Input | `parquet-libraries.md` §11 | dokumentiert |
| AP1.b-Befund-Rueckspiel | `parquet-libraries.md` §6 AP1.b | erweitert |

## 7. Folgeaufgaben

- **S3** (naechster Slice): `ParquetChunkReader`/`Writer`
  produktiv, `ParquetSeekableDataChunkReaderFactory` als
  Default-Impl des in S2 angelegten Ports,
  `DataExportFormat.PARQUET`-Erweiterung plus
  Contract-Branches in den Default-Factories.
- **S10b** (nach S3): Native-Image-Sondierung gegen die
  in S3 erstellten produktiven Klassen + S10a-
  Constraints; reine Befund-Erhebung, kein gruenes
  CI-Gate (Umbrella §4.2).
- **1.0.0** (AP13 §8.3): Hadoop-Footprint-Minimierung
  basierend auf
  [`parquet-libraries.md`](parquet-libraries.md) §11.2
  Schwergewichte; Distributions-Cut entscheiden.

---

## 8. Vollstaendiger `runtimeClasspath`-Snapshot nach S10a

Aus dem Befehl in §4.4, dedupliziert auf
`group:artifact` (Version weggelassen — Bestandsversionen
sind in `gradle.properties` / `build.gradle.kts` der
einzelnen Module gepinnt). Interne Projekt-Module sind
unten getrennt aufgefuehrt.

### 8.1 Interne Projekt-Module (4)

- `project :hexagon:core`
- `project :hexagon:ports-common`
- `project :hexagon:ports-read`
- `project :hexagon:ports-write`

### 8.2 Externe `group:artifact`-Koordinaten (129)

```
aopalliance:aopalliance
ch.qos.reload4j:reload4j
com.fasterxml.jackson.core:jackson-annotations
com.fasterxml.jackson.core:jackson-core
com.fasterxml.jackson.core:jackson-databind
com.fasterxml.jackson:jackson-bom
com.fasterxml.jackson.jaxrs:jackson-jaxrs-base
com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider
com.fasterxml.jackson.module:jackson-module-jaxb-annotations
com.fasterxml.woodstox:woodstox-core
com.github.pjfanning:jersey-json
com.github.stephenc.jcip:jcip-annotations
com.google.code.findbugs:jsr305
com.google.code.gson:gson
com.google.errorprone:error_prone_annotations
com.google.guava:failureaccess
com.google.guava:guava
com.google.guava:listenablefuture
com.google.inject.extensions:guice-servlet
com.google.inject:guice
com.google.j2objc:j2objc-annotations
com.google.re2j:re2j
com.jcraft:jsch
commons-beanutils:commons-beanutils
commons-cli:commons-cli
commons-codec:commons-codec
commons-collections:commons-collections
commons-io:commons-io
commons-logging:commons-logging
commons-net:commons-net
commons-pool:commons-pool
com.nimbusds:nimbus-jose-jwt
com.sun.jersey.contribs:jersey-guice
com.sun.jersey:jersey-client
com.sun.jersey:jersey-core
com.sun.jersey:jersey-server
com.sun.jersey:jersey-servlet
com.sun.xml.bind:jaxb-impl
dnsjava:dnsjava
io.airlift:aircompressor
io.dropwizard.metrics:metrics-core
io.netty:netty-all
io.netty:netty-buffer
io.netty:netty-codec
io.netty:netty-codec-dns
io.netty:netty-codec-haproxy
io.netty:netty-codec-http
io.netty:netty-codec-http2
io.netty:netty-codec-memcache
io.netty:netty-codec-mqtt
io.netty:netty-codec-redis
io.netty:netty-codec-smtp
io.netty:netty-codec-socks
io.netty:netty-codec-stomp
io.netty:netty-codec-xml
io.netty:netty-common
io.netty:netty-handler
io.netty:netty-handler-proxy
io.netty:netty-handler-ssl-ocsp
io.netty:netty-resolver
io.netty:netty-resolver-dns
io.netty:netty-resolver-dns-classes-macos
io.netty:netty-resolver-dns-native-macos
io.netty:netty-transport
io.netty:netty-transport-classes-epoll
io.netty:netty-transport-classes-kqueue
io.netty:netty-transport-native-epoll
io.netty:netty-transport-native-kqueue
io.netty:netty-transport-native-unix-common
io.netty:netty-transport-rxtx
io.netty:netty-transport-sctp
io.netty:netty-transport-udt
jakarta.activation:jakarta.activation-api
jakarta.xml.bind:jakarta.xml.bind-api
javax.annotation:javax.annotation-api
javax.inject:javax.inject
javax.servlet.jsp:jsp-api
javax.ws.rs:jsr311-api
javax.xml.bind:jaxb-api
org.apache.commons:commons-compress
org.apache.commons:commons-configuration2
org.apache.commons:commons-lang3
org.apache.commons:commons-math3
org.apache.commons:commons-text
org.apache.curator:curator-client
org.apache.curator:curator-framework
org.apache.curator:curator-recipes
org.apache.hadoop:hadoop-annotations
org.apache.hadoop:hadoop-auth
org.apache.hadoop:hadoop-common
org.apache.hadoop:hadoop-hdfs-client
org.apache.hadoop:hadoop-mapreduce-client-core
org.apache.hadoop:hadoop-yarn-api
org.apache.hadoop:hadoop-yarn-client
org.apache.hadoop:hadoop-yarn-common
org.apache.hadoop.thirdparty:hadoop-shaded-guava
org.apache.hadoop.thirdparty:hadoop-shaded-protobuf_3_25
org.apache.httpcomponents:httpclient
org.apache.httpcomponents:httpcore
org.apache.kerby:kerb-core
org.apache.kerby:kerb-crypto
org.apache.kerby:kerb-util
org.apache.kerby:kerby-asn1
org.apache.kerby:kerby-config
org.apache.kerby:kerby-pkix
org.apache.kerby:kerby-util
org.apache.parquet:parquet-column
org.apache.parquet:parquet-common
org.apache.parquet:parquet-encoding
org.apache.parquet:parquet-format-structures
org.apache.parquet:parquet-hadoop
org.apache.parquet:parquet-jackson
org.apache.yetus:audience-annotations
org.apache.zookeeper:zookeeper
org.apache.zookeeper:zookeeper-jute
org.bouncycastle:bcprov-jdk18on
org.checkerframework:checker-qual
org.codehaus.jettison:jettison
org.codehaus.mojo:animal-sniffer-annotations
org.codehaus.woodstox:stax2-api
org.eclipse.jetty.websocket:websocket-api
org.eclipse.jetty.websocket:websocket-client
org.eclipse.jetty.websocket:websocket-common
org.jetbrains:annotations
org.jetbrains.kotlin:kotlin-stdlib
org.jline:jline
org.locationtech.jts:jts-core
org.slf4j:slf4j-api
org.slf4j:slf4j-reload4j
```

### 8.3 Hinweise

- `org.apache.avro:avro` ist nach S10a **nicht** im
  runtimeClasspath; `dependencyInsight` liefert "No
  dependencies matching given input were found" (§4.2).
- `org.apache.parquet:parquet-avro` und
  `org.apache.parquet:parquet-protobuf` sind durch die
  jeweils vor S10a bereits aktiven `rejectAll()`-
  Constraints raus (`build.gradle.kts` Zeilen 51-66
  vor S10a; unveraendert nach S10a).
- `org.eclipse.jetty.websocket:*` ist ueberraschend
  vorhanden — die `hadoop-common`-Excludes (Zeilen 28-33)
  filtern `org.eclipse.jetty` raus, der
  `org.eclipse.jetty.websocket`-Group-Filter
  (Zeile 33) deckt aber nur den `*-websocket`-Pfad ab,
  nicht den runtime-resolved `websocket-{api,client,
  common}`. Inventar-Befund, nicht S10a-Aktion;
  1.0.0-Footprint-Cut sollte das adressieren.
