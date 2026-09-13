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

## Was zu klären ist

Drei Wege, die sich ausschließen; die Entscheidung gehört dem Eigner,
weil sie die Rolle des Templates festlegt:

1. **Gate schärfen**: die Formula-Version gegen den neuesten
   veröffentlichten Release-Tag prüfen und rot werden, wenn sie
   zurückhängt. Macht die Handarbeit aus 4.6 erzwingbar, statt sie zu
   erinnern.
2. **Handarbeit abschaffen**: Version und SHA im Post-Release-Schritt
   maschinell nachziehen (dieselbe Quelle, aus der 4.6 die SHA zieht —
   die Download-URL). Der Gate bleibt, wie er ist, und hat dann wieder
   eine Aussage.
3. **Template aufgeben**: wenn der Tap der einzige echte Kanal ist,
   braucht das Repo keine zweite, manuell gepflegte Formula. Dann
   entfallen 4.6 und der Workflow.

Weg 2 und 3 sind die ehrlichen; Weg 1 härtet den Zustand, den 2 oder 3
beseitigen würden. Vorher zu klären ist, was
`verify-homebrew-formula.yml` überhaupt absichern soll, das
`homebrew-releaser` nicht schon absichert — dieselbe Frage wie bei jedem
Gate, das eine Zusage trägt, die ein anderer Pfad auch trägt.

## Sofortmaßnahme (erledigt)

Im Release 1.4.0 ist das Template auf `1.4.0` und die SHA des
publizierten ZIP gesetzt. Das behebt den Stand, nicht die Ursache.
