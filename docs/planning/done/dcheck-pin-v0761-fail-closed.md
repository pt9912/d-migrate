# d-check-Pin: erst `v0.76.1` bricht bei nicht lesbaren Objekten ab

> **Status:** **Done — geschlossen 2026-09-17.** Befund / Vorabklärung
> (Gate-Infrastruktur), 2026-09-17. Der Pin steht auf `v0.76.1`. Die Abschnitte
> bis zur Closure beschreiben den Stand vor der Hebung. Eine Annahme darin
> (`make doc-commits`) hat sich beim Messen als falsch erwiesen; die Closure
> korrigiert sie.
> **Trigger:** Beim Schließen von
> [`doc-immutable-lokal-still-gruen.md`](../done/doc-immutable-lokal-still-gruen.md)
> gemessen: der gepinnte d-check `v0.74.1` meldet eine Kernänderung still mit
> 0 Befunden, wenn ein Objekt nur in einem `loose-*`-Pack liegt. d-check hat das
> selbst als Carve-out CO-001 geführt und mit `v0.76.1` geschlossen.
> **Aktivierungsbedingung:** Eigner-Entscheidung, den Pin zu heben. Danach ist es
> ein kleiner, eigener Commit (wie `a35261f04`).
> **Eigner-Entscheidung (2026-09-17):** Pin heben.

## Befund

- Der Pin steht im [`Makefile`](../../../Makefile) (`DCHECK_DIGEST`, `v0.74.1`),
  und `make/d-check.mk` ist mit `--print-mk` aus derselben Version erzeugt.
- `make doc-immutable` ist davon nicht mehr abhängig: es prüft gegen einen
  frischen Klon ([`scripts/doc-immutable-in-clone.sh`](../../../scripts/doc-immutable-in-clone.sh)),
  in dem es keine `loose-*`-Packs gibt.
- Jeder andere git-lesende Aufruf gegen das Arbeits-Repo trägt die Lücke weiter,
  etwa `make doc-commits` (Modul `commits`). Kein Gate dieses Repos nutzt ihn
  heute; gemessen ist er nicht.
- Gemessen an einem Probe-Repo mit `loose-*`-Pack: `v0.74.1` endet mit
  0 Befunden und Exit 0, `v0.76.1`
  (`sha256:1470ecdcaa686a5ef4513dee9b0ae522586f54b87d568b06fc6b5b2741b633b3`)
  bricht mit Exit 2 ab. Lesen kann auch `v0.76.1` solche Packs nicht; das ist in
  d-check eine dokumentierte Grenze (slice-218).

## Was eine Hebung mitbringt

Laut Versionshistorie im Benutzerhandbuch von d-check ändert `v0.76.1` am Modul
`vcs` zwei Fehlermeldungen, ohne neuen Grund-Code und ohne Konfigurations-Bruch.
`v0.75.0` und `v0.76.0` bringen Opt-in-Erweiterungen (Modul `mentions`, eine
weitere `structure`-Bedingung). Zur Hebung gehören `make/d-check.mk`, neu erzeugt
mit `--print-mk`, der neue Digest sowie `make docs-check` und
`make doc-immutable RANGE=origin/main..HEAD` auf dem neuen Pin.

## Closure

**Geschlossen 2026-09-17.** Der Pin steht auf d-check `v0.76.1`
(`sha256:1470ecdcaa686a5ef4513dee9b0ae522586f54b87d568b06fc6b5b2741b633b3`).
Vor dem Eintrag geprüft: `docker pull` des Tags liefert diesen Digest,
`docker buildx imagetools inspect` ebenso, und die Release-Notes von `v0.76.1`
nennen ihn als Digest-Pin.

**Woran „geschlossen" gemessen ist:** am Zweck, nicht an diesem Eintrag. Ein
git-lesender d-check-Lauf gegen das Arbeits-Repo, der ein Objekt nicht lesen
kann, endet jetzt mit Exit 2 statt mit 0 Befunden. `make doc-immutable` bleibt
davon unberührt und meldet eine Kernänderung wie vorher.

**Was gehoben ist**

