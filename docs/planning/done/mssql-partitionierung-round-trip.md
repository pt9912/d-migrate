# SQL Server: Partitionierung round-trip-fähig machen

> **Status:** Erledigt. Live belegt gegen SQL Server 2022 — der bestehende
> Migrate-Test verlangt jetzt `migrateExit == 0` statt „0 oder 5" und geht
> durch.
>
> **Herkunft:** [`mssql-hash-emulation-not-round-trippable`](mssql-hash-emulation-not-round-trippable.md).
> Beim Messen kam ein zweiter, unabhängiger Grund dazu, aus dem
> **auch RANGE** nicht konvergiert.

## Zwei Gründe, warum `schema migrate` gegen SQL Server nie fertig wird

### 1. Der Partitionsname (betrifft RANGE **und** HASH)

SQL Server **nummeriert** Partitionen; die Namen des Soll-Schemas gibt es
dort nicht. Der Reverse vergibt deshalb `p1…pn` in Grenzreihenfolge und sagt
das mit `R346` an.

`CanonicalPayload.partitionConfig` trägt dem bereits Rechnung und lässt den
Namen **bewusst** aus dem Operations-ID-Schlüssel heraus — der Kommentar dort
nennt genau diesen Grund. `TableComparator.canonicalPartitions` tut das
nicht: es vergleicht ganze `PartitionDefinition`-Werte, und `name` gehört zur
Gleichheit einer data class.

Folge: ein Soll mit `partition_2024` gegen ein Ist mit `p1` unterscheidet
sich bei **jedem** Lauf. Die Payload sagt „derselbe Vorgang", der Vergleich
sagt „geändert" — dieselbe Inkonsistenz zwischen zwei der drei Projektionen,
die schon der Volltext-Indexname hatte.

Dafür gibt es das Muster bereits: `namesFullTextIndexes = false` blendet bei
SQL Server den Indexnamen aus, weil der Reverse ihn synthetisiert. Hier fehlt
das Gegenstück.

### 2. Die HASH-Emulation kommt als RANGE zurück

Im Modus `--mssql-hash-partitions computed_column` entsteht eine persistierte
berechnete Spalte plus eine RANGE-Funktion an den Eimergrenzen.
`MssqlSchemaReader` erzeugt aber ausschließlich `PartitionType.RANGE` — Soll
und Ist weichen danach an drei Stellen zugleich ab: Partitionstyp, Schlüssel
und der zusätzlichen Spalte im Modell.

## Was die Messung ergeben hat

Gegen SQL Server 2022, mit der Emulation von Hand nachgebaut:

| Frage | Messung |
| --- | --- |
| Wie legt der Server den Ausdruck ab? | `(abs(checksum([customer_id])%(4)))` — kleingeschriebene Funktionsnamen, Bezeichner in eckigen Klammern, das Literal geklammert |
| Und mehrspaltig? | `(abs(checksum([a],[b])%(2)))` — kommagetrennt, ohne Leerzeichen |
| Was liefert der Partitions-Scan? | Spalte `dmg_hash_bucket`, `boundary_value_on_right = 1`, Grenzen `1, 2, 3` bei Modulus 4 |
| Liest der Reverse die Spaltendefinition schon? | Ja — `MssqlMetadataQueries` holt `sys.computed_columns.definition` bereits als `computedDefinition` |
| Braucht das Anlegen besondere Sitzungsoptionen? | Ja, `QUOTED_IDENTIFIER ON` (sonst `Msg 1934`) — genau die Präambel, die `MSSQL_SCRIPT_PREAMBLE` führt |

Damit ist die Rekonstruktion vollständig bestimmt: der Schlüssel sind die
Bezeichner in `checksum(…)`, der Modulus die Zahl hinter `%`, und die
Grenzen `1…n-1` sind eine unabhängige Gegenprobe dafür.

## Schnitt

- **A — `namesPartitions`.** Neue Fähigkeit, für SQL Server `false`;
  `capabilityPartitionCanonicalizer` nullt den Namen dort. Behebt Grund 1 für
  RANGE und HASH zugleich und ist die kleinere Hälfte.
