# Kapazitäts-Projektion: Fingerabdruck **und** Comparator

> **Status:** Erledigt (A bis D).
>
> **Herkunft:** drei Tickets, die als „derselbe Fehlertyp" zusammengelegt
> werden sollten —
> [`fulltext-config-fingerprint-lossy-dialects`](../open/fulltext-config-fingerprint-lossy-dialects.md),
> [`partition-fingerprint-lossy-dialects`](../open/partition-fingerprint-lossy-dialects.md),
> [`pg-identity-sequence-name-fingerprint`](../open/pg-identity-sequence-name-fingerprint.md).
> Die Aufnahme hat eines davon widerlegt und dafür eine gemeinsame Ursache
> gefunden, die größer ist als alle drei.

## Was die Aufnahme ergeben hat

Alle drei Tickets fragen: „speichert der Dialekt dieses Feld?" Das ist die
falsche Frage. Der Fingerabdruck und der Comparator vergleichen ein
**Soll-Schema** mit einem **zurückgelesenen Ist-Schema** — es zählt also, ob
der *Reverse* das Feld zurückgeben kann, nicht ob der *Server* es speichert.
An dieser Verwechslung hängen zwei der drei Befunde.

### 1. Volltext-Konfiguration — bestätigt

`IndexDefinition.textSearchConfig` (ADR 0025).

| Dialekt | Liest der Reverse sie zurück? | Beleg |
| --- | --- | --- |
| PostgreSQL | **ja** | `PostgresFullTextIndexSynthesis` liest sie aus den `to_tsvector`-Argumenten zurück |
| MySQL | nein | emittiert sie nicht (`MysqlIndexPartitionDdlHelper`), liest sie nicht |
| SQLite | nein | „not recoverable from FTS5 and stay null" (`SqliteSchemaReader`) |
| SQL Server | nein | das Feld kommt im gesamten Main-Sourceset des Treibers nicht vor |
| Oracle | nein | Analyzer hängt an einer `CTX_DDL`-Preference, Verwurf mit `W154` |

Der Default `true` ist also für PostgreSQL **richtig** und für die drei
anderen falsch.

### 2. Partitionsgrenzen — widerlegt

Das Ticket behauptet, MySQL und SQL Server verlören untere RANGE-Grenze und
HASH-Modulus. Beide Reader **rekonstruieren** sie:

- `MysqlPartitionReader.reconstructNeutralBounds`: RANGE-`from` aus der
  Kontiguität (`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`), HASH
  `modulus = n`/`remainder = Ordinalindex` aus `PARTITIONS n`.
- `MssqlSchemaReader`: `from = if (i == 0) MinValue else bounds[i - 1]` —
  dieselbe Rekonstruktion.
- Der Comparator normalisiert zusätzlich selbst
  (`PartitionBoundNormalizer.withDerivedLowerBounds`).

Die Angabe round-trippt damit, **solange die Partitionen lückenlos sind**.
Sind sie es nicht, ist der Unterschied echt: MySQL und SQL Server können
eine Lücke gar nicht abbilden, der Zielbestand ist ein anderer, und der
Generate-Pfad sagt das vorher (`W112`). Die Grenze dort auszublenden würde
genau diesen echten Verlust verstecken.

**Folge:** `carriesPartitionLowerBounds` und `carriesPartitionHashModulus`
bleiben für MySQL und SQL Server auf `true`. Das Ticket wird mit dieser
Messung geschlossen, nicht umgesetzt.

Ein **anderer** Befund bleibt und bekommt ein eigenes Ticket: SQL Server
emuliert HASH über eine berechnete Spalte, und „a reverse reads the table
back as RANGE" (`MssqlTablePartitioning`, `W145`). Da driftet nicht der
Modulus, sondern der Partitions**typ** — das kann keine Feld-Projektion
heilen.

### 3. Identity-Sequenzname — bestätigt

