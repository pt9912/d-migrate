# Tracker: Dependabot meldet den Build-Graphen, nicht das Auslieferungsartefakt

> **Status:** Tracker / Vorabklärung (28.08.2026)
> **Trigger:** Die Dependabot-Seite zeigte 38 offene Alerts, davon 8 „high".
> Gemessen am publizierten Image betrifft **keiner** die ausgelieferten
> Versionen.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan; sonst
> Trigger-Watch.

## Befund

Die Gradle-Dependency-Submission-Action läuft in
[`dependency-submission.yml`](../../../.github/workflows/dependency-submission.yml)
**ohne Konfigurationsfilter** und meldet damit jeden auflösbaren
Klassenpfad — Test- und Build-Script-Konfigurationen eingeschlossen. Deshalb
steht in der Alert-Liste unter anderem `org.jetbrains.kotlin:kotlin-gradle-plugin`,
ein Werkzeug, das nie ausgeliefert wird.

Gegenprobe gegen das publizierte Image (`ghcr.io/pt9912/d-migrate:latest`,
177 Jars), Stand 28.08.2026:

| Paket | Alert-Bereich | Im Image |
| --- | --- | --- |
| `io.netty:*` (16 Alerts) | — | nicht enthalten (gruppenweiter Ausschluss im Parquet-Adapter) |
| `org.apache.logging.log4j:*` (4) | — | nicht enthalten |
| `org.codehaus.plexus:plexus-utils` (1) | — | nicht enthalten |
| `commons-io:commons-io` (1) | `>= 2.0, < 2.14.0` | 2.16.1 |
| `com.nimbusds:nimbus-jose-jwt` (1) | `< 9.37.4` | 10.9 |
| `com.fasterxml.jackson.core:*` (12) | bis `< 2.18.9` | 2.21.5 / 3.1.5 |

Der Vorbehalt: das gilt für die **deklarierten Koordinaten**. Geschattete Jars
(`parquet-jackson`) können ältere Klassen tragen; dafür ist der nightly
Image-Scan zuständig, mit dokumentierten Ausnahmen in `.trivyignore.yaml`.

## Warum das mehr als Kosmetik ist

Die Alert-Seite ist heute kein Risikomaß für das Produkt, sondern für den
Bauraum. Achtunddreißig offene Einträge, von denen keiner das
Auslieferungsartefakt betrifft, erziehen dazu, die Seite nicht mehr zu öffnen —
und dann fällt der eine echte Eintrag nicht auf, wenn er kommt.

Dasselbe Muster wie beim `solid-suppression-gate`, das von Mai bis August rot
war, ohne dass es jemanden störte: ein Signal, das immer schrillt, ist keins.

Bemerkenswert ist auch, wie die Alerts hier bisher verschwinden: **73 „fixed",
0 „dismissed"**. Es wird also gehoben, nicht weggeklickt — die 38 offenen sind
kein Triage-Rückstand, sondern schlicht noch nicht gehobene Graph-Einträge.

## Arbeitspakete (Skizze)

1. Konfigurationsfilter für die Submission, sodass die Alert-Liste den
   ausgelieferten Klassenpfad abbildet.
2. Entscheiden, wie Build-Zeit-Abhängigkeiten sichtbar bleiben. Sie sind
   Lieferketten-Fläche, auch wenn sie kein Produktrisiko sind —
   `kotlin-gradle-plugin` (CVE-2026-53914) ist der aktuelle Fall, und sein Fix
   verlangt ein Beta.
3. Test-Abhängigkeiten (Netty über die Container-Werkzeuge, log4j) bei
   nächster Gelegenheit heben.

## Angrenzend

[`security-gates-not-in-ci.md`](security-gates-not-in-ci.md) betraf dieselbe
Frage aus der anderen Richtung: ein Gate, das lief, aber nicht blockierte.
