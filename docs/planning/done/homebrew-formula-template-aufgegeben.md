# Das Homebrew-Formula-Template im Repo ist aufgegeben

## Befund

`verify-homebrew-formula.yml` las Version **und** SHA aus dem Repo-Template
und prüfte danach, dass die installierte CLI genau diese Version meldet:

```
VERSION="$(grep -E '^\s+version "[^"]+"' … )"
…
d-migrate --version | grep -F "d-migrate version ${VERSION}"
```

Das ist selbstbezüglich: eine Formula, die auf ein älteres, noch
existierendes Release zeigt, ist in sich stimmig und installiert sauber. Der
Gate belegte damit „die Formula installiert, was sie behauptet", nicht „die
Formula behauptet den aktuellen Stand".

Wie lange das unbemerkt lief, war am Stand ablesbar: beim 1.4.0-Cut stand
das Template auf **1.1.0** — 1.2.0, 1.3.0 und 1.3.1 waren ohne Anpassung
vergangen, der Gate in jedem dieser Releases grün. Der Lauf zum Tag `v1.4.0`
hat wirklich installiert und gesmoked (kein Skip, beide Schritte `success`),
nur eben 1.1.0.

Gefunden beim Abarbeiten von Schritt 4.6 der Release-Doku für 1.4.0.

## Warum kein Anwenderschaden entstand

Der Kanal, über den Anwender installieren, ist der Tap
`pt9912/homebrew-d-migrate`. Dessen Formula erzeugt `homebrew-releaser` mit
automatisch berechneter Prüfsumme; dort konnte nichts veralten. Betroffen war
allein das Repo-Template — eine Datei, die drei Releases lang etwas Falsches
über den eigenen Stand sagte, und ein Gate ohne Aussage.

## Was den Ausschlag gab

Zwei Messungen, nicht eine Abwägung.

**Erstens:** `release-homebrew.yml` trägt einen zweiten Job `verify-homebrew`,
und der prüft strikt mehr.

| | der aufgegebene Workflow | Job `verify-homebrew` |
| --- | --- | --- |
| Woher die Sollversion kommt | aus der Formula selbst | aus dem **Tag** (`VERSION="${TAG#v}"`) |
| Was installiert wird | Repo-Template im Ephemeral-Tap | der **publizierte Tap** |
| Wartet auf den Tap-Commit | nein | ja, 6 × 20 s, sonst rot |
| Aussage bei veralteter Formula | grün | rot |

Am Tag `v1.4.0` ist er in jedem Schritt grün gelaufen — Warten auf den
Tap-Commit, `brew install` aus dem publizierten Tap, Smoke gegen die
Tag-Version.

**Zweitens:** der `install:`-Block stand doppelt — einmal im Template, einmal
inline im Workflow. Beide Fassungen waren **byte-identisch**. Das Template war
also auch für die Install-Logik keine Quelle, sondern eine Kopie. Das einzige,
was nur dort stand, war der Kommentar, der erklärt, warum `Dir["*"]` und nicht
`Dir["d-migrate-*"]` — Homebrew strippt beim Entpacken das einzelne
Top-Level-Verzeichnis.

Damit war der Weg entschieden, ohne dass eine Zusicherung zur Disposition
stand: es gab keine, die nur der aufgegebene Gate trug.

## Was geschah

- `packaging/homebrew/d-migrate.rb` <!-- d-check:ignore (in diesem Slice entfernt; ADR 0011) -->
  und `.github/workflows/verify-homebrew-formula.yml` entfernt.
- Der erklärende Kommentar ist in den `install:`-Block von
  [`release-homebrew.yml`](../../../.github/workflows/release-homebrew.yml)
  gewandert — die Stelle, die bleibt.
- Schritt 4.6 der [Release-Doku](../../user/releasing.md) entfällt; 4.7 und
  4.8 rücken auf 4.6 und 4.7. Die Verifikationsliste nennt statt der
  Handarbeit den `verify-homebrew`-Job.
- [`spec/architecture.md`](../../../spec/architecture.md) beschreibt unter
  „Homebrew-Basis" jetzt den Tap und die Verifikation gegen den publizierten
  Stand, nicht mehr die Formula im Repo.
- Beide READMEs und die Vorab-Prüflisten nachgezogen.

Vor dem Entfernen war das Template im Release 1.4.0 noch auf den aktuellen
Stand gebracht worden (Version + SHA des publizierten ZIP). Das behob den
Stand; dieser Schritt behebt die Ursache.

## Was bleibt

Der `verify-homebrew`-Job ist damit die einzige Homebrew-Verifikation — und
die einzige, die je eine Aussage trug. Fällt er auf einem Tag aus, steht der
manuelle Weg in Abschnitt 6.6 der Release-Doku: aus dem publizierten Tap
installieren und `d-migrate --version` gegen die Tag-Version halten.