- `Makefile`: `DCHECK_DIGEST` und der Kommentar darüber (Version,
  Regenerations-Befehl). Die Target-Liste im Kommentar nennt jetzt auch
  `doc-usage`, das seit `v0.74.1` mit generiert wird.
- `make/d-check.mk`: mit `--print-mk` aus `v0.76.1` neu erzeugt, nicht von Hand;
  Tag und Digest liefern dieselbe Datei. Der Diff ist nur Versionsfolge: der Tag
  in `DCHECK_IMAGE` und `--disable mentions` in den sechs
  Einzelmodul-Targets.
- CI pinnt nichts selbst. `build.yml` ruft `make docs-check` und
  `make doc-immutable` und zieht damit mit.
- Digest und `.mk` gehören zusammen. Die neue `.mk` mit dem alten Digest bricht
  in den Einzelmodul-Targets mit `unbekanntes Modul "mentions"` ab (Exit 2);
  `doc-check` ohne `--disable`-Liste läuft mit beiden.

**Was die Versionen ändern, und was davon hier wirkt.** Quelle sind das
CHANGELOG und die Versionshistorie im Benutzerhandbuch von d-check, jeweils am
Tag `v0.76.1`.

| Version | Änderung | Wirkung hier |
| --- | --- | --- |
| `v0.75.0` | Neues Opt-in-Modul `mentions`; die Zusammenfassung trägt in `--json`/`--yaml` zusätzlich `summary.notes` | `mentions` ist in `.d-check.yml` nicht aktiviert, die `.mk` schaltet es in den Einzelmodul-Targets ab. Kein Skript und kein Workflow wertet die JSON-Ausgabe von d-check aus. |
| `v0.76.0` | `structure`: Opt-in-Bedingung `open-tasks-require-marker`. `vcs`: zwei von vier Ausprägungen des stillen Lesefehlers behoben (unsichtbarer Blob, unsichtbares Verzeichnis mit Pendant); im CHANGELOG-Abschnitt nicht genannt, nur in der Versionshistorie | `structure` ist nicht aktiviert. Die `vcs`-Korrektur wirkt nur außerhalb des Klon-Targets. |
| `v0.76.1` | `vcs` löst die geschützte Pfad-Menge gegen beide Tree-Stände auf, statt einem Diff zu vertrauen; ein nicht ladbarer Unterbaum bricht mit Exit 2 ab. Zwei Fehlermeldungen ändern sich; kein neuer Grund-Code, kein Konfigurations-Bruch | Direkte `vcs`-Läufe gegen das Arbeits-Repo brechen jetzt ab (Nachweise). Das Klon-Skript wertet keine Meldung aus und läuft unverändert. |

Kein Konfigurationsschlüssel von `.d-check.yml` ist geändert oder entfallen. Die
dort aktivierten Module ändert keine der drei Versionen.

**Nachweise.** Die Sabotage-Commits lagen nur auf einem Wegwerf-Branch in einem
eigenen Worktree, der danach entfernt wurde; `main` trägt keinen davon.

| Lauf | `v0.74.1` | `v0.76.1` |
| --- | --- | --- |
| `make docs-check` | 344 Dateien, 0 Befunde | 344 Dateien, 0 Befunde |
| `make doc-planning`, `make doc-tracked` | je 0 Befunde | je 0 Befunde |
| `make doc-immutable RANGE=00b64a4a6..HEAD` | — | 0 Befunde |
| `make doc-immutable RANGE=abcfcaa8…..ff3d3403e` | — | 2 Befunde (ADR 0048, 0055) |
| dieselbe Range, `vcs` direkt im frischen Klon | 2 Befunde | 2 Befunde; der Befund an 0048 kommt aus `6d83afe5f`, nicht aus dem Pin |
| `make doc-immutable`, Rumpfzeile von ADR 0055 committet | — | 1 Befund `core-drift-vcs` |
| `make doc-immutable STAGED=1`, Rumpfzeile von ADR 0056 gestaged | — | 1 Befund `core-drift-vcs` |
| `vcs` direkt gegen das Arbeits-Repo, `9cc1f4aee..origin/main` und die letzten fünf Commits | 0 Befunde, Exit 0, ohne den Tree lesen zu können | Abbruch `nicht lesbarer Unterbaum`, Exit 2 |
| `vcs` direkt gegen das Arbeits-Repo, `abcfcaa8…..ff3d3403e` | Abbruch `reference not found`, Exit 2 | ebenso |
| Probe-Repo mit `loose-*`-Pack und committeter Kernänderung, Target `doc-immutable` der jeweiligen `.mk` direkt gegen das Repo | 0 Befunde, Exit 0 | Abbruch `nicht lesbarer Unterbaum "docs"`, Exit 2 |
| dasselbe Probe-Repo, Rezept aus d-check#4 als blankes `docker run` | 0 Befunde, Exit 0 | Abbruch, Exit 2 |
| dasselbe Probe-Repo als `git clone --no-local` | 1 Befund `core-drift-vcs`, Exit 1 | ebenso |

