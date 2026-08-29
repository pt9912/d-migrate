# Tracker: Dependabot meldet den Build-Graphen, nicht das Auslieferungsartefakt

> **Status:** Abgeschlossen (29.08.2026) — siehe [Closure](#closure).
> **Ursprünglich:** Tracker / Vorabklärung (28.08.2026)
> **Trigger:** Die Dependabot-Seite zeigte 38 offene Alerts, davon 8 „high".
> Gemessen am publizierten Image betrifft **keiner** die ausgelieferten
> Versionen.
> **Ergebnis:** 38 offene Alerts → **0**, ohne eine einzige Unterdrückung.

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

[`security-gates-not-in-ci.md`](../open/security-gates-not-in-ci.md) betraf dieselbe
Frage aus der anderen Richtung: ein Gate, das lief, aber nicht blockierte.

## Closure

Umgesetzt am 29.08.2026 in zwei Schritten, jeder einzeln gemessen.

**Schritt 1 — nur `runtimeClasspath`** (`c65e818b`): 461 → 202 Pakete, 29
Alerts fielen weg. Neun blieben, und sie waren der eigentliche Fund: der Graph
führte **jede** Version doppelt.

    jackson-core      2.12.7     und  2.21.5
    jackson-databind  2.12.7.1   und  2.21.5
    nimbus-jose-jwt   9.37.2     und  10.9

**Schritt 2 — nur das CLI-Modul** (`08038c36`): 202 → 192 Pakete, die niedrigen
Versionen verschwanden, Alerts auf 0.

**Die Lehre: ein Modulstand ist kein Auslieferungsstand.** Für sich aufgelöst
landet ein Modul niedriger; erst im CLI-Modul, aus dem `installDist` die
Distribution baut, hebt Gradles Konfliktauflösung an. Der Graph muss deshalb
genau **eine** Konfiguration melden — `:adapters:driving:cli:runtimeClasspath` —
und nicht die Vereinigung aller Module.

Verifiziert wurde **positiv** gegen die SBOM (Jackson muss bleiben, Netty
verschwinden), nicht über die Alert-Zahl: ein zu enger Filter liefert einen
leeren Graphen, und der sieht wie Erfolg aus. Die 192 gegenüber 177 Jars im
Image sind BOM-Einträge, die keine Jars erzeugen.

### Arbeitspakete

1. ~~Konfigurationsfilter für die Submission.~~
2. ~~Entscheiden, wie Build-Zeit-Abhängigkeiten sichtbar bleiben.~~ — sie
   bleiben es über die wöchentlichen `gradle`-/`github-actions`-/`docker`-
   Versions-Updates in `.github/dependabot.yml`. Der Filter nimmt ihnen die
   falsche Einordnung als Produktrisiko, nicht die Aufmerksamkeit.
3. Test-Abhängigkeiten heben — **entfällt als eigener Punkt**: sie kommen über
   dieselben Versions-Updates mit. Was bleibt, ist der eine Fall, der eine
   Entscheidung verlangt: [`kotlin-gradle-plugin-cve-beta.md`](../open/kotlin-gradle-plugin-cve-beta.md).
