# Grenzänderungen an einer Partitionierung als Operation

> **Status:** In Arbeit — P0 bis P4 geliefert, P5 (Matrix, Spec, Handbuch) läuft.
> **Ziel:** Eine hinzugekommene oder entfallene Partition wird als
> Migrations-Operation ausgeführt statt als Warnung gemeldet. Der Wechsel der
> Strategie oder des Schlüssels bleibt, was er ist: nur über Neubau erreichbar.
> **Vorbedingungen:** keine. Berührt sich mit
> [`partition-mapping-overlay.md`](../next/partition-mapping-overlay.md), hängt aber
> nicht davon ab — siehe Abschnitt 3.
> **Live belegt:** SQL Server (`SPLIT` behält die Zeilen), MySQL (`ADD` hinter
> der letzten Grenze, `REORGANIZE` beim Neuschnitt), PostgreSQL (Kindtabelle
> entsteht; Entfernen hängt an `--allow-destructive`; die DEFAULT-Partition
> lehnt der Server selbst ab).

Absorbiert die Vorabklärung `open/partition-boundary-change-operation.md`.

## 1. Ausgangslage

`TableComparator` erkennt eine Partitionierungsänderung und legt sie als
`TableDiff.partitioning` ab.
[`OperationMapper.mapTablePartitioning`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/OperationMapper.kt)
macht daraus keine Operation, sondern eine Warnung
(`PARTITIONING_CHANGE_NOT_APPLIED`) — für alle vier Dialekte, mit einer
Begründung, die für den Strategiewechsel stimmt und für den Normalfall nicht:

> a table's partitioning cannot be altered in place

Das gilt für die Partitionierung **als Ganzes**. Eine einzelne Partition
hinzuzufügen oder zu entfernen ist dagegen in jedem partitionierenden Dialekt
eine gewöhnliche Anweisung. Rollierende Partitionierung — monatlich eine neue
Partition, die älteste weg — ist der Normalfall, und heute bekommt sie bei
jedem Lauf dieselbe Warnung und muss von Hand nachgezogen werden.

## 2. Was die Dialekte können

| Dialekt | Kind hinzu | Kind weg | Datenwirkung des Entfernens |
| --- | --- | --- | --- |
| PostgreSQL | `CREATE TABLE kind PARTITION OF eltern FOR VALUES …` | `DROP TABLE kind` (oder `DETACH`) | `DROP` verliert die Zeilen; `DETACH` behält sie in einer Tabelle, die das SOLL-Schema nicht kennt |
| MySQL | `ALTER TABLE t ADD PARTITION (…)` — nur **hinter** der letzten Grenze; sonst `REORGANIZE PARTITION` | `ALTER TABLE t DROP PARTITION p` | Zeilen sind weg |
| SQL Server | `ALTER PARTITION SCHEME … NEXT USED` + `ALTER PARTITION FUNCTION … SPLIT RANGE (v)` | `ALTER PARTITION FUNCTION … MERGE RANGE (v)` | `MERGE` behält die Zeilen, sie wandern in die Nachbarpartition |
| SQLite | — kennt keine Partitionierung | — | — |

Drei Dialekte, drei verschiedene Datenwirkungen beim Entfernen. Das ist kein
Detail, sondern bestimmt die Risikoeinstufung (Abschnitt 5).

## 3. Der Kern: Identität einer Partition ist nicht ihr Name

Ein Abgleich der Kinder über den **Namen** wäre der naheliegende Weg und ist
falsch. SQL Server speichert keine Partitionsnamen; der Reverse vergibt
`p1…pn+1` in Grenzreihenfolge und meldet das mit `R346`
([`MssqlSchemaReader.kt`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlSchemaReader.kt)).
Eine **eingefügte** Grenze verschiebt damit jede Nummer dahinter: aus zwei
Partitionen `p1, p2` werden `p1, p2, p3`, und `p2` bezeichnet vorher und
nachher etwas anderes. Ein namensbasierter Abgleich meldete dort einen
Umbau, wo eine Grenze dazukam.

Die Identität einer Partition ist deshalb ihre **Grenzsignatur** — je nach
Strategie das `from`/`to`-Tupel, die `values`-Menge, `modulus`/`remainder`
oder das `isDefault`-Flag. Der Name ist Beiwerk; er wird gerendert, aber nicht
verglichen.

Das ist zugleich der Grund, warum dieser Plan **nicht** auf
[`partition-mapping-overlay.md`](../next/partition-mapping-overlay.md) wartet: das
Overlay stellt Namensidentität her, die ein signaturbasierter Abgleich gar
nicht braucht. Beide Pläne bleiben unabhängig gültig — wo das Overlay
Kindnamen beisteuert, rendert diese Operation sie, statt `p3` zu erfinden.

## 4. Der Schnitt: Klassifikation im Hexagon, Ausführung im Dialekt

Ein Kind-Delta ist nicht in jedem Dialekt eine Kind-Anweisung. In SQL Server
ist das Einfügen einer Grenze ein `SPLIT` — im neutralen Modell erscheint es
als **ein entferntes und zwei hinzugekommene** Kinder, weil die MSSQL-Lesung
die Zahlenachse lückenlos abdeckt. Wer das als „drop, dann zweimal add"
ausführte, verlöre die Zeilen der aufgeteilten Partition.

Daraus folgt der Schnitt:

- **Das Hexagon klassifiziert** und trägt das Ergebnis vollständig: die
  Partitionierung vorher, die nachher, und die daraus berechnete Aufteilung in
  hinzugekommene, entfallene und unveränderte Kinder — alles signaturbasiert.
