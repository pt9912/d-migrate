# SQL Server: ein `integer`-Identity-Primärschlüssel verliert den Modus

> **Status:** Entschieden (2026-09-18) — wird in Plan 3 des Reader-Umbrellas
> gebaut ([`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md)).
> **Eigner-Entscheidung:** wie S1 bei PostgreSQL beheben — eine `int IDENTITY`-Spalte,
> die allein den Primärschlüssel bildet, liest als `integer` mit
> `generation: identity` (Modus bleibt), statt auf `identifier` zu fallen. Der
> Eintrag schliesst mit der Graduation von Plan 3; der Silent-Loss-Check verliert
> damit seinen letzten Eintrag aus Plan 2.
> **Trigger:** Beim Bau von **S1** in Plan 2 des Reader-Umbrellas
> ([`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md)).
> S1 hat den PostgreSQL-Fall behoben; derselbe Verlust bleibt auf SQL Server,
> und der Silent-Loss-Check der Compare-Matrix zeigt ihn seither als
> bekannten Befund mit diesem Ort.
> **Severity:** P3 — schmal (nur ein `integer`/`smallint`-Identity-Schlüssel),
> und das Gegenstück ist gerade beschrieben worden.

## Befund

Der SQL-Server-Reverse faltet eine `int IDENTITY(1,1)`-Spalte, die **allein**
den Primärschlüssel bildet, auf den Typ `identifier` mit `auto_increment` —
und `identifier` trägt keinen Modus. Eine `bigint IDENTITY`-Spalte kommt
dagegen als `biginteger` + `generation: identity` mit Modus `always` zurück.

In der Compare-Matrix sichtbar als Zelle PostgreSQL → SQL Server, Seed
`sl_pg_identity_int.id`:

| Schritt | Form |
| --- | --- |
| Quelle (PostgreSQL, seit S1) | `integer` + `generation: identity`, Modus `always` |
| erzeugte DDL (SQL Server) | `INT IDENTITY(1,1) NOT NULL` |
| Reverse des Ziels | `identifier(auto)`, **kein** `generation` |

Der Modus geht also verloren, und **kein Code sagt es**. `W140` meldet den
umgekehrten Fall (`BY DEFAULT` ohne Entsprechung); `always` ist auf SQL Server
die native Form und wird dort nicht gemeldet.

## Warum das nicht in Plan 2 gehört

S1 hat den PostgreSQL-Fall behoben, weil der Verlust dort auf dem Rückweg in
**denselben** Dialekt entstand: `identifier` rendert als `SERIAL`, und `SERIAL`
nimmt einen gesetzten Wert an — die Zusage von `always` war damit schon
PostgreSQL → PostgreSQL weg, ohne dass ein Vergleich zweier Reverses es sieht.

Auf SQL Server liegt der Fall anders. Ob `int IDENTITY` als `identifier` oder
als `integer` mit Identity gilt, ist eine **Typfrage** — dieselbe, die das
[Toleranzprofil](../next/compare-toleranzprofil.md) unter K2 ausdrücklich
ausklammert („Ob `int IDENTITY` als `identifier(auto)` oder als `integer` mit
Identity gilt, ist eine Typfrage und kein Teil von K2"). Sie zu ändern hieße,
den `identifier`-Vertrag für SQL Server neu zu ziehen, und das trifft jede
Zelle mit Quelle oder Ziel SQL Server.

## Die drei Wege

1. **Melden.** Der SQL-Server-Generator gibt der Spalte einen Code, wenn das
   Soll `mode: always` sagt und die Spalte als `identifier` zurückkommt — das
   Gegenstück zu `W163` auf MySQL und SQLite. Billig, ändert kein Modell,
   löst den Verlust aber nicht.
2. **Beheben wie S1.** Der Reverse liest eine `int IDENTITY`-Spalte im
   Primärschlüssel als `integer` + `generation: identity`. Das ist die
   konsequente Fortsetzung, ändert aber den `identifier`-Vertrag für SQL
   Server und damit jede Zelle mit SQL Server auf einer Seite.
3. **Nichts tun** und den Fall im Silent-Loss-Check als bekannten Befund
   führen, wie jetzt.

**Eigner-Frage:** welcher der drei. Ohne Entscheidung bleibt Weg 3.

## Belegstellen

- `MssqlTypeMapping.mapColumn` (die Faltung auf `identifier`).
- `examples/mcp-e2e/scripts/lib/silent-loss.sh`, Eintrag
  `ziel postgresql->mssql: sl_pg_identity_int.id`.
- `examples/mcp-e2e/fixtures/seeds/postgresql.sql`, Anmerkung zu
  `sl_pg_identity_int.id` (`ziel mssql: identifier(auto) | code: keinen`).
