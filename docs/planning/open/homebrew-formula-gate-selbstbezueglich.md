# Der Homebrew-Gate prüft die Formula gegen sich selbst

- **Status**: Befund (gemessen)
- **Trigger**: Release 1.4.0, Schritt 4.6 der
  [Release-Doku](../../user/releasing.md) — das Repo-Template
  `packaging/homebrew/d-migrate.rb` stand auf `1.1.0`, während
  `Verify Homebrew Formula` zum Tag `v1.4.0` grün meldete.
- **Aktivierungsbedingung**: sobald der Gate eine Aussage über den
  *aktuellen* Release tragen soll — also beim nächsten Release, das sich
  nicht mehr auf Handarbeit im Ablauf verlassen will.

## Was gemessen ist

`verify-homebrew-formula.yml` liest Version **und** SHA aus der Formula
und prüft danach, dass die installierte CLI genau diese Version meldet:

```
VERSION="$(grep -E '^\s+version "[^"]+"' packaging/homebrew/d-migrate.rb …)"
…
d-migrate --version | grep -F "d-migrate version ${VERSION}"
```

Das ist eine Selbstbezüglichkeit: eine Formula, die auf ein beliebiges
**älteres**, noch existierendes Release zeigt, ist in sich stimmig und
installiert sauber. Der Lauf zum Tag `v1.4.0`
([Run 34742498178](https://github.com/pt9912/d-migrate/actions/runs/34742498178))
hat wirklich installiert und gesmoked — Schritt 5 und 6 `success`, kein
Skip — nur eben **1.1.0**. Er belegt „die Formula installiert, was sie
behauptet", nicht „die Formula behauptet den aktuellen Stand".

Wie lange das unbemerkt lief, ist am Stand ablesbar: 1.2.0, 1.3.0 und
1.3.1 sind ohne Anpassung des Templates vergangen, der Gate war in jedem
dieser Releases grün.

## Warum es nicht der echte Installationskanal ist

Der Kanal, über den Anwender wirklich installieren, ist der Tap
`pt9912/homebrew-d-migrate`. Dessen Formula erzeugt `homebrew-releaser`
mit **automatisch** berechneter SHA gegen
`d-migrate-X.Y.Z-homebrew.tar.gz`; dort ist nichts von Hand zu pflegen
und nichts veraltet. Betroffen ist allein das Repo-Template, das laut
Release-Doku „nur Referenz + Input für `verify-homebrew-formula.yml`"
ist.

Die Folge ist deshalb kein Anwenderschaden, sondern ein Gate ohne
Aussage — und eine Datei im Repo, die drei Releases lang etwas Falsches
über den eigenen Stand sagte.

## Was der echte Kanal schon absichert (gemessen 2026-09-13)

Die Frage, ob `verify-homebrew-formula.yml` etwas trägt, das sonst niemand
trägt, ist beantwortet: **nein.** `release-homebrew.yml` hat einen eigenen
Job `verify-homebrew`, und der prüft strikt mehr:

| | `verify-homebrew-formula.yml` | `release-homebrew.yml` Job `verify-homebrew` |
| --- | --- | --- |
| Woher die Sollversion kommt | aus der Formula selbst | aus dem **Tag** (`VERSION="${TAG#v}"`) |
| Was installiert wird | Repo-Template im Ephemeral-Tap | der **publizierte Tap** (`brew install d-migrate`) |
| Wartet auf den Tap-Commit | nein | ja, 6 × 20 s, sonst rot |
| Aussage bei veralteter Formula | grün | rot |

Der zweite Job ist nicht selbstbezüglich: die Sollversion kommt aus dem Tag,
nicht aus einer Datei, die mitwandern kann. Am Tag `v1.4.0` ist er gelaufen
und in **jedem** Schritt grün — Warten auf den Tap-Commit, `brew install` aus
dem publizierten Tap, Smoke gegen die Tag-Version
([Run 34742498270](https://github.com/pt9912/d-migrate/actions/runs/34742498270)).

Damit prüft der echte `brew install`-Kanal sich bereits gegen die richtige
Quelle, und zwar bei jedem Stable-Tag.

## Was zu entscheiden ist

Die Messung oben nimmt einem der drei denkbaren Wege die Grundlage:

1. **Gate schärfen** (Formula-Version gegen den neuesten Release-Tag prüfen) —
   dupliziert damit nur, was `verify-homebrew` schon tut, und härtet einen
   Zustand, den Weg 3 beseitigt.
2. **Handarbeit maschinell nachziehen** (Version + SHA im Post-Release-Schritt
   setzen) — behebt das Veralten, lässt aber die Frage offen, wofür das
   Template dann noch da ist.
3. **Template aufgeben** — `packaging/homebrew/d-migrate.rb`,
   `verify-homebrew-formula.yml` und Schritt 4.6 der Release-Doku entfallen.
   Die Messung zeigt: es geht dabei keine Zusicherung verloren.

Weg 3 ist der von der Messung getragene. Zu prüfen bleibt vor dem Löschen
**eine** Sache: der `install:`-Block steht doppelt — einmal im Template, einmal
inline in `release-homebrew.yml` (Zeilen 77–81). Verglichen werden die beiden
von nichts; das Template ist also auch heute keine Quelle, sondern eine Kopie.
Wer es löscht, verliert keine geprüfte Zusage — aber die zweite Lesart der
Install-Logik, die beim letzten Abgleich vor 1.0.0 von Hand herangezogen wurde.
Ob das ein Verlust ist, ist die eigentliche Eignerfrage.

## Sofortmaßnahme (erledigt)

Im Release 1.4.0 ist das Template auf `1.4.0` und die SHA des
publizierten ZIP gesetzt. Das behebt den Stand, nicht die Ursache.
