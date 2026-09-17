# Tracker: W-Code-Ledger-Vollständigkeit (Backfill + Code→Ledger-Gate)

> **Status:** Tracker / Vorabklärung (2026-06-28)
> **Trigger:** Beim Anlegen des Fulltext-Degradierungs-Codes (W132, Folge-Slice zu
> [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md)) fiel auf: der
> maschinen-geprüfte YAML-Ledger ist **unvollständig** — mehrere im Main-Code
> emittierte W-Codes sind nirgends registriert, und das Validierungs-Gate fängt
> das nicht.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan mit den zwei
> Arbeitspaketen; sonst Trigger-Watch.

## Befund (gemessen 2026-06-28)

8 im Main-Code emittierte W-Codes fehlen im YAML-Ledger (`warn-code-ledger-*.yaml`):

| Code | Bedeutung | Herkunft der Lücke |
| ---- | --------- | ------------------ |
| W100, W102, W103, W111 | alte Dialekt-Typ-Mapping-Warnungen | nie in den YAML nachgezogen (in `spec/ledger.md` nur als Bereich W100-W112) |
| W127 | PG schema-globale Index-Namen-Dedup (N8) | Pilot-P3, ohne Ledger-Eintrag gemerged |
| W128 | zirkuläre/unauflösbare Funktions-Call-Deps (K2) | Pilot-P3, dito |
| W200, W201 | SQLite-Typ-Mapping (W200 = Decimal→REAL; W201 beim Backfill zu verifizieren) | ohne Ledger-Eintrag |

Die Lesefassung `spec/ledger.md` überspringt zusätzlich W127/W128 (W126→W129).

