# SQLite: `ADD COLUMN` einer Identity-Spalte verliert den Autowert, und kein Code sagt es

> Status: **Draft (Trigger Watch)**
> Trigger: Befund M2 der Korrekturrunde zu Plan 2 des Reader-Slices
> ([`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md)).
> Der Fix dort behebt eine **abgelehnte Anweisung**; was danach bleibt, ist
> ein stiller Verlust.
> Severity: **P3** (schmal — nur wer eine Spalte mit `generation: identity`
> per `schema migrate` an eine bestehende SQLite-Tabelle anfügt).
> Aktivierungsbedingung: ein Plan, der W-Codes vergibt und registriert (die
> fünf Registrierungsorte), oder eine Konsumentenmessung, die den Fall trifft.

## Befund

`SqliteDiffSimpleOps.renderAddColumn` rief `columnLine` ohne den Parameter
`isSolePrimaryKey`; dessen Default war `true`, und damit schrieb **jedes**
`ADD COLUMN` einer Identity-Spalte ein
`INTEGER PRIMARY KEY AUTOINCREMENT`. SQLite lehnt das ab („Cannot add a
PRIMARY KEY column"), und erklärt hätte die Anweisung einen Schlüssel, den das
Soll nicht nennt. **Das ist behoben** (Plan 2, Korrekturrunde): die Stelle
rendert die Spalte als gewöhnliche Deklaration, und der Parameter hat keinen
Default mehr.

**Was bleibt:** die Spalte entsteht damit **ohne** Autowert. SQLite kann einen
rowid-Alias per `ALTER TABLE` nicht anlegen — der Verlust ist unvermeidbar.
Gemeldet wird er nicht:

- `W163` ([`SqliteIdentityModeDegradation`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteIdentityModeDegradation.kt))
  kehrt für `isSolePrimaryKey = false` früh zurück — richtig, denn sein Text
  („der Modus `always` ist nicht durchgesetzt") setzt einen Autowert voraus,
  den es hier gar nicht gibt.
- `W135` ([`SqliteCompositePkIdentity`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteCompositePkIdentity.kt))
  trifft die Lage inhaltlich („AUTOINCREMENT dropped"), aber sein Text nennt
  als Grund den **zusammengesetzten Primärschlüssel** — den es hier ebenfalls
  nicht gibt. An dieser Stelle wird er nicht gerufen.

## Nebenbefund am selben Prädikat

`SqliteCompositePkIdentity.isDroppedAutoincrement(col, isSolePrimaryKey)` ist
`!isSolePrimaryKey && SqliteRowidIdentity.inAnyForm(col)` — es trifft damit
auch eine Identity-Spalte, die **gar nicht** im Schlüssel liegt. Auf dem
`CREATE TABLE`-Pfad und im Tabellen-Neubau meldet sie deshalb heute schon
`W135` mit dem Satz „is part of a composite primary key", der für sie nicht
stimmt. Der Verlust ist benannt, der Grund falsch.

## Drei Wege (nicht entschieden)

1. **`W135` verallgemeinern** — das Prädikat bekommt die Information, ob die
   Spalte im Schlüssel liegt, und der Text sagt je Fall das Richtige
   (zusammengesetzter Schlüssel / gar kein Schlüssel / per `ALTER TABLE` nicht
   anlegbar). Der Code bleibt einer, der Nebenbefund oben fällt mit.
2. **Ein eigener Code** für „der Autowert entsteht auf diesem Pfad gar nicht"
   — sauber getrennt, kostet aber eine neue Kennung samt fünf
   Registrierungsorten.
3. **Blocken statt melden** — `ADD COLUMN` einer Identity-Spalte als
   `MANUAL_ACTION_REQUIRED`. Verhindert den stillen Verlust, nimmt aber eine
   Migration weg, die heute läuft und deren DDL gültig ist.

Weg 1 ist der wahrscheinlichste; er löst beide Befunde mit einer Änderung.
