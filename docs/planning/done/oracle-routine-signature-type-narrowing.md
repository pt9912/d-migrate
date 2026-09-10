# Oracle-Routinensignaturen: stille Typverengungen im Round-Trip

## Befund

Eine PL/SQL-Signatur trägt keine Länge — `IN VARCHAR2(10)` erzeugt die Routine
`INVALID`. Der Reverse legt Parametertypen deshalb ohne Länge ab, und der
Generate-Pfad rendert sie ohne Länge. Für einige Typen ist der Rückweg dabei
aber **enger** oder anders als der Hinweg, ohne dass eine Meldung entsteht:

| Original | neutral | wieder gerendert | Folge |
| --- | --- | --- | --- |
| `CLOB` / `NCLOB` / `LONG` | `text` | `VARCHAR2` | PL/SQL-`VARCHAR2` endet bei 32767 Zeichen; ein CLOB-Parameter wird begrenzt |
| `CHAR` | `text` | `VARCHAR2` | keine Auffüllung mehr auf feste Länge |
| `DATE` | `datetime` | `TIMESTAMP` | anderer Typ, gleiche Bedeutung — Überladungen könnten kollidieren |
| `BINARY_FLOAT` | `float` | `BINARY_DOUBLE` | Verbreiterung, kein Verlust |
| `RAW` | `binary` | `BLOB` | anderer Speicherweg |

Anders als bei `R362`/`R363` gibt es dafür keine Notiz. Der Kontrast fällt auf:
der Slice meldet sorgfältig, was er *nicht* darstellen kann, schweigt aber zu
dem, was er *anders* darstellt.

## Was der Vergleich mit dem Spalten-Renderer zeigte

Drei der fünf Fälle waren gar keine Modell-Lücke, sondern eine **Abweichung
zwischen Spalten- und Parameter-Renderer**. Für denselben neutralen Typ rendert
d-migrate an einer Spalte etwas anderes als an einem Parameter:

| neutral | Spalte | Parameter (vorher) |
| --- | --- | --- |
| `text` (unbegrenzt) | `CLOB` | `VARCHAR2` |
| `char` | `CHAR(n)` | `VARCHAR2` |
| `binary` | `BLOB` | `BLOB` ✓ |

Und die anderen Dialekte rendern einen `text`-Parameter ebenfalls unbegrenzt:
PostgreSQL `text`, SQL Server `NVARCHAR(MAX)`. Oracle war der Ausreißer.

## Was gegen ein echtes Oracle gemessen wurde

| Gemessen | Ergebnis |
| --- | --- |
| `IN CLOB`, `IN CHAR`, `IN DATE`, `IN BINARY_FLOAT`, `IN RAW`, `IN NCLOB`, `IN LONG`, `IN OUT CLOB` | alle **VALID**; `ALL_ARGUMENTS` führt den exakten Typ |
| `IN VARCHAR2(10)` | **INVALID** — und `ALL_ARGUMENTS` führt dann gar keine Argumente |
| CLOB-Parameter mit einem kurzen Literal | **OK** |
| VARCHAR2-Parameter mit einem 65534-Zeichen-CLOB | **ORA-06502** |
| CLOB-Parameter mit demselben Wert | **OK** |
| zweite `CREATE OR REPLACE FUNCTION` gleichen Namens, anderer Parametertyp | **ersetzt** die erste — freistehende Routinen überladen nicht |

Damit beantwortet sich Entscheidung 2 („Ob `CLOB` durchgereicht werden soll")
ohne Modelländerung: `text` heißt schon überall unbegrenzter Text, und `CLOB`
ist die einzige Oracle-Form, die dem entspricht.

## Was gebaut wurde

- `text` → `CLOB` und `char` → `CHAR` (statt beides `VARCHAR2`). `email`,
  `enum` und `uuid` bleiben `VARCHAR2` — sie sind ihrer Natur nach begrenzt.
- Der Reverse liest `CHAR`/`NCHAR` als `char` statt als `text`.
- **R368**: eine Notiz je Parameter, dessen Typ den Rückweg nicht unverändert
  übersteht. Welche das sind, entscheidet nicht eine gepflegte Liste, sondern
  der Rückweg selbst — gelesen und wieder gerendert. Die Antwort kann damit
  nicht veralten, wenn sich eine der beiden Richtungen ändert.

Damit ist auch Entscheidung 1 beantwortet: eine Notiz je verengtem Parameter,
und zwar abgeleitet statt gepflegt.

## Die Folge, die benannt gehört

`VARCHAR2` übersteht den Rückweg jetzt **ebenfalls nicht** — er kommt als
`CLOB` zurück, und das ist der häufigste Parametertyp überhaupt. R368 meldet
das entsprechend oft.

Das ist bewusst so. Die Kosten sind asymmetrisch: die Verengung
(`CLOB` → `VARCHAR2`) bricht den Aufruf zur Laufzeit, gemessen mit `ORA-06502`;
die Verbreiterung (`VARCHAR2` → `CLOB`) bricht keinen — `CLOB` nimmt an, was
`VARCHAR2` annimmt. Der übliche Einwand gegen eine Verbreiterung, kollidierende
Überladungen, greift hier nicht: freistehende Oracle-Routinen lassen sich nicht
überladen (gemessen), und Überladung in Packages trägt das neutrale Modell
ohnehin nicht (`R342`).

## Was bleibt, wie es war — mit Begründung

| Fall | warum keine Änderung |
| --- | --- |
| `DATE` → `datetime` → `TIMESTAMP` | `datetime` fasst `DATE`, `TIMESTAMP` und `TIMESTAMP WITH TIME ZONE` zusammen. `DATE` zu rendern verengte zwei der drei; `TIMESTAMP` ist der breiteste. Der Spalten-Renderer wählt hier `DATE`, weil eine Spalte den Unterschied über `timezone` trägt — ein Parametername tut das nicht. |
| `RAW` → `binary` → `BLOB` | `binary` trägt keine Länge, an einer Spalte ebenso wenig. Der Spalten-Pfad rendert `Binary` gleichfalls als `BLOB`. |
| `BINARY_FLOAT` → `float` → `BINARY_DOUBLE` | Der neutrale Parametername trägt keine Gleitkomma-Genauigkeit — die einzige echte Vokabular-Lücke der fünf. Verbreiterung, kein Verlust. |

Alle drei melden R368.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleTypeMapping.kt`
  (`mapParamType`)
- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleRoutineDdl.kt`
  (`paramTypeSql`)