- **Der Dialekt entscheidet, wie er das ausführt.** SQL Server rechnet die
  Klassifikation in ein Grenz-Delta zurück (Grenzwerte hinzu = `SPLIT`, weg =
  `MERGE`) und kommt so ohne Datenverlust aus. PostgreSQL und MySQL rendern
  Kind-Anweisungen.
- **Was ein Dialekt nicht ausführen kann, meldet er** — mit benanntem Grund
  statt der pauschalen Warnung.

Eine Operation je Kind wäre der falsche Schnitt: sie zerschnitte den `SPLIT` in
Teile, die einzeln nicht ausführbar sind.

## 5. Risiko und Umkehrbarkeit

Das Entfernen einer Partition ist in zwei von drei Dialekten Datenverlust, im
dritten nicht. Die Einstufung im Hexagon kann das nicht je Dialekt
unterscheiden und wird deshalb **konservativ** getroffen: entfällt ein Kind,
ist die Operation `destructive` + `dataLossPossible` +
`requiresManualConfirmation` (`--allow-destructive`). Reine Zugänge sind
`SAFE`; ihre Down-Richtung ist es nicht, dieselbe Asymmetrie wie bei
`AddColumn`.

## 5a. Was die Live-Läufe geändert haben

Zwei Annahmen des Entwurfs haben gegen echte Server nicht gehalten:

**Ein entfallenes Kind ist nicht dasselbe wie ein verlorener Bereich.** In der
SQL-Server-Lesart decken die Partitionen die Zahlenachse lückenlos ab, also
ersetzt jede eingefügte Grenze ein Kind durch zwei. Die erste Fassung stufte
das als zerstörend ein — das Hinzufügen einer Partition verlangte
`--allow-destructive` und lief in Exit 8. Das Delta unterscheidet jetzt
zwischen einem **Neuschnitt** (`PartitionRecut`: derselbe Bereich, anders
geteilt) und einem echten Wegfall.

**Die Untergrenze fehlt auf einer Seite.** MySQL beschreibt eine
RANGE-Partition nur über ihre Obergrenze, und so schreibt man sie auch in eine
Schemadatei; der Reverse rechnet die Untergrenze aus und liefert sie mit. Ohne
Ausgleich beschreiben beide Seiten dieselbe Tabelle verschieden — der Vergleich
meldete eine Änderung, die es nicht gab, und aus einem Zugang wurde ein
vollständiger Umbau. `PartitionBoundNormalizer` ergänzt die fehlende
Untergrenze aus der Reihenfolge; der `TableComparator` benutzt ihn ebenso, denn
der Fehlbefund lag nicht am neuen Schnitt, sondern bestand vorher.

## 6. Arbeitspakete

| AP | Inhalt | Fertig, wenn |
| --- | --- | --- |
| **P0** | Hexagon: Grenzsignatur, Klassifikation (`PartitionDelta`), Operation, Risiko/Umkehrbarkeit; die nicht auflösbaren Fälle (Strategie, Schlüssel, Partitionierung ganz hinzu/weg) behalten die Warnung, aber mit benanntem Grund je Fall | Der Mapper liefert für ein reines Kind-Delta eine Operation, für alles andere eine Warnung, die sagt **welcher** Fall vorliegt |
| **P1** | PostgreSQL: Kind hinzu/weg. Die `DEFAULT`-Partition ist der Sonderfall — ein neues Kind, dessen Bereich in der DEFAULT-Partition liegt, lässt PostgreSQL nur anlegen, wenn dort keine passenden Zeilen stehen. Live messen, nicht annehmen | Live gegen PG belegt, inkl. des DEFAULT-Falls |
| **P2** | MySQL: `ADD PARTITION` beim Anhängen hinter der letzten Grenze, `REORGANIZE PARTITION` beim Einfügen dazwischen, `DROP PARTITION` beim Entfernen | Live gegen MySQL belegt, beide Einfügefälle unterschieden |
| **P3** | SQL Server: Klassifikation → Grenz-Delta; `NEXT USED` vor jedem `SPLIT`; `MERGE` beim Entfernen. Die Filegroup für `NEXT USED` kommt aus demselben Profil wie beim Generate | Live gegen SQL Server belegt; eine eingefügte Grenze erzeugt **einen** `SPLIT`, keinen Neubau |
| **P4** | SQLite: benannte Ablehnung statt der allgemeinen Partitionierungswarnung | Die Meldung nennt SQLite als Grund |
| **P5** | Cross-Dialekt-Matrix, Spec (`ddl-generation-rules.md`, Abschnitt Partitionierung je Dialekt), Anwenderhandbuch | `make docs-check` grün, Matrix-Zellen belegt |

## 7. Nicht-Scope

- **Strategie- oder Schlüsselwechsel.** Bleibt Neubau; die Warnung bleibt, sie
  wird nur genauer.
- **Grenzverschiebung eines bestehenden Kindes** (gleiche Signaturstelle,
  anderer Wert). In PostgreSQL und MySQL ist das Abhängen und Neuanhängen mit
  Datenbewegung; das ist eine eigene Frage.
- **Sub-Partitionen** (MySQL) und **RANGE LEFT** (SQL Server, `R347`).
- **HASH.** Ein Modulus-Wechsel verteilt jede Zeile neu; das ist kein
  Grenz-Delta.

## 8. Abnahmekriterien

- Ein Lauf, der genau eine Partition hinzufügt, erzeugt in PostgreSQL, MySQL
  und SQL Server je eine Migration ohne Warnung, und der Post-Compare ist
  danach sauber.
- Ein Lauf, der genau eine Partition entfernt, verlangt `--allow-destructive`.
- Eine in der Mitte eingefügte Grenze erzeugt in SQL Server einen `SPLIT` und
  in MySQL ein `REORGANIZE` — nicht Drop+Create.
- Ein Strategiewechsel erzeugt weiterhin keine Operation, und die Warnung
  benennt den Grund.
