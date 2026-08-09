---
status: accepted
date: 2026-08-09
decision-makers: pt9912
consulted: docs/planning/next/sdkman-distribution.md, docs/planning/in-progress/roadmap.md, docs/user/releasing.md
informed: .github/workflows/sdkman-release.yml
---

# SDKMAN-Distribution ist kein 1.0.0-Gate — 1.0.0 wird ohne SDKMAN geschnitten

> **Status: accepted (2026-08-09).** Die am 2026-07-31 gesetzte Wartefrist auf den
> Merge des SDKMAN-Candidate-PRs ist am 2026-08-05 ohne Merge abgelaufen. 1.0.0-Stable
> wird **ohne** SDKMAN geschnitten; die Roadmap-Zeile bleibt sichtbar `⛔` und kann
> **nachträglich** geschlossen werden, ohne das Release anzufassen.

## Kontext und Problemstellung

SDKMAN ist die dritte der drei 1.0.0-Distributionszeilen — GraalVM-Native-Image `✅`
und der Docker-Hub-Spiegel `✅` (letzterer seit dem `v1.0.0-RC2`-Tag) sind erledigt.

Auf **unserer** Seite ist der Kanal fertig gebaut: `sdkman-release.yml` läuft auf
Tag-Push, wartet auf das **Asset** (nicht nur auf das Release, weil die SDKMAN-API die
URL selbst lädt), meldet das UNIVERSAL-JVM-Launcher-ZIP und setzt `PUT /default` nur
bei Stable. Der `workflow_dispatch`-Pfad wurde am 2026-07-31 mit `tag=v1.0.0-RC2` real
durchlaufen (grün, Skip-mit-Notice mangels Credentials). Was fehlt, ist
**ausschließlich extern**: [PR #794](https://github.com/sdkman/sdkman-db-migrations/pull/794)
an `sdkman/sdkman-db-migrations` legt den Candidate `dmigrate` an und muss gemergt
werden; danach folgen GPG-Mail an `info@sdkman.io` und die beiden Secrets.

Der Eigner hat am 2026-07-31 entschieden, bis zum **2026-08-05** auf den Merge zu
warten. Stand **2026-08-09** ist der PR unverändert `OPEN`, Review-Status
`REVIEW_REQUIRED`, letzte Aktualisierung `2026-07-31T08:54Z` — keinerlei Bewegung. Die
Frist ist abgelaufen. Zu entscheiden ist: Wartet 1.0.0-Stable auf einen Merge in einem
fremden Repository, oder wird ohne SDKMAN geschnitten?

## Entscheidungstreiber

- **Fremdbeschaffung, kein Dev-Task.** Am offenen PR ändert kein Commit in diesem Repo
  etwas. Die Merge-Kadenz des Ziel-Repos reicht laut seiner eigenen PR-Historie von
  „selber Tag" bis **6 Monate** (Median grob 5–6 Wochen), und vor #794 liegen vier
  weitere Candidate-PRs. Ein Warten hat damit keinen planbaren Endpunkt.
- **Normativ ist SDKMAN kein Gate.** Das Lastenheft nennt den Kanal nicht, und
  [ADR 0039](0039-externer-security-audit-kein-1.0.0-gate.md) ordnet SDKMAN unter
  „Entscheidungstreiber" bereits ausdrücklich als Fremdbeschaffung ein, an der 1.0.0
  nicht hängt. Die Roadmap-`⛔`-Zeile ist deskriptiv, nicht normativ.
- **Nachträglicher Publish ist möglich und real erprobt.** `sdkman-release.yml` hat
  neben dem Tag-Trigger ein `workflow_dispatch` mit `tag`-Input; ein
  `gh workflow run sdkman-release.yml -f tag=v1.0.0` publiziert für einen längst
  veröffentlichten Tag — **kein Re-Release, keine Patch-Version**. Das unterscheidet
  diesen Fall von einer echten Verschiebung: die Zeile lässt sich später für **genau
  dieses** Release schließen.
- **Das Release kann daran nicht scheitern.** Der Workflow ist auf die Secrets gegatet;
  fehlen sie, gibt es Skip plus Notice statt eines roten Release-Laufs
  ([`releasing.md` 4.4.4](../user/releasing.md)). Ein nicht gemergter Candidate macht
  keinen Tag-Cut kaputt.
- **Ehrlichkeit.** Die Zeile darf weder fälschlich abgehakt werden („der Kanal ist doch
  gebaut") noch still verschwinden — ein Ausschluss oder eine Verschiebung gehört in
  einen ADR, nicht in eine gelöschte Tabellenzeile (Regel aus
  [ADR 0039](0039-externer-security-audit-kein-1.0.0-gate.md), Präzedenz
  [ADR 0037](0037-database-agnostic-first-staffelung.md)).

## Betrachtete Optionen

1. **1.0.0-Stable blockieren**, bis #794 gemergt ist — Release an einen Fremd-Merge
   ohne Termin gekoppelt.
2. **Zeile auf `✅` setzen**, weil die Automatik auf unserer Seite vollständig gebaut
   und einmal durchlaufen ist.
3. **Neue Wartefrist setzen** und den Cut erneut daran ausrichten.
4. **1.0.0 ohne SDKMAN schneiden**; Zeile bleibt sichtbar `⛔` mit Fußnote auf diese
   ADR; Nachpublizieren per `workflow_dispatch`, sobald der Candidate steht (gewählt).

## Entscheidung

Gewählt: **Option 4.** SDKMAN ist **kein 1.0.0-Gate**; 1.0.0-Stable wird ohne den
Kanal geschnitten. Die Roadmap-Zeile bleibt `⛔` — der Kanal ist unerledigt — und trägt
eine Fußnote, die auf diese ADR verweist und klarstellt, dass sie das Release **nicht**
blockiert.

**Keine neue Frist.** Option 3 wurde verworfen, weil ein zweiter Termin dieselbe
Abhängigkeit nur wiederholt: Der Wartepunkt ist der Merge selbst, und den steuert das
Ziel-Repo. Die Frist vom 2026-07-31 hatte ihren Zweck darin, den Fall einmal
auszuprobieren; das Ergebnis liegt vor.

Option 2 scheidet aus, weil `✅` an dieser Zeile bedeutet, dass ein Nutzer
`sdk install dmigrate` tatsächlich ausführen kann — nicht, dass wir dafür bereit sind.
Das ist dieselbe Latte, die beim Docker-Hub-Spiegel angelegt wurde (verifiziert per
`docker pull`, nicht per grüner CI).

**Geschlossen** wird die Zeile ausschließlich so:

1. #794 wird gemergt (Candidate-Freigabe erfolgt danach automatisch, kein Review-Gate);
2. armored GPG-Public-Key an `info@sdkman.io`, Antwort liefert `Consumer-Key` und
   `Consumer-Token`;
3. beide als GitHub-Secrets `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN` hinterlegen;
4. `gh workflow run sdkman-release.yml -f tag=vX.Y.Z` für den dann aktuellen
   Stable-Tag — auch rückwirkend für 1.0.0;
5. auf einem Host mit `sdk` und Java verifizieren: `sdk install dmigrate X.Y.Z` und
   `d-migrate --version`. **Erst dann** `⛔` → `✅`.

Diese ADR verzichtet **nicht** auf SDKMAN, ändert **nichts** an der gebauten Automatik
und verschiebt die Zeile auch **nicht** in einen späteren Milestone: Anders als der
externe Security-Audit ([ADR 0039](0039-externer-security-audit-kein-1.0.0-gate.md)),
der post-1.0.0 stattfindet und dort abgelegt ist, kann SDKMAN nachträglich für das
1.0.0-Release selbst wirksam werden. Die Zeile bleibt deshalb im 1.0.0-Milestone stehen.

## Konsequenzen

- **Positiv:** 1.0.0-Stable hängt nicht an einem Fremd-Merge ohne Termin. Der Kanal
  bleibt vollständig gebaut und scharf; das Nachziehen kostet einen Workflow-Dispatch,
  kein Release. Die Roadmap bleibt ehrlich (kein falsches `✅`, kein stilles Löschen).
- **Negativ:** Zum 1.0.0 funktioniert `sdk install dmigrate` **nicht**. JVM-CLI-Nutzer
  nehmen Homebrew, das OCI-Image, ein natives Binary oder das Launcher-ZIP direkt vom
  Release. Release-Notes und Ankündigung dürfen SDKMAN **nicht** als Bezugsweg nennen,
  solange die Zeile `⛔` ist.
- **Abgrenzung:** Die plattform-nativen SDKMAN-Distributionen (`LINUX_64`, `MAC_ARM64`,
  `WINDOWS_64`) waren ohnehin kein 1.0.0-Scope und bleiben es; diese ADR betrifft nur
  das UNIVERSAL-ZIP.

## Weitere Informationen

- [`sdkman-distribution.md`](../planning/next/sdkman-distribution.md) — Slice mit
  Artefakt, Automatik, Onboarding-Schritten und der Merge-Kadenz-Erhebung.
- [`releasing.md` 4.4.4](../user/releasing.md) — Verhalten des Workflows im
  Release-Ablauf (Secret-Gate, Skip mit Notice) und die Release-Checkliste.
- [ADR 0039](0039-externer-security-audit-kein-1.0.0-gate.md) — Quelle der Regel, dass
  Verschiebung/Ausschluss in einen ADR gehört; ordnet SDKMAN bereits als
  Fremdbeschaffung ein.
- [ADR 0037](0037-database-agnostic-first-staffelung.md) — Präzedenz für eine
  Verschiebung per ADR plus Roadmap-Fußnote.
