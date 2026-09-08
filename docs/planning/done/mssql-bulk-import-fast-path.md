---
id: mssql-bulk-import-fast-path
title: "MSSQL-Import läuft über gebatchte INSERTs, ohne Bulk-Pfad"
status: resolved
---

# MSSQL-Import ohne Bulk-Pfad

## Lage

Der MSSQL-Import schreibt über gebatchte `INSERT`s. SQL Server bietet mit
`BULK INSERT` und `SQLServerBulkCopy` einen Weg, der für große Mengen deutlich
schneller ist — analog zu PostgreSQLs `COPY`, das d-migrate dort nutzt.

Der Slice-Schnitt notierte für Slice 3 „Fast-Path später", ohne Slice-Nummer.
Damit ist es eine Durchsatz-Frage ohne Termin.

## Warum es kein Defekt ist

Der heutige Weg ist korrekt, nur langsamer. Er trägt alle Eigenschaften, die
der Import braucht — Konfliktmodi, Identity-Insert, Trigger-Verhalten. Ein
Bulk-Pfad müsste sie einzeln nachweisen, statt sie zu erben.

## Gemessen und gebaut

**Der Messpunkt** (SQL Server 2022 im Container, 200 000 Zeilen, vier Spalten
`INT`/`NVARCHAR(100)`/`DECIMAL(18,2)`/`NVARCHAR(400)`):

| Weg | Zeilen/s | Faktor |
| --- | -------- | ------ |
| gebatchtes `INSERT` (heute) | 43 773 | 1,00 |
| `SQLServerBulkCopy`, Voreinstellungen | 107 526 | 2,46 |
| `SQLServerBulkCopy`, mit erzwungenen Sicherungen | **94 473** | **2,16** |

**Welche Eigenschaften der Bulk-Weg traegt** — gemessen, nicht aus der Doku
gelesen:

- **Constraints und Trigger traegt er NICHT von selbst.** Mit den
  Voreinstellungen landet eine Zeile, die einen `CHECK` verletzt, in der
  Tabelle, und ein `AFTER INSERT`-Trigger bleibt stumm. `isCheckConstraints`
  und `isFireTriggers` werden deshalb erzwungen; die 14 % Unterschied zwischen
  den beiden Bulk-Zeilen oben sind ihr Preis.
- **Identity traegt er** ueber `isKeepIdentity` — der explizite Wert 42
  ueberlebt.
- **`--on-conflict` traegt er nicht.** BulkCopy kennt kein `MERGE`; `skip` und
  `update` bleiben auf dem bisherigen Weg.
- **Geometrie traegt er nicht.** Der Wert wird ueber
  `geometry::STGeomFromWKB(?, srid)` konstruiert, und ein roher Wertstrom fuehrt
  kein SQL je Wert aus.
- **Die Transaktion des Laufs traegt er mit.** `isUseInternalTransaction` ist
  mit einer uebergebenen Verbindung nicht erlaubt — genau richtig, der Chunk
  gehoert in die Transaktion des Aufrufers.
- **Praezision und Skala fuehrt `TargetColumn` nicht**, `ISQLServerBulkData`
  verlangt sie aber je Spalte. Sie kommen deshalb vom Server, einmal je Tabelle.

**Ab wann er sich lohnt:** ab der ersten Zeile — der Pfad ist nicht an eine
Mindestmenge gebunden, weil er dieselbe Semantik traegt und kein Umschalten
kostet. Wo er nicht greift, faellt er auf den bisherigen Weg zurueck, nicht auf
eine Naeherung.

Abgenommen live gegen SQL Server 2022. Der Beleg, dass der Pfad ueberhaupt
genommen wird, steckt im Sabotage-Gegenversuch: legt man
`isCheckConstraints`/`isFireTriggers` um, fallen genau die beiden Faelle um —
auf dem INSERT-Weg haette das Umlegen einer BulkCopy-Option keine Wirkung.

## Was der Schnitt klaeren musste

- **Welche der Import-Eigenschaften der Bulk-Weg trägt.** `SQLServerBulkCopy`
  kennt eigene Optionen für Identity und Constraints; `--on-conflict` hat dort
  keine Entsprechung.
- **Ab wann er sich lohnt.** Ein Messpunkt gegen den heutigen Weg gehört vor
  die Entscheidung, nicht danach.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans, dort ohne Slice-Zuordnung.