- **B — Die Emulation im Reverse wiedererkennen.** Trägt die
  Partitionsspalte den reservierten Namen und passt der Ausdruck auf die
  erzeugte Form, wird daraus `HASH(key, modulus)`; die Eimerspalte fällt aus
  dem Spaltenbestand und aus PK/UNIQUE-Schlüsseln heraus. Passt der Ausdruck
  **nicht**, bleibt es beim heutigen RANGE — erkennen oder zurückfallen, nie
  raten. Dieselbe Bauart wie `SqliteSequenceReverseSupport`, das
  `dmg_sequences` wiedererkennt.
- **C — `MigrationFingerprint.ALGORITHM` v12 → v13.** Beide Teile ändern
  MSSQL-Abdrücke.

## Akzeptanzkriterien

- Ein zweiter `schema migrate --execute`-Lauf gegen dasselbe, frisch
  migrierte SQL-Server-Ziel plant **null** Operationen — für eine
  RANGE-partitionierte Tabelle mit benannten Partitionen **und** für eine
  über die Emulation HASH-partitionierte. Live belegt, nicht als Unit-Test
  behauptet.
- Eine Tabelle mit einer *echten*, von Hand angelegten Spalte namens
  `dmg_hash_bucket`, die nicht auf die Emulationsform passt, wird **nicht**
  als HASH gelesen.
- Ein Rollback-Artefakt aus einer v12-Version meldet
  `ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH`.

## Was der Review gefunden hat

Neun Befunde, der schwerste an einer Stelle, die der Plan gar nicht im Blick
hatte:

| Befund | Warum es schiefging | Behoben durch |
| --- | --- | --- |
| Die Namensfaltung machte den **Fingerabdruck reihenfolgeabhängig** | `MigrationFingerprint` sortierte Partitionen nach `name`. Ein konstanter Schlüssel sortiert nicht mehr — die Reihenfolge war danach die der Liste, und Soll und Ist bringen sie verschieden mit (PG liest nach Kindnamen, der MSSQL-Reverse nach Resten). Der Comparator hätte „keine Änderung" gesagt, der Post-Compare Drift: Exit 5 statt 0 | Sortierung nach **Inhalt**, Name nur noch als Gleichstand — dieselbe Ordnung, die `CanonicalPayload` schon immer nutzt |
| Ein Index **nur** über der Eimerspalte wurde leer oder verschwand | Nach dem Ausblenden blieb `columns = []` stehen → `CREATE INDEX … ON [t] ()`; ein eindeutiger verschwand ganz ohne Meldung | Er entfällt benannt (`R366`) |
| Die Gegenprobe bewies nichts | Die Attrappe war **unpartitioniert** — die Erkennung bricht schon an der Partitionsabfrage ab, der Ausdruck wurde nie geprüft | Attrappe ist jetzt RANGE-partitioniert auf einer gleichnamigen Spalte mit anderem Ausdruck |
| `namesPartitions` war ungetestet | Die Fähigkeit war neu und nirgends geprüft — auch nicht, dass sie für die vier benennenden Dialekte **nicht** greift | Drei Tests, darunter der Round-Trip-Hash und die Reihenfolge-Unabhängigkeit |
| Der bestehende Migrate-Test duldete Exit 5 weiter | Seine Begründung („die Kindnamen überleben SQL Server nicht") ist mit Teil A weggefallen; ein Rückfall wäre nicht aufgefallen | `migrateExit shouldBe 0` |
| Handbuch und Spec sagten das Gegenteil | „Ein Reverse liest die Tabelle als `range` zurück" | Beide nachgezogen, dazu `R366` |
| Groß-/Kleinschreibung uneinheitlich | Die Emulation reserviert den Namen `ignoreCase`, der Reader verglich exakt | Angeglichen |
| Kein CHANGELOG-Eintrag zum Versionssprung | Betreibersichtbar: bestehende Artefakte müssen neu erzeugt werden | Eintrag für `v11 → v13` samt Ausweg |
| **Widerlegt:** die Eimerspalte lande in `includeColumns` | Gemessen: sie kommt mit `is_included_column = 0` und `key_ordinal = 0` als **Schlüssel**spalte zurück und sortiert damit sogar vorne. `withoutBucket` greift dort bereits — der Reverse läse sonst `(dmg_hash_bucket, amount)` statt `(amount)` | Kein Fix nötig; ein Test hält es fest |

## Nicht-Scope

- Die Zeilenzuordnung: SQL Server hasht anders als PostgreSQL oder MySQL,
  eine Zeile landet in einem anderen Eimer. Das ist der Emulation
  eigen (`W145`) und keine Round-Trip-Frage.