Das Probe-Repo folgt dem Rezept aus dem Issue: eine akzeptierte ADR,
`git maintenance run --task=loose-objects` zweimal, dann ein Commit ohne und
einer mit Kernänderung; `.git` trägt danach nur einen `loose-*`-Pack.

**`make doc-commits`: die Annahme im Befund war falsch.** Der Befund nahm an,
das Target trage die Lücke weiter. Gemessen:

- In diesem Repo ist `make doc-commits` inert. `.d-check.yml` hat keinen
  `commits:`-Block, und ohne ihn prüft das Modul nichts und löst die Range nicht
  auf. `RANGE=origin/main~5..origin/main` endet mit 0 Befunden und Exit 0,
  `RANGE=deadbeef..cafebabe` genauso, mit beiden Pins. Einen lauten Abbruch
  wegen der `loose-*`-Packs zeigt das Target deshalb nicht, es liest nichts.
  Das ist dieselbe Klasse wie Punkt 1 in d-check#4, dort für `vcs` gemeldet.
- Mit einem `commits:`-Block, per Bind-Mount über `.d-check.yml` gelegt (das
  Arbeits-Repo blieb read-only), bricht `commits` im Arbeits-Repo mit **beiden**
  Pins ab (Exit 2): die letzten fünf Commits mit
  `Range-Basis-Vorfahren nicht lesbar: object not found`,
  `abcfcaa8…..ff3d3403e` mit `reference not found`. Im frischen Klon meldet
  dieselbe Konfiguration die fünf Commits als `commit-untraceable` (Exit 1),
  die Probe greift also. Das deckt sich mit dem Benutzerhandbuch von d-check:
  `commits` war vom stillen Fall nie betroffen. Für `commits` ändert die Hebung
  nichts.

**Grenzen**

- d-check liest `loose-*`-Packs auch mit `v0.76.1` nicht (dokumentierte Grenze,
  Issue pt9912/d-check#4, Punkt 2). Jeder git-lesende Lauf direkt gegen das
  Arbeits-Repo bricht deshalb ab, solange dort solche Packs liegen; das
  Handbuch von d-check nennt `git repack -A -d` als Abhilfe. `make doc-immutable`
  umgeht das über den Klon.
- `vcs` und `commits` ohne eigenen Konfigurationsblock enden weiter mit
  Exit 0, ohne die Range aufzulösen.

**Restflächen**

| Punkt | Ort |
| --- | --- |
| `loose-*`-Packs lesen oder die Ursache in der Meldung nennen; `vcs` ohne `vcs:`-Block | Issue an `pt9912/d-check` ([#4](https://github.com/pt9912/d-check/issues/4)) |
| `commits` ohne `commits:`-Block ist ebenso inert wie `vcs` ohne `vcs:`-Block | Ergänzung zu d-check#4, noch nicht gestellt (Eigner) |

**Bewusst ohne eigenen Ort:** `make doc-commits` bleibt in diesem Repo ohne
`commits:`-Block und ist kein Gate. Ein solcher Block wäre eine eigene
Entscheidung über eine Commit-Konvention, und im Arbeits-Repo bräche er heute
ohnehin ab.

**Paket → Commit**

| Paket | Commit |
| --- | --- |
| Pin-Hebung (`Makefile`, `make/d-check.mk`) | `56d2648a7` |
| Move mit Closure, Verweise nachgezogen | der Move-Commit |
