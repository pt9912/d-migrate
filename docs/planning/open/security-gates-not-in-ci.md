# Tracker: Security-Gates laufen nicht in der CI, und nichts läuft zeitgesteuert

> **Status:** Befund mit Erhebung (Draft) / Trigger Watch (2026-08-15)
> **Trigger:** Beim v1.0.0-Tag fiel auf, dass `dependency-submission.yml` seit
> mindestens dem 2026-07-31 bei **jedem** `main`-Push scheiterte (behoben `66a27d99`).
> Die Erhebung danach zeigte: das ist kein Einzelfall, sondern ein Muster.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung, ob Dependabots
> wöchentlicher Lauf als CVE-Abdeckung genügt oder ein unabhängiger Scanner
> dazukommt — und wenn ja, welcher und mit welchem Prüfgegenstand.

## Erhebung (2026-08-15)

| Mechanismus | Verankerung | Läuft in der CI? |
| --- | --- | --- |
| **Dependabot** (Security- + Version-Updates) | GitHub-Dienst, `schedule: weekly, monday` | ja (außerhalb des Repos) |
| **`dependency-submission.yml`** — speist den Dependency-Graph | `main`-Push | ja, war aber fünf Wochen defekt |
| **semgrep** | `make gates`, `make docker-gates` | **nein** — in keinem Workflow |
| **a-check** (Architektur-Gate) | `make gates`, `make docker-gates` | **nein** — in keinem Workflow |
| CodeQL / Trivy / Grype / OSV | — | nicht vorhanden |

**Zeitgesteuert läuft nichts Sicherheitsrelevantes.** Die einzigen beiden Workflows
mit `schedule:` sind `sample-db-scale.yml` und `perf-acceptance.yml`, beide zu
Performance.

Was die CI tatsächlich an `make`-Zielen fährt: `ci-build`, `docs-check`,
`release-assets`, `docker-oci-build`, `native-runtime-build`. Das `gates`-Ziel, das
semgrep und a-check bündelt, ist **kein** CI-Ziel — es existiert für den lokalen
Aufruf.

## Zwei Anliegen, nicht eines

**1. Vorhandene Gates laufen nicht.** semgrep ist gepinnt, hermetisch (`--network
none`) und hat bei seiner Einführung reale Befunde geliefert. a-check ebenso.
Beide sind an eine Person gebunden, die daran denkt, `make gates` zu tippen.
[ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md) führt „die
Security-Gates (semgrep offline, a-check-Architektur, `dependency-submission`)"
als Teil der **1.0.0-Sicherheits-Interimslatte** — von den dreien lief zum
1.0.0-Tag genau einer, und der war fünf Wochen kaputt.

**Entscheidungsfrei**: semgrep und a-check in einen Workflow zu heben braucht keine
Vorabklärung, nur Arbeit. Dieser Teil kann jederzeit vorgezogen werden.

**2. Nichts prüft ohne Push.** CVEs tauchen auf, ohne dass sich das Repo ändert.
Ein push-getriggertes Gate schweigt in genau diesem Fall: Zwischen RC4 (09.08.) und
dem 15.08. lag das Repo sechs Tage still — in dieser Zeit lief keine einzige
Prüfung, obwohl draußen jederzeit ein CVE für eine der Abhängigkeiten hätte
erscheinen können.

**Hier steckt die Entscheidung**, und sie hängt an einer Abgrenzung, die leicht
verschwimmt: **semgrep ist kein CVE-Scanner.** Es analysiert eigenen Code und
eigene Konfiguration. CVEs in Abhängigkeiten findet der Dependency-Graph plus
Dependabot — oder ein eigener Scanner.

## Warum es fuer die Images bisher keinen Scanner gibt — und was sich geaendert hat

Es war **kein Versehen**: Das Security-Vollaudit vom 2026-07-17 hat die Frage
behandelt und Digest-Pinning des Basis-Images ausdruecklich abgelehnt —

> Digest-Pinning ist TOFU und würde die Tag-Re-Pushes verwerfen, die der
> **Auslieferungsweg für OS-CVE-Fixes** sind.

Die Strategie lautet also: `FROM eclipse-temurin:21-jre-noble` bleibt tag-gepinnt,
jeder Release-Build zieht den aktuellen Stand, OS-CVE-Fixes kommen von Upstream.
Kohaerent — und sie erklaert, warum nie ein Scanner eingeplant wurde.

**Die Begruendung stuetzt sich aber auf eine Praemisse, die seit
[ADR 0041](../../adr/0041-oci-image-aus-dockerfile-runtime-statt-jib.md) nicht mehr
gilt.** Dieselbe Audit-Zeile argumentiert:

> das publizierte GHCR-Image stammt von **Jib** … der `apt-get spatialite`-Vorwurf
> ist damit gegenstandslos (Jib-Images haben keinen apt-Layer)

Seit RC3 kommt das publizierte Image aus der Dockerfile-`runtime`-Stage — **mit**
apt-Layer (`libsqlite3-mod-spatialite` samt Abhaengigkeiten). Die Flaeche, die das
Audit wegargumentiert hat, existiert real, und die Einschaetzung wurde nicht
nachgezogen.

Unabhaengig von der Pinning-Frage bleiben drei Luecken:

1. **Zwischen Releases altert das Image.** Die Tag-Re-Push-Strategie wirkt nur beim
   naechsten Bau. `:latest` zeigt auf 1.0.0 und bleibt dort bis 1.0.1.
