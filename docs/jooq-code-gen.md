# Eigenes Oracle-Codegen-Tool statt jOOQ-Codegen

**Konzept- und Entscheidungsdokument fuer ein eigenes CLI-Tool zur Oracle-Schema-Introspektion und Codegenerierung**

> Dokumenttyp: Architektur-Notiz / Entscheidungsgrundlage

---

## 1. Ziel

Fuer `d-migrate` soll bewertet werden, ob ein **eigenes Tool** geschrieben werden kann, das ein Oracle-Schema ausliest und daraus Java/Kotlin-Code erzeugt, **ohne** den offiziellen `jooq-codegen`-Generator und moeglichst auch **ohne jOOQ-Bibliotheken** zu verwenden.

Der zentrale Beweggrund ist, das jOOQ-Oracle-Lizenzthema zu vermeiden, solange `d-migrate` nur Metadaten aus Oracle lesen und daraus eigenen Code erzeugen soll.

---

## 2. Kurzfassung

Ja, dieser Ansatz ist technisch moeglich.

Wenn `d-migrate`:

- Oracle-Metadaten per JDBC selbst ausliest
- die Modellierung selbst durchfuehrt
- den Zielcode selbst erzeugt
- und dabei **weder `jooq-codegen` noch andere `org.jooq.*`-Bibliotheken** verwendet

dann ist das **kein jOOQ-Codegen**, sondern ein eigener Generator.

In diesem Fall wird das jOOQ-Lizenzthema fuer den Generierungsschritt grundsaetzlich vermieden.

Der wichtige Vorbehalt ist:

- Sobald der erzeugte Code oder die spaetere Anwendung doch wieder auf `org.jooq.*` basiert, ist die jOOQ-Frage nicht weg, sondern nur vom Generator in die Runtime oder in den Anwendungscode verschoben.

---

## 3. Entscheidende Abgrenzung

Es gibt drei klar zu unterscheidende Varianten.

### 3.1 Eigener Generator ohne jOOQ

`d-migrate` liest Oracle selbst aus und erzeugt eigenen Code, zum Beispiel:

- Metadaten-Klassen
- DAO-Klassen
- Query-Helper
- Record-/Table-Wrapper im eigenen Format
- Dokumentation oder Mapping-Dateien

Merkmale:

- keine Nutzung von `jooq-codegen`
- keine Nutzung von `org.jooq.*`
- keine technische Abhaengigkeit von jOOQ

Das ist der sauberste Weg, wenn das Ziel ist, das jOOQ-Oracle-Thema zu vermeiden.

### 3.2 Eigener Generator, aber jOOQ-kompatibler Output

`d-migrate` erzeugt Klassen, die wie generierte jOOQ-Artefakte aussehen oder direkt auf `org.jooq.*` aufsetzen.

Merkmale:

- Generator ist selbst geschrieben
- aber der Output oder die Zielanwendung benutzt weiterhin jOOQ

Dann wird zwar `jooq-codegen` umgangen, aber nicht automatisch die jOOQ-Nutzung insgesamt.

### 3.3 Offizielle jOOQ-Integration

`d-migrate` ruft intern `jooq-codegen` oder andere jOOQ-Bibliotheken auf.

Das ist der direkte jOOQ-Weg und damit gerade **nicht** der gewuenschte Ansatz.

---

## 4. Lizenz-Einordnung

Stand **5. April 2026** ordnen die offiziellen jOOQ-Seiten Oracle den kommerziellen Editionen zu. In der Support-Matrix wird Oracle nur unter kommerziellen Editionen aufgefuehrt.

Quellen:

- https://www.jooq.org/download/
- https://www.jooq.org/download/support-matrix
- https://www.jooq.org/legal/licensing

Die technische Schlussfolgerung fuer `d-migrate` ist:

- Wenn das Tool **gar kein jOOQ** verwendet, ist es aus technischer Sicht kein jOOQ-Anwendungsfall.
- Wenn das Tool oder der erzeugte Code spaeter doch auf jOOQ basiert, muss die jOOQ-Lizenzfrage weiterhin betrachtet werden.

Wichtig:

- Das ist eine technische Einordnung der Architektur.
- Es ist **keine Rechtsberatung**.
- Vor einer produktiven Einfuehrung sollte die konkrete Nutzungsform bei Bedarf rechtlich oder direkt mit dem Hersteller geklaert werden.

---

## 5. Empfohlene Richtung fuer d-migrate

Die Zielrichtung sollte daher **nicht** sein:

- "jOOQ-Codegen in `d-migrate` einbauen"

sondern:

- "ein eigenes Oracle-Introspection- und Codegen-Tool in `d-migrate` bereitstellen"

Das bedeutet konkret:

