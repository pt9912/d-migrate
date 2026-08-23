# MSSQL: spaltenlevel `references` im Diff-Pfad

> Status: **offen, nicht gebaut.** Gefunden beim Review von Sub-Slice 5a-2
> ([`../in-progress/mssql-dialect-scoping.md`](../in-progress/mssql-dialect-scoping.md)),
> dort bewusst **nicht** mitgelöst — die Fläche gehört zu Slice 5a/5b und braucht
> einen eigenen Schnitt.
> Severity: **stiller Verlust**, kein Fehlschlag. Die Migration läuft durch, die
> Beziehung fehlt danach. Sichtbar erst im Postcompare.

## Der Befund

Das neutrale Modell kennt zwei Formen für denselben Fremdschlüssel: als Eintrag
in `table.constraints` und als `references` an einer Spalte. Der Generate-Pfad
rendert **beide** — `MssqlDdlGenerator.generateTable` hat dafür zwei getrennte
Schleifen und macht aus der zweiten Form `fk_<tabelle>_<spalte>`.

Der Diff-Pfad rendert nur die erste:

| Operation | `table.constraints` | `column.references` |
| --- | --- | --- |
| `CreateTable` | ✅ inline | ❌ fällt weg |
| `AddColumn` | — | ❌ fällt weg |
| Tabellen-Neubau (5a-2) | ✅ | ✅ |
| Spaltentanz (Abräumen/Wiederherstellen) | ✅ | ✅ (seit 5a-2) |

Eine mit `schema migrate` angelegte Tabelle verliert also ihre spaltenlevel
Fremdschlüssel, während dieselbe Tabelle über `schema generate` sie bekommt.
Beide Pfade müssen für dasselbe Schema dieselbe Datenbank bauen.

Nicht aufgefallen ist das bis 5a-2, weil der MSSQL-Reverse `column.references`
nie setzt (er hebt jeden Fremdschlüssel auf die Constraint-Liste) und die
Fixtures die Form nicht benutzten. Erreichbar ist sie über hand-geschriebene
Schemata — und abwärts liest der Spaltentanz seine Abhängigkeiten aus dem
Soll-Schema (`schemaOppositeOfDirection()`), also aus YAML.

## Was der Schnitt klären muss

- **Wer rendert.** `CreateTable` inline (wie `generateTable`) oder als
  nachgelagertes `ALTER TABLE`? Inline ist näher am Generate-Pfad, nachgelagert
  löst die Reihenfolge bei gegenseitigen Verweisen.
- **Eine Entscheidung, nicht zwei.** Ob eine Operation einen Fremdschlüssel
  anlegt, fragt seit 5a-2 auch die Buchhaltung
  ([`MssqlDiffColumnDependencies.materialisedBy`](../../../adapters/driven/driver-mssql/src/main/kotlin/dev/dmigrate/driver/mssql/MssqlDiffColumnDependencies.kt)
  über `InboundForeignKey.fromColumn`). Rendern und Buchführen müssen dieselbe
  Funktion fragen, sonst laufen sie auseinander — das war in Runde 5 des
  5a-2-Reviews der HIGH-Befund.
- **Die Doppelform.** Führt das Modell denselben Fremdschlüssel in beiden
  Formen unter demselben Namen, darf er nur einmal entstehen (Msg 2714). Unter
  verschiedenen Namen entstehen heute im Generate-Pfad zwei — ob das gewollt
  ist, gehört mitentschieden.
- **Andere Dialekte.** Die Lücke ist an MSSQL aufgefallen; ob PostgreSQL, MySQL
  und SQLite ihre `column.references` im Diff-Pfad rendern, ist ungeprüft.
