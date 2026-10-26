# MSSQL: die Datumspräzision einer berechneten Spalte driftet in einem Zyklus auf (7)

## Befund

`MssqlTypeMapper.castTargetSql` rendert `TIME`, `DATETIME2` und
`DATETIMEOFFSET` für die `CAST`-Hülle berechneter Spalten hart mit
Präzision `(7)` (`MssqlTypeMapper.kt:124-126`). Das neutrale Modell trägt
keine Datumspräzision — `NeutralType.DateTime` kennt nur `timezone` —, die
Angabe kann also nicht aus der Schemadatei kommen.

Eine Autorenhülle `CAST(x AS datetime2(3))` wird beim Lesen korrekt
zurückgeführt (der Katalogtext `datetime2(3)` entspricht dem Spaltentyp,
`MssqlServerCast.withoutServerTypeCast` trennt sie ab), aber das Modell
drückt die Präzision nicht aus: die nächste Generierung hüllt auf
`DATETIME2(7)`. In einem Hin-und-Rück-Zyklus driftet die deklarierte
Präzision 3 → 7.

## Bewertung

- Der Drift ist **laut**: `schema compare` meldet ihn als
  `TABLE_COLUMN_TYPE_CHANGED` — kein stiller Verlust im Sinne von D1.
- Das Modelllimit ist bewusst: die Abgrenzung des Reader-Treue-Umbrella
  schließt Modellerweiterungen aus. Dasselbe Limit gilt für den
  Regel-Spalten-Pfad.
- Der Fall ist in der Messung 1 von Plan 4 (reader-treue-4) angelegt:
  `CAST(created_at AS datetime2(3))` legt der Server als
  `CONVERT([datetime2](3),…)` ab — die Rückführung trifft, die Präzision
  geht im Modell verloren.

## Ausweg

Entweder ein Präzisionsattribut am Neutraltyp (Modellerweiterung, bräuchte
eine eigene Entscheidung) oder der Verzicht auf die hart gerenderte
Präzision, wo der Katalog sie parametrisiert halten kann — beides
Geschmacksfragen über D1 hinaus.