- Oracle-Schema per JDBC oder `DatabaseMetaData` auslesen
- internes neutrales Modell fuer Tabellen, Spalten, Keys, Indizes, Sequences aufbauen
- daraus eigene Artefakte generieren
- bewusst **keine** Abhaengigkeit zu `org.jooq.*` einziehen

Damit bleibt `d-migrate` in seiner eigenen Architektur und vermeidet eine enge Kopplung an jOOQ.

---

## 6. Technische Konsequenzen

Der Preis fuer diese Lizenz- und Entkopplungsstrategie ist, dass mehr Eigenlogik gebaut werden muss.

Ein eigener Oracle-Generator muss unter anderem behandeln:

- Tabellen und Spalten
- Primary Keys und Foreign Keys
- Unique Constraints und Indizes
- Oracle-Datentypen wie `NUMBER`, `VARCHAR2`, `CHAR`, `CLOB`, `BLOB`
- `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`
- Sequences
- ggf. Synonyms und Schema-/Owner-Grenzen

Das ist machbar, aber es ist ein echtes Produktfeature und kein kleiner Adapter.

---

## 7. Empfohlene Architektur

### 7.1 CLI-Ebene

Moegliches Kommando:

```bash
d-migrate oracle generate-model \
  --jdbc-url jdbc:oracle:thin:@//localhost:1521/ORCLCDB \
  --user app \
  --password-env DB_PASSWORD \
  --schema APP \
  --target-dir build/generated/oracle-model
```

Alternative Namensgebung:

- `d-migrate oracle introspect`
- `d-migrate oracle generate-code`
- `d-migrate generate oracle-model`

### 7.2 Modulstruktur

Empfohlenes Zielbild:

```text
d-migrate-integrations/
  src/main/kotlin/dev/dmigrate/integration/oracle/
    OracleMetadataReader.kt
    OracleIntrospectionConfig.kt
    OracleTypeMapper.kt
    OracleModelGenerator.kt
```

### 7.3 Interne Schritte

1. JDBC-Verbindung zu Oracle herstellen
2. Metadaten aus Systemkatalog und JDBC-Metadaten lesen
3. Internes Modell aufbauen
4. Optional validieren und normalisieren
5. Zielartefakte in Dateien rendern

---

## 8. Zielartefakte

Wenn das jOOQ-Thema wirklich umgangen werden soll, sollten die Zielartefakte **eigene** Artefakte sein.

Geeignete Ziele:

- Kotlin/Java-Modelle fuer Tabellen und Spalten
- interne DSL-Metadaten
- Generator-Input fuer spaetere SQL- oder DAO-Erzeugung
- Mapping-Dateien fuer andere Frameworks

Weniger geeignet fuer dieses Ziel:

- Klassen, die von jOOQ-Typen erben
- Klassen, die direkt `org.jooq.Table`, `org.jooq.Field` oder `org.jooq.Record` voraussetzen

Denn genau dort wuerde wieder eine inhaltliche Bindung an jOOQ entstehen.

---

## 9. Risiken und Trade-offs

### Vorteile

- keine direkte Abhaengigkeit von `jooq-codegen`
- keine enge Bindung an jOOQ-Interna
- bessere Kontrolle ueber Naming, Output und interne Modellierung
- lizenzseitig sauberer, **wenn** jOOQ komplett vermieden wird

### Nachteile

- hoeherer Implementierungsaufwand
- mehr Verantwortung fuer Oracle-Spezifika
- eigener Wartungsaufwand fuer Typmapping und Metadatenlogik
- keine automatische Kompatibilitaet mit jOOQ-Artefakten

---

## 10. Empfehlung

Fuer `d-migrate` wird folgende Entscheidung empfohlen:

1. Kein programmgesteuerter Aufruf von `jooq-codegen`
2. Kein Einbau von `org.jooq.*` in den Generator
3. Eigenes Oracle-Introspection-Modul bauen
4. Eigene Zielartefakte definieren statt jOOQ-Artefakte nachzubauen
5. Falls spaeter echte jOOQ-Integration gewuenscht wird, diese als **separate** bewusste Produktentscheidung behandeln

Nicht empfohlen wird:

- ein halb-eigener Generator, der offiziell kein `jooq-codegen` nutzt, aber am Ende trotzdem voll auf jOOQ-Runtime aufsetzt

Das waere technisch moeglich, aber fuer das eigentliche Ziel "jOOQ-/Oracle-Lizenzthema vermeiden" nur ein unvollstaendiger Ausweg.

---

## 11. Externe Referenzen

Offizielle jOOQ-Seiten:

- Download / Editions:
  - https://www.jooq.org/download/
- Support-Matrix:
  - https://www.jooq.org/download/support-matrix
- Licensing FAQ:
  - https://www.jooq.org/legal/licensing

Diese Referenzen sollten bei spaeteren Architekturentscheidungen erneut geprueft werden, falls sich das Editionsmodell oder die Oracle-Zuordnung in einer spaeteren jOOQ-Version aendert.
