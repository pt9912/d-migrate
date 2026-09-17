# `make doc-immutable` ist im Arbeits-Repo still grün

> **Status:** **Done — geschlossen 2026-09-17.** Befund (Gate-Infrastruktur),
> gemessen 2026-09-16. Weg 1 ist gebaut: `make doc-immutable` prüft selbst gegen
> einen frischen `git clone --no-local`. Der Nachtrag (`superseded`-ADRs) ist mit
> einer Zeile in `.d-check.yml` gelöst. Die Korrektur in d-check selbst (Weg 2)
> geht als Issue upstream. Die Abschnitte bis zum Nachtrag beschreiben den Stand
> vor dem Fix. Ursache, Nachweise und Grenzen stehen in der Closure am Ende.
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

## Closure

**Geschlossen 2026-09-17.** Weg 1 ist gebaut und der Nachtrag über die
Konfiguration gelöst. Weg 2 geht als Issue an d-check, Weg 3 (lokal umpacken)
entfällt.

**Woran „geschlossen" gemessen ist:** am Zweck des Gates, nicht an diesem
Eintrag. `make doc-immutable` meldet eine Kernänderung an einem eingefrorenen ADR
im Arbeits-Repo genauso wie in CI. Damit belegt der Lauf vor dem Push wieder
etwas.

**Ursache, jetzt nachgewiesen.** d-check liest nur Packs, deren Dateiname mit
`pack-` beginnt. Das Arbeits-Repo trägt daneben vier `loose-<hash>.pack` (von
`git maintenance`, Aufgabe `loose-objects`); git liest sie, d-check nicht. Die
beiden Commits aus der ersten Tabellenzeile (`abcfcaa8…`, `ff3d3403e`) liegen
ausschließlich in `loose-2bd81…`. Im Hardlink-Klon bricht die Range ab wie im
Arbeits-Repo. Benennt man dort die vier `loose-*` in `pack-*` um und lässt den
`multi-pack-index` stehen, meldet derselbe Klon den einen Befund an ADR 0055, wie
CI. Der `multi-pack-index` ist also nicht die Ursache.

Still grün wird der Lauf, wenn der Blob eines eingefrorenen ADR an der Basis nur
in einem solchen Pack liegt: dann zählt die Kernänderung nicht. Liegt der Blob
lose, wird sie gefunden. Das alte Target war deshalb nicht überall blind, sondern
je ADR verschieden (Nachweise unten: 0055 unsichtbar, 0056 gefunden).
`STAGED=1` war im Arbeits-Repo nie still, aber unbrauchbar: Abbruch mit
`HEAD-Tree nicht lesbar: object not found`, Exit 2, auch bei leerem Index.

Beim Bau kam ein zweiter stiller Pfad dazu. Ein Verzeichnis mit `.git`, aber ohne
Arbeitsbaum und ohne `.d-check.yml` beantwortet d-check mit `--enable vcs
--range` mit „0 Datei(en) geprüft, 0 Befund(e)" und Exit 0, auch bei einer Range,
die es nicht gibt (`deadbeef..cafebabe`). Aufgetreten ist das, als zwei
gleichzeitige Läufe sich einen festen Klon-Pfad teilten und der eine dem anderen
den Klon wegräumte. Maßgeblich ist der fehlende `vcs:`-Block: eine
`.d-check.yml` ohne ihn endet genauso, mit einem `vcs:`-Block wird die Range
aufgelöst (gemessen mit `v0.74.1` und `v0.76.1`).

**Upstream schon bekannt.** d-check führt den stillen Pfad bei nicht lesbaren
Objekten selbst (Carve-out CO-001, slice-218 und slice-220 von d-check) und hat
ihn mit `v0.76.1` geschlossen: dort bricht der Lauf mit Exit 2 ab. Lesen kann auch
`v0.76.1` die `loose-*`-Packs nicht; das ist dort eine dokumentierte Grenze. Das
Issue an d-check trägt deshalb nur die beiden Punkte, die mit `v0.76.1` bleiben.

**Was gebaut ist**

- `make/gate.mk` lenkt das Target `doc-immutable` über target-spezifische
  Variablen um (`private`): `CURDIR`, die Mount-Quelle der Recipe-Zeile, zeigt
  auf den Klon, `RANGE` auf zwei Refs im Klon, und `SHELL` ist
  [`scripts/doc-immutable-in-clone.sh`](../../../scripts/doc-immutable-in-clone.sh).
  `make/d-check.mk` bleibt, wie es generiert ist; make meldet kein
  `overriding recipe`.
- Das Skript löst `RANGE` im Arbeits-Repo zu Commits auf (auch `origin/main` und
  `HEAD~n`), klont mit `--no-local`, holt genau diese Commits und checkt den Kopf
  aus. Bei `STAGED=1` überträgt es den Index (`git diff --cached --binary`, dann
  `git apply --index`). `.d-check.yml` kommt wie vorher aus dem Arbeitsbaum;
  fehlt sie, bricht das Skript ab. Danach führt es die Recipe-Zeile aus und räumt
  den Klon weg, auch bei Fehler und Abbruch.
- Der Klon liegt je make-Prozess in einem eigenen Verzeichnis im temporären
  Verzeichnis (`TMPDIR`, sonst `/tmp`), benannt nach Benutzer-ID und PID von make,
  Modus 0700. Eine aus einem Hook geerbte Git-Umgebung (`GIT_DIR` u. a.) wirkt
  nur auf das Arbeits-Repo, nicht auf den Klon.
