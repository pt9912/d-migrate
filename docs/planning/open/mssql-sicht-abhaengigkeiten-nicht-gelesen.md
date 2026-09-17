# MSSQL-Reverse: Sichten tragen keine `dependencies`

> **Status:** Befund / Vorabklärung (2026-09-17). Am Code gelesen,
> **nicht** gegen einen Server gemessen.
> **Trigger:** Die zweite Hälfte von Punkt 5 des Umbrella
> [`compare-falsch-positive-cross-dialekt.md`](../in-progress/compare-falsch-positive-cross-dialekt.md)
> („MSSQL-View ohne `columns`"). Er nannte beide Felder eine Datenlücke beim
> Lesen und wollte sie mit einem eigenen Fix im MSSQL-Adapter schließen. Die
> Spalten sind seit `1b7e5f517` (1.7.0) da, die `dependencies` nicht. Bei der
> Graduation des Umbrella hatte der Punkt keinen Ort.
> **Aktivierungsbedingung:** Die Messung unten ergibt einen Fehler, oder ein
> Slice fasst den MSSQL-Reader oder den Rename-Pfad von `schema migrate` an.

## Befund

- **Der Reader füllt das Feld nicht.** `MssqlSchemaReader.readViews` baut
  `ViewDefinition` aus Rumpf, Spalten und `sourceDialect`. `dependencies` bleibt
  `null`. Die Kanten liest der Reader für Routinen und Trigger bereits aus
  `sys.sql_expression_dependencies`
  (`MssqlRoutineQueries.listRoutineDependencies`), aber die Abfrage filtert auf
  `o.type IN ('P', 'FN', 'IF', 'TF', 'TR')`. Sichten (`'V'`) fehlen.
- **Die anderen Server-Dialekte füllen es.** PostgreSQL
  (`PostgresSchemaProgrammabilityReaders`), MySQL (`MysqlRoutineReader`) und
  Oracle (`OracleSchemaReader`) setzen `dependencies` an Sichten. SQLite setzt es
  ebenfalls nicht (`SqliteSchemaReader`); dort hat der Befund keine der Folgen
  unten, weil die SQLite-Rename-Politik keine Sichten neu projiziert.
- **`schema compare` ist nicht betroffen.** `ViewDiff` hat kein solches Feld,
  deshalb entsteht kein Fehlalarm. Das hat der Umbrella richtig eingeordnet.

## Wo das Feld wirkt

Der **Generate**-Pfad ist nicht betroffen:
`DdlGenerationSupport.sortViewsByDependencies` leitet die Reihenfolge
zusätzlich aus dem Rumpf ab. Der **Migrate**-Pfad liest nur das deklarierte
Feld:

1. **Umbenennen einer Tabelle unter einer Sicht (vermutlich die schwerste
   Folge).** `MssqlRenameDependencyPolicy` sagt ausdrücklich, dass abhängige
   Sichten nach `sp_rename` ihren alten Text behalten und ein explizites
   Drop+Create brauchen. Die Neuprojektion
   (`RenameViewReprojector.reprojectViewsDependingOn`) überspringt aber jede
   Sicht mit `dependencies == null` (`?: continue`), und zwar ohne Blocker. Der
   Ist-Stand kommt bei einem SQL-Server-Ziel aus diesem Reader. Ein Plan mit
   Tabellen-Rename lässt die Sicht deshalb vermutlich unverändert stehen, und
   sie bricht erst bei der nächsten Benutzung. Der Kommentar direkt darunter
   nennt genau das als Fehler („Still zu überspringen hieße, sie nach dem Rename
   gebrochen zurückzulassen"), gilt aber nur für eine *unbrauchbare*, nicht für
   eine *fehlende* Projektion.
2. **Reihenfolge.** `DependencyAnalyzer` hängt `CreateView` an die Tabellen und
   Sichten aus `view.dependencies` und `DropTable` an die `DropView`s, deren
   `dependencies.tables` die Tabelle nennen. Ohne das Feld fehlen diese
   Kanten.
3. **Fingerabdruck.** `CanonicalPayload` nimmt `dependencies` auf, wenn das Feld
   gesetzt ist. Ein MSSQL-Reverse und ein PostgreSQL-Reverse derselben Sicht
   liefern deshalb verschiedene Nutzlasten. Ob das über die bestehende
   Dialekt-Parametrisierung des Abdrucks hinaus etwas bewirkt, ist nicht
   geprüft.

## Was ein Schnitt braucht

1. **Zuerst messen.** Auf SQL Server eine Tabelle mit einer abhängigen Sicht
   anlegen, im Soll-Schema die Tabelle umbenennen (mit Rename-Overlay) und
   `schema migrate` planen und ausführen. Dabei auf `renameProjections[].explicit`
   achten. Danach die Sicht abfragen. Ein
   bestehender Test deckt das nicht ab: `MssqlObjectRenameIntegrationTest`
   benennt die Sicht selbst um, nicht die Tabelle darunter.
2. **Reader.** `sys.sql_expression_dependencies` auch für `'V'` lesen und
   `DependencyInfo` mit `tables`, `views` und `functions` füllen, samt
   Projektionsstatus. Dieselbe Quelle, eine Filterzeile mehr.
3. **Reprojektor.** Klären, ob `dependencies == null` beim Rename weiter
   „hängt an nichts" heißen darf, oder ob es wie eine unbrauchbare Projektion
   einen Blocker braucht. Das betrifft jede Sicht aus einer Datei ohne
   `dependencies`, nicht nur den MSSQL-Reverse. Das ist eine Vertragsfrage
   (`spec/cli-spec.md`, „Report-Felder für Rename-Projection“: heute nur
   `WARNING`-Blocker).
4. **Nachweis:** die Messung aus Schritt 1 als Integrationstest, mit Sabotage.