`ColumnGeneration.Identity.sequenceName` wird von keinem Dialekt gerendert,
vom PG-Reverse aber schema-qualifiziert gelesen. Ein handgeschriebenes
Soll-Schema kann ihn nicht tragen. Das Ticket ist präzise; `namesIdentitySequences`
gehört für PostgreSQL auf `false`.

### 4. Die gemeinsame Ursache — neu, und der eigentliche Slice

**Die Kapazitäts-Projektion wirkt nur auf den Fingerabdruck, nicht auf den
Comparator.**

`SchemaMigrateWiring` verdrahtet den Comparator als
`SchemaComparator(canonicalizeType)` — er bekommt **ausschließlich** den
Typ-Kanonisierer. `capabilityIndexCanonicalizer`,
`capabilityGenerationCanonicalizer` und `capabilityPartitionCanonicalizer`
gehen nur in `MigrationFingerprint.project/compute` ein.

Und der Comparator vergleicht die betroffenen Felder wortgleich:

- `TableComparator.projectIndex` blendet nur `fullTextVectorColumn` und
  `fullTextAccessMethod` aus; `textSearchConfig` bleibt, und der Vergleich
  ist `projectIndex(l) == projectIndex(r)`.
- Spalten: `left.generation == right.generation`, ohne jede Projektion.

Das heißt: **auch Oracle konvergiert heute nicht.** Slice 8 hat
`carriesFullTextConfiguration = false` gesetzt, damit schweigt der
Post-Compare — aber der nächste `schema migrate`-Lauf plant denselben
`DropIndex` + `AddIndex` erneut, weil der Comparator die Konfiguration
weiterhin sieht. Dasselbe gilt für den Identity-Sequenznamen. Die Flags
haben die *Meldung* geheilt, nicht die *Ursache*.

Das ist die schlechtere Hälfte des Fehlers: eine Migration, die „keine
Drift" meldet und trotzdem bei jedem Lauf dieselbe Änderung plant, sieht
gesund aus.

### 5. Die Blockade, an der alle drei Tickets hingen — aufgelöst

Alle drei begründen ihr Nichtstun damit, dass eine Fingerabdruck-Änderung
„bestehende Rollback-Artefakte entwertet" und bei Ticket 3 sogar „still
bricht, statt mit einer Meldung, die den Grund nennt".

Das trifft nicht zu. `SchemaRollbackRunner` prüft vor dem Vergleich:

```kotlin
if (parsed.fingerprintAlgorithm != MigrationFingerprint.ALGORITHM) {
    ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH … "regenerate the rollback artefact"
}
```