> **Nachtrag 2026-07-18:** Zwei weitere ValueSerializationWarning-Codes aus dem
> CSV-Writer gehören ebenfalls in den AP1-Backfill: **W202** (nicht-serialisierbarer
> Java-Typ → String-Fallback, bereits vor diesem Datum emittiert) und **W203**
> (formel-anfällige CSV-Text-Zelle, CWE-1236 Audit-Follow-up #6). Beide sind heute
> nicht im Ledger; das Gate fängt sie mangels W-Code-Vollständigkeitsprüfung (AP2)
> nicht.

**Ursache:** `CodeLedgerValidationTest` erzwingt **E-Code**-Vollständigkeit
(E001-E121) + Eintrags-Struktur, aber **keine W-Code-Vollständigkeit gegen den
Source**. W-Drift sammelt sich daher still an — N8/W127 und K2/W128 kamen so
ungeprüft durch.

## Arbeitspakete

- **AP1 — Backfill.** Die 8 fehlenden W-Codes in den aktuellen YAML-Ledger
  eintragen, je mit `level`/`status`/`entry_type`/`test_path`/`evidence_paths`
  (Vorbild: die W112/W118-Backfill-Einträge im aktuellen Warn-Ledger). Für
  W100/W102/W103/W111 etwas Archäologie (Emit-Stelle + Test je Code finden);
  `spec/ledger.md` parallel auf W127/W128 nachziehen.
- **AP2 — Gate-Härtung.** `CodeLedgerValidationTest` um eine
  W-Code-Vollständigkeitsprüfung erweitern: alle `code = "Wxxx"` im Main-Source
  einsammeln, jeder muss im Ledger registriert sein (symmetrisch zur bereits
  vorhandenen E-Code-Vollständigkeit). Das Gate wird erst grün, wenn AP1 komplett
  ist — das macht den Fix dauerhaft und verhindert künftige stille Drift.

## Akzeptanzkriterien

- Jeder im Main emittierte W-Code hat einen YAML-Ledger-Eintrag, vom Gate gegen
  den Source erzwungen.
- `spec/ledger.md` und der YAML-Ledger decken dieselbe W-Code-Menge ab.
- Build / `docker-check` grün.

## Bezug

- Auslöser: W132 (Fulltext-Degradierung), Slice
  [`../done/fulltext-structural-cross-dialect.md`](../done/fulltext-structural-cross-dialect.md).
- Validierung: `CodeLedgerValidationTest.kt` (`hexagon/core`); Ledger-YAMLs unter
  dem `ledger`-Verzeichnis (`warn-code-ledger-0.9.9.yaml` u. a.).

## Nachtrag 2026-08-28: die R-Serie hat gar keinen Ledger

Beim Anlegen von `R346`/`R347` (MSSQL-Partitionierungs-Reverse) fiel auf, dass
die **Reverse-Notes überhaupt nicht geführt werden**. `ledger/` kennt nur
`warn-code-ledger-*` und `error-code-ledger-*`; für R-Codes gibt es weder eine
YAML-Datei noch eine Lesefassung.

Dokumentiert sind heute nur die typbezogenen: `R340`, `R343` und `R345` stehen
in `spec/type-mapping.md`, weil sie dort inhaltlich hingehören. `R341` (inzwischen
zurückgenommen), `R342` (nicht gelesene Routinen-Rümpfe), `R346`, `R347`,
`R348` (synthetisierter Name des Volltext-Index, Slice 8c) sowie `R349` bis
`R353` (Routinen-Reverse, Slice 9a/9b) stehen nirgends — die R349–R353 immerhin
als Tabelle in `spec/ddl-generation-rules.md` Abschnitt 11, aber nicht in einem
Ledger.

Damit fehlt der R-Serie, was die anderen beiden Serien haben: eine Stelle, an der
Code, Bedeutung und Beleg zusammenstehen, und ein Test, der prüft, dass ein
emittierter Code registriert ist. Ob die R-Serie einen eigenen Ledger bekommt oder
in den bestehenden aufgeht, ist Teil dieses Tickets.

## Nachtrag 2026-09-17: Fragen aus dem Reader-Schnitt (F4)

Der Reader-Slice vergibt neue Kennungen
([`../next/reader-treue.md`](../next/reader-treue.md), Abschnitt „Codes":
`R370`, `R371`, `R402`–`R405`, `R221`, `W162`–`W164`). Beim Schnitt in vier
Pläne sind dabei Fragen aufgefallen, die nicht in einen der Pläne gehören,
sondern hierher. Der Eintrag wächst damit über „Backfill der W-Codes" hinaus.

1. **Gehören R-Codes mit Severity `WARNING` unter „jeder nutzersichtbare
   Warning-Code"?** `spec/ledger.md` verlangt, dass jeder nutzersichtbare
   W- und E-Code registriert ist. Reverse-Notes mit `WARNING` sind ebenso
   sichtbar: sie zählen im Reverse-Report unter `summary.warnings` und stehen
   ohne `--verbose` auf stderr. Das trifft heute `R301`, nach
   [ADR 0058](../../adr/0058-verlorener-srid-beim-reverse-ist-warnung.md) auch
   `R365` und `R370`, und mit dem Reader-Slice `R371`, `R402`–`R405` und
   `R221`. Der Nachtrag vom 2026-08-28 fragt schon, ob die R-Serie einen
   eigenen Ledger bekommt; diese Frage schärft ihn.
2. **Welche Ledger-Datei gilt?** `spec/ledger.md` sagt: je Minor-Version ein
   eigener Satz, ältere Dateien bleiben unverändert. Gelebt wird die
   Fortschreibung von
   [`ledger/warn-code-ledger-1.1.0.yaml`](../../../ledger/warn-code-ledger-1.1.0.yaml):
   `W155` bis `W161` sind nach 1.1.0 entstanden und stehen dort, und
   `CodeLedgerValidationTest` liest diese Datei; eine Datei für die laufende
   Minor-Version läse kein Test. Der Reader-Slice schreibt seine W-Codes
   deshalb in die 1.1.0-Datei, bis das hier entschieden ist. Entweder die
   Regel in `spec/ledger.md` oder die Praxis muss sich ändern.
3. **`W137` — die Richtung ist vorentschieden.** Zwei akzeptierte ADRs führen
   `W137` als Diagnose eines Berechnungsausdrucks, den der Vergleich nicht
   entscheiden kann
   ([ADR 0056](../../adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
   [ADR 0057](../../adr/0057-schema-compare-eine-semantik-herkunft-kein-unterschied.md);
   Code: `ComputedExpressionDecidability.UNDECIDED`). Das YAML-Ledger legt
   `W137` dagegen auf die SQL-Server-Bedeutung „JSON/Array →
   `NVARCHAR(MAX)`" (Eintrag mit Beleg `MssqlColumnConstraintHelper.kt`), und
   `spec/ledger.md` nennt beide Bedeutungen in zwei Zeilen. Weil die ADRs
   eingefroren sind, muss die **JSON/Array-Bedeutung umziehen**: ein neuer
   Code für SQL Server. Kandidat für die Array-Hälfte ist `W162` aus dem
   Reader-Slice („die Spalte verliert ihre Array-Eigenschaft"), die
   JSON-Hälfte braucht einen eigenen.
4. **`W160` fehlt in der Bereichszeile** von `spec/ledger.md` (dort folgt
   `W161` auf `W159`) und im YAML-Ledger (dort steht an seiner Stelle ein
   Kommentar, der auf diesen Eintrag verweist). In der W-Tabelle von
   `spec/cli-spec.md` steht er.
5. **Weitere Doppelbelegungen.** `R345` trägt im Code zwei Notizen:
   SQL Servers `geography` mit angenommenem SRID 4326
   (`MssqlTypeMapping.kt:55`) und Oracles fehlenden `START WITH`-Wert
   (`OracleSchemaReader.kt:383`). `W120` trägt zwei: den SRID-Hinweis der
   Generatoren (SQL Server, MySQL, Oracle) und den veränderten
   Trigger-Rumpf des SQLite-Reverse
   (`SqliteSequenceReverseSupport.kt:186`, dort als Reverse-Note).
   `spec/ledger.md` weist `W120` als „Multi-Dialekt" mit beiden Bedeutungen
   aus; die W-Tabelle von `spec/cli-spec.md` und die Code-Tabelle in
   `spec/neutral-model-spec.md` nennen nur den SRID-Hinweis.
6. **Die R-Vergabe hat keine Regel.** Belegt sind: R200–R220 SQLite,
   R300/R301 allgemein, R310–R330 MySQL, R340–R369 gemischt (SQL Server,
   Oracle, `driver-common`), R400/R401 PostgreSQL. Der Reader-Slice folgt
   diesen Bereichen (`R370`/`R371` hinter dem gemischten Bereich), ohne dass
   eine Entscheidung sie trägt.
7. **Die Lesefassungen decken verschiedene Mengen.** Die W-Tabelle in
   `spec/cli-spec.md` springt von `W120` auf `W155`; `spec/ledger.md` führt
   Bereichszeilen; das YAML-Ledger ist die maschinenlesbare Menge. Welche
   Fassung vollständig sein muss, gehört zu Punkt 2.

**Erweiterte Akzeptanzkriterien:**

- Keine Kennung trägt zwei Bedeutungen, oder jede Lesefassung weist die
  Doppelbelegung gleich aus; `W137` hat nur noch die Bedeutung der ADRs.
- Die gültige Ledger-Datei ist entschieden, und `spec/ledger.md` sagt
  dasselbe wie die Praxis.
- Für R-Codes mit `WARNING` ist entschieden, ob und wo sie registriert werden,
  und ein Test prüft es.
- `W160` steht in allen Lesefassungen.