- `.d-check.yml`: `vcs.immutable-when` friert auch `superseded by ADR-NNNN` ein;
  `head-allow` ist unverändert.
- CI ist unverändert. `build.yml` ruft `make doc-immutable RANGE=<sha>..<sha>`
  und läuft damit ebenfalls über den Klon (etwa zwei Sekunden mehr).

**Nachweise.** Die Sabotage-Commits lagen nur auf einem lokalen Wegwerf-Branch,
der danach gelöscht wurde; `main` trägt keinen davon.

| Lauf | altes Target | neues Target |
| --- | --- | --- |
| Rumpfzeile von ADR 0055 geändert und committet (Blob der Basis nur in `loose-*`), Range ab `9cc1f4aee` | 0 Befunde, Exit 0 | 1 Befund `core-drift-vcs` |
| dazu Rumpfzeile von ADR 0056 (Blob lose), ganze Range, auch als `RANGE=origin/main..HEAD` | 1 Befund (nur 0056) | 2 Befunde (0055, 0056) |
| `00b64a4a6..HEAD` | — | 0 Befunde, Exit 0 |
| `abcfcaa8…..ff3d3403e` | Abbruch `reference not found`, Exit 2 | 1 Befund `core-drift-vcs` an ADR 0055, wie damals in CI |
| `STAGED=1`, Index leer | Abbruch, Exit 2 | 0 Befunde |
| `STAGED=1`, Rumpfzeile von 0055 gestaged | Abbruch, Exit 2 | 1 Befund |
| `STAGED=1`, gestaged, Arbeitsbaum zurückgesetzt | — | 1 Befund (der Index zählt) |
| zwei Läufe gleichzeitig (einer rot, einer grün), dreimal | — | je richtig: 2 Befunde bzw. 0 |
| Abbruch per SIGINT an die Prozessgruppe | — | Exit 130, Klon weg |
| mit `GIT_DIR` und `GIT_WORK_TREE` aus der Umgebung | — | 2 Befunde, Arbeits-Repo unverändert |
| ohne `RANGE`, unbekannter Ref, `a...b`, `..HEAD`, fremder Klon-Pfad | — | Exit 2 mit Meldung |

`make -n doc-immutable RANGE=…` zeigt den Mount des Klons
(`d-migrate-doc-immutable-<uid>-<pid>/repo`) und
`--range doc-immutable-base..doc-immutable-head`.

Zum Nachtrag, mit dem neuen Target, alte gegen neue Konfigurationszeile:

| Range | alt | neu |
| --- | --- | --- |
| Rumpfzeile von ADR 0053 geändert (an der Basis schon `superseded`) | 0 Befunde | 1 Befund |
| Statuszeile von 0053 auf `superseded by` mit anderer Nummer | 0 Befunde | 0 Befunde |
| Statuszeile von 0053 auf `deprecated` | 0 Befunde | 1 Befund (unzulässiger Status-Übergang) |
| `c9737f909` (0053 von `accepted` auf `superseded`) | 0 Befunde | 0 Befunde |
| `00b64a4a6..HEAD` | 0 Befunde | 0 Befunde |
| `ebd294f30..ff3d3403e` | 1 Befund (0055) | 2 Befunde (0048, 0055) |

**Grenzen**

- Geprüft werden nur Commits (`RANGE`) bzw. der Index (`STAGED=1`). Nicht
  gestagte Änderungen zählen in keinem Modus.
- make muss wie bei den anderen Gate-Targets im Wurzelverzeichnis des Repos
  laufen: `SHELL` ist ein relativer Pfad, sonst endet der Lauf mit Exit 127.
- Nach `kill -9` bleibt ein Klon im temporären Verzeichnis liegen.
- Der Pin steht auf d-check `v0.74.1`. Andere git-lesende Aufrufe gegen das
  Arbeits-Repo, etwa `make doc-commits`, bleiben dort blind (nicht gemessen; kein
  Gate dieses Repos nutzt sie).

**Restflächen**

| Punkt | Ort |
| --- | --- |
| `vcs` ohne `vcs:`-Block endet mit Exit 0, ohne die Range aufzulösen; Objekte aus `loose-*`-Packs lesen (oder die Ursache in der Meldung nennen) | Issue an `pt9912/d-check` ([#4](https://github.com/pt9912/d-check/issues/4)) (Weg 2) |
| Pin auf d-check `v0.76.1` heben, damit nicht lesbare Objekte auch außerhalb dieses Targets laut abbrechen | [`dcheck-pin-v0761-fail-closed.md`](../done/dcheck-pin-v0761-fail-closed.md) (erledigt 2026-09-17) |

**Bewusst ohne eigenen Ort:**

- Historische Ranges über `ff3d3403e` melden jetzt zusätzlich ADR 0048; der
  Link-Sweep dort lief nach dessen Supersede. Das ist Historie, keine Arbeit.
- Ein übersteuerter ADR wird jetzt auch bei einem Link-Sweep rot. Für akzeptierte
  ADRs gilt das schon länger: der Verweis ist historisch und bleibt stehen.

**Paket → Commit**

| Paket | Commit |
| --- | --- |
| Weg 1: Target prüft gegen einen frischen Klon | `62a9bc5b9` |
| Nachtrag: `superseded` im Kern eingefroren | `6d83afe5f` |
| Move mit Closure, Verweise nachgezogen, Ort für die Pin-Hebung | der Move-Commit |
