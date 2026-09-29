# SQL Server: `schema migrate` plant eine Typänderung an einer berechneten Spalte, die der Server nicht ausführen kann

> **Status:** Befund (gemessen 2026-09-29, P13-Messung 3) / Folgeschnitt der
> Eigner-Entscheidung zu
> [`../in-progress/reader-treue-4-mssql-berechneter-typ.md`](../in-progress/reader-treue-4-mssql-berechneter-typ.md).
> **Trigger:** P13-Messung 3 — Soll = SQL-Server-Reverse mit abgeleitetem
> Typ, Ziel = Datenbank mit `CAST`-Hülle (dezimal(14,2)): `schema migrate`
> rendert `ALTER TABLE [orders] ALTER COLUMN [line_total] DECIMAL(25,4)
> NULL;` (`AUTOMATIC_WITH_DATA_RISK`) gegen eine berechnete Spalte — T-SQL
> ändert sie nicht in place (3.2a), der Server lehnt das `ALTER` ab.
> **Aktivierungsbedingung:** als Slice schneiden, wenn ein Konsument in
> `--execute` an dieser Blockade hängt oder der Identity-/Typ-Slice die
> Migrate-Naht ohnehin öffnet.

## Befund

`schema migrate` plant und rendert eine Typänderung an einer **berechneten**
Spalte, statt sie zu blocken. Der Fall ist heute schon erreichbar — der
Soll-Typ wird aus dem Ausdruck abgeleitet (`decimal(25,4)` für
`quantity * unit_price`), die Ziel-DB trägt eine eigene Autorenhülle auf
`decimal(14,2)` — und P13 (Variante a, gebaut) macht ihn zum Normalfall:
dann trägt jede generierte SQL-Server-DB die Hülle, und jedes ältere
Artefakt den abgeleiteten Typ.

Der Lauf ist **laut**: der Server lehnt das `ALTER` an der berechneten
Spalte ab — es entsteht kein stiller Schaden, aber der Plan ist unbrauchbar
und die Meldung sagt die Ursache nicht.

## Der Weg

Die Migrate-Naht erkennt eine Typänderung an einer berechneten Spalte und
**blockt** sie mit eigenem Code nach dem Muster der
`SPATIAL_METADATA_UNSUPPORTED`-Blockade (Grund: T-SQL ändert berechnete
Spalten nicht in place; der Ausweg ist ein Neubau der Tabelle oder ein
manuelles Eingreifen), statt das `ALTER` zu rendern. Offene Teilfrage: ob
die Paarung „älteres Artefakt mit abgeleitetem Typ gegen DB mit Hülle" —
beide Seiten aus derselben d-migrate-Generierung — vielmehr gefaltet wird
(sie ist exakt der D1-Drift, den P13 mit der Rückführung behoben hat).

## Belegstellen

- `MssqlColumnConstraintHelper` (Generierung des berechneten Ausdrucks).
- `DiffPlanner` / `AlterColumnType` (die gerenderte Operation).
- [`../in-progress/reader-treue-4-mssql-berechneter-typ.md`](../in-progress/reader-treue-4-mssql-berechneter-typ.md),
  „Regelhafte Bewertung" und die Eigner-Entscheidung.
