# Handgepflegte Native-Image-Metadaten

Bewusst **getrennt** vom Nachbarverzeichnis `dev.dmigrate/cli/`: dort liegt die Ausgabe des
Tracing-Agenten (`make native-agent`), die bei jedem Lauf neu erzeugt und ueberschrieben wird.
Handarbeit dort waere beim naechsten Agent-Lauf verloren. `native-image` fuehrt alle Verzeichnisse
unter `META-INF/native-image/` zusammen, die Trennung kostet also nichts.

Alle Eintraege hier sind **empirisch belegt**: ohne sie scheitert ein konkreter, reproduzierbarer
Aufruf, mit ihnen laeuft er. Keiner steht auf Verdacht.

## `com.zaxxer.hikari.HikariConfig`

`HikariConfig.logConfiguration()` ruft ueber `PropertyElf.getProperty` reflektiv alle Getter auf,
laeuft aber **nur bei aktivem DEBUG-Logging**. Die `logback.xml` des CLI setzt `root level="WARN"` —
auf der JVM wird der Pfad also nie betreten, und was nicht laeuft, kann der Agent nicht aufzeichnen.

**Belegt (GraalVM 25):** mit erzwungenem DEBUG (`-Dlogback.configurationFile=` auf eine
Konfiguration mit `root level="DEBUG"`) bricht das Binary **ohne** diesen Eintrag mit
`MissingReflectionRegistrationError: Cannot reflectively invoke method
HikariConfig.isAllowPoolSuspension()`; **mit** ihm laeuft der Aufruf durch. Ohne die Registrierung
braeche das Binary also ausgerechnet dann, wenn jemand DEBUG einschaltet, um einen Fehler zu suchen.

Das GraalVM Reachability Metadata Repository fuehrt HikariCP 6.2.1 als **unterstuetzt**
(`check-library-support.sh`), und seine Metadaten enthalten den passenden `methods`-Eintrag — er
wird bei uns aber nicht wirksam. Deshalb hier explizit.

## `org.postgresql.util.PGobject` — `getValue()`

Beim Cross-Dialect-Transfer **PostgreSQL → MySQL** reicht pgjdbc dialektfremde Werte (ein PG-Enum wie
pagilas `mpaa_rating`, ein `tsvector`, `json`) als `PGobject` durch. `JdbcForeignValueNormalizer`
(driver-common) normalisiert sie **reflektiv** zu ihrem String — `value.javaClass.getMethod("getValue")`
in einem `runCatching{}`, damit driver-common keine Compile-Abhaengigkeit auf pgjdbc braucht.

**Belegt (GraalVM 25):** ohne diesen Eintrag findet `getMethod("getValue")` nativ nichts, `runCatching`
verschluckt den Fehler still, und der **rohe** PGobject erreicht `PreparedStatement.setObject` — worauf
MySQL Connector/J in Java-Serialisierung faellt und das Binary mit
`UnsupportedFeatureError: SerializationConstructorAccessor class not found for … PGobject` abbricht.
Der Serialisierungsfehler ist nur das Symptom; die Ursache ist die fehlende Reflection-Registrierung.
Reproduzierbar ueber `SAMPLE_DB_DMIGRATE_IMAGE=d-migrate:native-dev make sample-db-cross-smoke-pg2my`
(ebenso `sample-db-3hop-smoke`, hop1). Mit dem Eintrag laeuft die Normalisierung nativ wie auf der JVM,
der PGobject wird nie serialisiert.

`serialization` zu registrieren waere der falsche Hebel: das baute nur einen Pfad nach, der bei
funktionierender Reflection gar nicht erst betreten wird.

## `org.eclipse.lsp4j.jsonrpc.json.*` und `.messages.*` (50 Klassen)

lsp4j serialisiert JSON-RPC per Gson und instanziiert dafuer TypeAdapter-Fabriken reflektiv. Das
Repository fuehrt `org.eclipse.lsp4j.jsonrpc:0.23.1` als **NICHT unterstuetzt** — es gibt dafuer
keine gepflegten Metadaten, die Registrierung ist unvermeidbar.

## `dev.dmigrate.mcp.protocol.*` (36 Klassen)

Gson braucht **Konstruktoren**, um die DTOs zu instanziieren. Der Agent erfasste nur deren Felder.

## Wie der Fehler sich zeigte — drei Schichten uebereinander

`mcp serve` antwortete nativ mit `{"jsonrpc":"2.0","id":"1","error":{}}` statt dem `result`:

1. Die MCP-DTOs hatten keine Konstruktoren registriert → `parseMessage` scheiterte.
2. Der Parse-Fehler erzeugt eine Fehlerantwort — deren Serialisierung scheiterte ebenfalls, weil
   lsp4js Adapter fehlten. Ergebnis: ein **leeres** `error`-Objekt, das Schicht 1 verdeckte.
3. Die verfaelschte `id` (`"1"` statt `1`) war kein Bug: `tryExtractIdAsString` liest sie als String
   aus dem Rohtext, gerade **weil** das Parsen gescheitert war.

**Und der Exit-Code war 0.** Eine reine Exit-Code-Pruefung haette `mcp serve` als gruen
durchgewinkt — deshalb bewertet die Sonde in `scripts/native-probe.sh` die Antwort, nicht den Code.

## Bevor hier ein Eintrag dazukommt

Erst pruefen, ob das GraalVM Reachability Metadata Repository die Bibliothek schon pflegt:

```
make native-check-lib LIB=com.zaxxer:HikariCP:6.2.1
```

Das beantwortet in Sekunden, was sonst als Handsuche im Repository-Index endet — und ordnet den
Eintrag ein: unvermeidbare Eigenarbeit (Bibliothek nicht unterstuetzt) oder Symptom einer
Fehlkonfiguration (unterstuetzt, greift aber nicht). Beide Faelle gibt es hier bereits, und sie
verlangen unterschiedliche Folgearbeit.
