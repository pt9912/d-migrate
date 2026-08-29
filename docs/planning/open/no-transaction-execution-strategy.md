# Tracker: der Runner kann nichts außerhalb seiner Transaktion ausführen

> **Status:** Tracker / Vorabklärung (29.08.2026)
> **Trigger:** Sub-Slice 8d konnte Volltext-Indizes nicht anwenden — SQL Server
> weist `CREATE FULLTEXT INDEX` in einer offenen Transaktion ab.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan.

## Befund

`TransactionBehavior.NOT_TRANSACTIONAL` und `TransactionScope.NO_TRANSACTION`
sind im Modell vorgesehen, aber nicht ausführbar:
`MigrationStreamClassifier.unsupportedTransactionScopeReason` weist sie
ausdrücklich ab — *„NO_TRANSACTION statements require a dedicated execution
strategy"*. Die Strategie gibt es nicht.

Am Server gemessen, mit offener Transaktion:

| Anweisung | in Transaktion |
| --- | --- |
| `IF NOT EXISTS … CREATE FULLTEXT CATALOG` | geht |
| `CREATE FULLTEXT INDEX` | **abgelehnt** |
| `DROP FULLTEXT INDEX` | **abgelehnt** |
| `DROP FULLTEXT CATALOG` | **abgelehnt** |

Eine Migration ist damit gemischt: ein Statement muss außerhalb laufen, der
Rest innerhalb. Genau das nennt der Klassifikator „mixed transaction scopes are
not executable as one migration stream".

## Reichweite

Nicht MSSQL-spezifisch. Der PostgreSQL-Adapter nennt denselben Bedarf in einem
Kommentar: `CREATE INDEX CONCURRENTLY` braucht dieselbe Fähigkeit und ist
deshalb bis heute nicht gerendert. Zwei Dialekte warten also auf dieselbe
Naht.

## Was heute stattdessen passiert

`schema migrate` bricht **vor** der Ausführung mit `E072` ab (Exit 8,
`MIGRATION_BLOCKED`) und nennt den Weg: `schema generate --target mssql` und
das DDL außerhalb einer Transaktion anwenden. Das ist ehrlich, aber es ist
eine Umgehung, keine Lösung.

Vorher — und das war der eigentliche Defekt — scheiterte der Lauf **mitten in
der Ausführung** mit einer Servermeldung (Exit 5).

## Arbeitspakete (Skizze)

1. Ausführungsstrategie für Statements, die außerhalb der Transaktion laufen
   müssen: eigener Abschnitt vor oder nach dem transaktionalen Strom, mit
   klarer Aussage im Report, was davon nicht zurückrollbar ist.
2. `MigrationStreamClassifier` von „weist ab" auf „segmentiert" umstellen.
3. Danach: MSSQL-Volltext und PostgreSQL-`CREATE INDEX CONCURRENTLY`
   freischalten.
