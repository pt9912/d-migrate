# `make doc-immutable` ist im Arbeits-Repo still grün

> **Status:** Befund (Gate-Infrastruktur), gemessen 2026-09-16.
> **Trigger:** Beim Übersteuern von ADR 0053 (Slice
> [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md))
> sollte die Gegenprobe zeigen, dass das Gate eine Kernänderung fängt. Im
> Arbeits-Repo fing es sie nicht.
> **Eigner-Entscheidung (2026-09-16): Weg 1** — das Target prüft gegen einen
> frischen `--no-local`-Klon; dazu ein Issue an d-check (ein Lesefehler darf nicht
> grün enden). Wird nach dem Compare-Slice gebaut — der ist seit 2026-09-17
> graduiert, die Bedingung damit erfüllt; `make/d-check.mk`
> ist generiert, die Änderung gehört deshalb nicht dort hinein.

## Befund

`make doc-immutable` fährt das d-check-Modul `vcs` gegen das eingehängte
Arbeits-Repo (`make/d-check.mk`, Target `doc-immutable`). In diesem Repo liest es
den Objektbestand nicht vollständig, und zwar auf zwei Arten:

| Lauf | Arbeits-Repo (bzw. Hardlink-Klon) | frischer Klon (`git clone --no-local`) |
| ---- | --------------------------------- | -------------------------------------- |
| Range über `ff3d3403e` (in CI rot, Kern von ADR 0055 geändert) | **Abbruch**: `Range-Basis "abcfcaa8…" nicht auflösbar: reference not found`, obwohl `git rev-parse` den Commit kennt | **1 Befund** `core-drift-vcs` — wie in CI |
| Sabotage: Rumpfzeile von ADR 0053 geändert und committet, Range ab `origin/main` | **0 Befunde, Exit 0** | **1 Befund** `core-drift-vcs`, Exit 1 |
| Die legitime Änderung aus `c9737f909` (nur Statuszeile) | 0 Befunde | 0 Befunde |

Der zweite Fall ist der gefährliche: das Gate meldet keinen Fehler, es meldet
**Erfolg**. `CLAUDE.md` empfiehlt genau diesen Lauf vor dem Push; ein grünes
Ergebnis dort belegt also nichts.

**CI ist nicht betroffen.** Der Job arbeitet auf einem frischen Checkout, und dort
war das Gate beim Kernbruch in `ff3d3403e` rot.

## Vermutete Ursache (nicht nachgewiesen)

Das Arbeits-Repo trägt neben regulären Packs `loose-*`-Packs und einen
`multi-pack-index` (`.git/objects/pack/`). Ein Hardlink-Klon übernimmt diesen
Aufbau und verhält sich gleich; ein `--no-local`-Klon packt neu und liest sich
korrekt. Naheliegend ist, dass die Git-Bibliothek in d-check einen Teil dieses
Aufbaus nicht liest und einen nicht gefundenen Blob als „unverändert" wertet
statt als Fehler. Belegt ist nur der Unterschied zwischen den beiden Klonarten,
nicht der Mechanismus.

## Wege

1. **Make-Target härten:** `doc-immutable` klont vor dem Lauf mit
   `git clone --no-local` in ein temporäres Verzeichnis und hängt das ein. Kostet
   Sekunden, wirkt sofort, braucht keine Änderung an d-check.
2. **Upstream in d-check:** nicht lesbare Objekte als Fehler melden statt als
   „unverändert"; den Pack-Aufbau unterstützen. Das ist die eigentliche
   Korrektur. Unabhängig davon bleibt die Frage, warum ein Lesefehler still grün
   ausgeht.
3. **Lokal umpacken** (`git repack -a -d`, `multi-pack-index` entfernen): wirkt nur
   bis zur nächsten Wartung und nur auf diesem Rechner. Kein Gate-Fix.

Bis dahin gilt als Arbeitsweise: die Range in einem frischen Klon prüfen
(`git clone --no-local`, dann dasselbe `docker run` wie im Target gegen den Klon).

## Nachtrag 2026-09-17 — ein `superseded`-ADR ist nicht eingefroren

Zweiter Befund aus demselben Slice (Verifikation Runde 4, P7-DoD 3), unabhängig
vom Lesefehler oben: das Modul `vcs` friert nur ADRs ein, deren Basis
`status: accepted` trägt (`.d-check.yml`, `vcs.immutable-when:
'^status: accepted'`). Nach dem Statuswechsel von ADR 0053 auf
`superseded` (übersteuert von ADR 0056) fiele eine spätere Kernänderung an 0053 keinem Gate
auf — obwohl der Kern eines übersteuerten ADR als Historie genauso fest sein
sollte. Die Gegenprobe von P7 („eine Kernänderung an 0053 macht das Gate rot")
gilt damit nur für Ranges, deren Basis 0053 noch als `accepted` führt.

**Wege:** `immutable-when` im Repo auf beide Statusformen erweitern (zu prüfen:
wie d-check die Basis wertet, wenn die Statuszeile im selben Range wechselt —
der legitime Wechsel darf nicht rot werden), oder eine Regel upstream in
d-check (ein Issue zusammen mit dem Lesefehler). Gegenprobe in jedem Fall im
frischen `--no-local`-Klon: eine Rumpfzeile von 0053 ändern, das Gate muss rot
werden; der Statuswechsel aus `c9737f909` muss grün bleiben.