Das Artefakt führt `fingerprintAlgorithm` als Pflichtfeld
(`RollbackArtefactParser`), und die Meldung nennt beide Versionen und den
Ausweg. Ein Versionssprung bricht also **benannt**, nicht still — genau das,
was Ticket 3 als fehlend annahm. Damit ist Richtung 1 („Flag umstellen +
`ALGORITHM` anheben") gangbar und die aufgeschobene
„Artefakt-Kompatibilitätsentscheidung" beantwortet.

## Ziel

Die Kapazitäts-Projektion gilt an **allen** Stellen, die entscheiden, ob
etwas als Unterschied zählt — nicht nur dort, wo Drift gemeldet wird.
Danach konvergiert eine Migration gegen einen Dialekt, der ein Feld nicht
zurückgeben kann, statt es bei jedem Lauf erneut zu planen.

## Schnitt

- **A — Die Projektion an den Comparator.** ✅ `TargetProjection` bündelt
  Typ-, Index-, Generation- und Partitions-Projektion; `SchemaComparator`
  nimmt sie entgegen, der Runner baut sie aus den Dialekt-Fähigkeiten.

  **`CanonicalPayload` wandert nicht mit.** Der Drei-Projektionen-Vertrag
  betrifft die Frage „ist dieses Feld semantisch" (dialektunabhängig); hier
  geht es um „kann dieser Dialekt es zurückmelden". Zwei Achsen. Weil die
  Projektion ausschließlich die *Vergleichsentscheidung* trifft und die
  geplante Operation die unprojizierten Definitionen behält, bleiben
  Operations-IDs und die erzeugte DDL unberührt.

  Zwei Dinge kamen beim Bauen dazu:

  - Die **Partitions**-Projektion gehört ins Bündel. Der Comparator leitet
    zwar über `PartitionBoundNormalizer` die unteren RANGE-Grenzen ab, aber
    nicht Modulus/Remainder einer HASH-Partition und nicht die
    Mitternachts-Faltung.
  - `compareIndices` ordnete Indizes über den **unprojizierten** Namen zu —
    die Projektion lief nur im `changed`-Zweig. Wo ein Dialekt den
    Volltext-Namen nicht ablegt (SQL Server), synthetisiert sein Reverse
    einen anderen, die Schlüsselmengen waren disjunkt, und aus einem
    unveränderten Index wurden `DropIndex` + `AddIndex` — bei sauberem
    Fingerabdruck. Die Zuordnung läuft jetzt über den gefalteten Schlüssel.
- **B — Die belegten Flags.** ✅ `carriesFullTextConfiguration = false` für
  MySQL, SQLite und SQL Server; `namesIdentitySequences = false` für
  PostgreSQL. Die KDocs der vier Fähigkeiten sagen jetzt, was gemessen wurde,
  statt „nicht geprüft".
- **C — `MigrationFingerprint.ALGORITHM` v11 → v12.** ✅ Die Handbuch-Zusage
  stand bereits: sowohl der Migrate-Abschnitt als auch die Fehlerbehebung
  erklären `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` samt Lösung, und eine
  Versionsnummer, die veralten könnte, steht dort nirgends.
- **D — Die falschen Aussagen einsammeln.** ✅ Der KDoc von
  `carriesPartitionLowerBounds` ist korrigiert (er war die Quelle des
  Irrtums), das Partitions-Ticket steht auf `refuted` mit der Messung darin,
  die beiden von B aufgelösten Tickets auf `resolved`, und der Verweis im
  Oracle-Plan sagt jetzt das Gegenteil des alten Satzes.

  Der echte SQL-Server-Befund ist eigenständig abgelegt:
  [`mssql-hash-emulation-not-round-trippable.md`](../open/mssql-hash-emulation-not-round-trippable.md)
  — eine emulierte HASH-Partitionierung kommt als RANGE zurück, mit anderem
  Schlüssel und einer zusätzlichen Spalte im Modell. Drei Abweichungen
  zugleich; keine Feld-Projektion hat dafür einen Griff.

## Akzeptanzkriterien

- Ein zweiter `schema migrate --execute`-Lauf gegen dasselbe, frisch
  migrierte Ziel plant **null** Operationen — für einen Volltext-Index mit
  Text-Search-Konfiguration gegen Oracle, MySQL, SQLite und SQL Server, und
  für eine IDENTITY-Spalte gegen PostgreSQL. Live belegt, nicht als
  Unit-Test behauptet: genau diese Kette hat der bisherige Test nicht
  abgedeckt.
- Ein Rollback-Artefakt aus einer v11-Version meldet
  `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` mit beiden Versionsnamen.
- Ein **echter** Unterschied wird weiterhin gemeldet: eine geänderte
  Text-Search-Konfiguration gegen PostgreSQL (das sie führt) bleibt eine
  Änderung.

## Nicht-Scope

- `raw-sql-text-drift` — dort trägt die Projektion ausdrücklich **nicht**
  (der Text *ist* die Aussage); der Entwurf liegt als ADR 0053 `proposed`
  vor und wird getrennt entschieden.
- Die Partitions-Flags für MySQL und SQL Server (siehe oben: widerlegt).
