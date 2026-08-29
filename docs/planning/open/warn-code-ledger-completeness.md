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
zurückgenommen), `R342` (nicht gelesene Routinen-Rümpfe), `R346`, `R347` und
`R348` (synthetisierter Name des Volltext-Index, Slice 8c) stehen nirgends.

Damit fehlt der R-Serie, was die anderen beiden Serien haben: eine Stelle, an der
Code, Bedeutung und Beleg zusammenstehen, und ein Test, der prüft, dass ein
emittierter Code registriert ist. Ob die R-Serie einen eigenen Ledger bekommt oder
in den bestehenden aufgeht, ist Teil dieses Tickets.
