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

## Warum es nicht im Slice behoben wurde

Zwei der fünf Fälle sind nicht offensichtlich zu beheben: `text` müsste sich
merken, ob es aus `CLOB` kam (das neutrale Modell führt dafür `maxLength`, das
eine Signatur aber nicht tragen darf), und `date` gegen `datetime` ist bereits
in `OracleTypeMapping.mapTemporal` für Spalten so entschieden.

## Was zu entscheiden ist

1. Ob eine Notiz je verengtem Parameter genügt (billig, sichtbar) oder ob das
   neutrale Modell die Herkunft tragen soll (teuer, verlustfrei).
2. Ob `CLOB` als Parametertyp durchgereicht werden soll — `IN CLOB` ist
   gültiges PL/SQL und wäre die verlustfreie Wahl, verlangt aber, dass der
   neutrale Name die LOB-Eigenschaft trägt.

## Berührte Stellen

- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleTypeMapping.kt`
  (`mapParamType`)
- `adapters/driven/driver-oracle/src/main/kotlin/dev/dmigrate/driver/oracle/OracleRoutineDdl.kt`
  (`paramTypeSql`)