2. **Die Exposition ist unbekannt.** Fixes werden geerbt, aber niemand weiss, welche
   CVEs im ausgelieferten Image gerade stecken.
3. **Der apt-Layer ist ungeprueft** — und war zum Audit-Zeitpunkt noch nicht einmal
   vorhanden.

## Wege

1. **Dependabot genügt.** Wöchentlich, deckt Abhängigkeits-CVEs ab, erzeugt direkt
   PRs statt nur Meldungen. Voraussetzung ist ein intakter Dependency-Graph — was
   fünf Wochen nicht der Fall war und wofür es bis heute kein Frühwarnsignal gibt.
   Billigster Weg; deckt das **gebaute Image** nicht ab (Basis-Image-CVEs,
   `mod_spatialite`, apt-Stand).
2. **Unabhängiger Scanner im Nightly** (OSV-Scanner, Trivy oder Grype). Prüft je
   nach Wahl Abhängigkeiten **und** das Container-Image. Bringt eine
   Fremdabhängigkeit mit Pin-/Offline-Frage mit — das semgrep-Gate hat dafür
   bereits ein Muster (gecacht, SHA256-gepinnt, `--network none`).
3. **Beides**: Dependabot für Abhängigkeiten, Nightly für das Image.

Unabhängig vom gewählten Weg: **semgrep und a-check in die CI** (Anliegen 1) und
ein Frühwarnsignal dafür, dass ein Security-Gate überhaupt läuft.

## Warum das nicht als „roter Lauf" durchgeht

Der Submission-Defekt blieb fünf Wochen unbemerkt, weil der Workflow **nur bei
`main`-Pushes** lief — also nur beim Release, wenn die Aufmerksamkeit beim Release
liegt. Der Fix hat deshalb einen `workflow_dispatch` mitbekommen. Dasselbe
Strukturproblem trifft jedes Gate, das ausschließlich an einem seltenen Ereignis
hängt.

## Empfehlung (2026-08-15, noch keine Eigner-Entscheidung)

**Ja zu einem Nightly — aber berichtend statt blockierend, auf das Image beschraenkt,
und nicht als Erstes.**

**Warum ja:** Das Argument ist strukturell, nicht graduell. Ein push-getriggertes
Gate ist gegen CVEs prinzipiell blind, weil CVEs auftauchen, ohne dass sich das Repo
aendert. Dazu die Eigenart dieses Produkts: `mcp serve --transport http` ist ein
**langlaufender, netzexponierter Dienst mit JWT-Validierung**. Eine CVE in Ktor,
`nimbus-jose-jwt` oder der JRE trifft dort anders als eine CLI, die drei Sekunden
lebt.

**Berichtend, nicht blockierend.** Ein Nightly, das an nicht behebbaren
Basis-Image-CVEs rot wird, ist in zwei Wochen ein ignoriertes Abzeichen — und dann
schlechter als nichts, weil es antrainiert, Sicherheitssignale wegzuklicken.
Sinnvolle Schwelle fuer "jemand sieht hin": Critical **mit verfuegbarem Fix**.

**Auf das Image beschraenkt.** Die Abhaengigkeiten sind Dependabots Aufgabe; ein
Scanner, der dieselben Java-Dependencies nochmal meldet, produziert Doppelbefunde.
Ungedeckt ist das ausgelieferte Image: Basis-OS, apt-Layer, JRE.

**Reihenfolge — nicht der Scanner zuerst:**

1. **semgrep + a-check in die CI.** Entscheidungsfrei und die groessere Luecke: zwei
   Gates, die existieren, gepinnt und hermetisch sind — und nie laufen. Einen neuen
   Scanner einzufuehren, waehrend die vorhandenen brachliegen, optimiert am falschen
   Ende.
2. **Nightly-Scan des publizierten Images** (Trivy oder Grype), berichtend.
3. **Fruehwarnung, dass Gates ueberhaupt laufen.** Der Submission-Defekt blieb fuenf
   Wochen unbemerkt, weil niemand merkt, wenn etwas *nicht* laeuft. Ein rotes Gate
   faellt auf; ein schweigendes nicht.

Punkte 1 und 3 sind unabhaengig von der Scanner-Entscheidung und jederzeit
vorziehbar.

**Verworfene Alternative — geplanter Rebuild statt Scan.** Wenn die Strategie ohnehin
"Fixes bei jedem Bau erben" lautet, laege nahe, das Image geplant neu zu bauen: das
*liefert* den Fix, statt nur darueber zu berichten. Der Haken ist Unveraenderlichkeit
— man muesste `1.0.0` mit anderem Digest neu publizieren und damit einen
Versions-Tag nachtraeglich veraendern. Praktikabel waere es nur fuer `:latest`, womit
`:latest` und der Versions-Tag auseinanderliefen. Kein Ersatz fuer einen Scan, aber
festgehalten, damit die Idee nicht als ungeprueft naheliegend wiederkehrt.

## Nachzuziehen

[ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md) braucht eine
Fußnote: Die dort genannte Interimslatte beschreibt drei Gates, von denen zwei nie
in der CI liefen und eines fünf Wochen defekt war. Ohne die Notiz liest der nächste
Leser die Zeile als lückenlos — und 1.0.0 ist mit dieser Aussage herausgegangen.
