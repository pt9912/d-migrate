---
status: accepted
date: 2026-08-16
decision-makers: pt9912
consulted: .github/dependabot.yml, .github/workflows/image-scan.yml, docs/planning/open/security-gates-not-in-ci.md
informed: docs/user/releasing.md, README.md, README.de.md, .github/workflows/build.yml, .github/workflows/integration.yml, .github/workflows/coverage-modules.yml
---

# Ein Branch statt `develop` → `main` — Entwicklung nach v1.0.1 auf `main`

> **Status: accepted (2026-08-16).** Nach dem Release **v1.0.1** entfällt der
> `develop`-Branch. Entwicklung, Integration und Releases finden auf `main` statt;
> Tags `vX.Y.Z` werden weiterhin auf `main` gesetzt. Bis einschließlich v1.0.1 gilt
> das bisherige Modell unverändert.

## Kontext und Problemstellung

Das bisherige Modell (beschrieben in [`releasing.md`](../user/releasing.md), nie in
einem ADR festgehalten) sah `develop` als Entwicklungsbranch und `main` als reinen
Release-Branch: Jeder Commit auf `main` war ein Release-Merge.

**Der Konflikt ist strukturell, nicht graduell.** GitHub behandelt den
Default-Branch als Quelle der Wahrheit für Repo-Automatisierung; dieses Modell
behandelte ihn als Archiv. Damit lag Automatisierungs-Konfiguration
**konstruktionsbedingt am falschen Ort**. Betroffen ist mindestens:

| Mechanismus | Verhalten |
| --- | --- |
| `.github/dependabot.yml` | wird **nur** vom Default-Branch gelesen; Änderungen auf `develop` bleiben wirkungslos |
| `schedule:` in Workflows | ein Zeitplan, der nur auf `develop` liegt, **feuert nie** |
| Registrierung von `workflow_dispatch` | `gh workflow run --ref develop` quittiert mit **404**, solange die Datei nicht auf `main` liegt |

Am 2026-08-15/16 lief das dreimal auf, und **jedes Mal als stiller Ausfall statt
als Fehler**: Die Dependabot-Konfiguration zielte auf den falschen Branch und
musste außer der Reihe direkt auf `main` committet werden; der neue
Trivy-Nightly [`image-scan.yml`](../../.github/workflows/image-scan.yml) hätte auf
`develop` nie ausgelöst; und sein Dispatch scheiterte mit 404. Das ist dieselbe
Klasse „schweigendes Gate", die
[`security-gates-not-in-ci.md`](../planning/open/security-gates-not-in-ci.md)
behandelt — hier vom Branch-Modell selbst erzeugt.

## Entscheidungstreiber

- **Der Archiv-Nutzen wird bereits von Tags erbracht.** Ein Branch, der
  ausschließlich Release-Merges erhält, ist ein redundanter Zeiger auf den
  neuesten Tag. `v1.0.0` ist unveränderlich und präzise; `main` als Branch fügt
  dem nichts hinzu — außer der GitHub-Startseite.
- **Jede Ausnahme ist Handarbeit, die vergessen werden kann.** Der Cherry-Pick
  nach `main` ist kein einmaliger Sonderfall, sondern die dauerhafte Folge des
  Modells. Er fällt nur auf, wenn jemand daran denkt.
- **Die Kosten der Trennung fallen bei jedem Release an** (Version-Bump,
  Merge, Rück-Merge), ihr Nutzen ist bei einem Solo-Betrieb gering: `main`
  bleibt auch ohne Trennung durch die CI-Gates auf jedem Push releasefähig.

## Betrachtete Optionen

1. **Trennung beibehalten, Automatisierungs-Konfiguration bewusst auf `main`
   pflegen.** Verlangt, an zwei Orte zu denken — was nachweislich dreimal an
   einem Tag misslang.
2. **`develop` zum Default-Branch machen.** Löst das Automatisierungsproblem und
   erhält einen Release-Branch. Verliert aber dieselbe Startseiten-Eigenschaft wie
   Option 3, und der verbleibende Vorteil (Release-Archiv) wird schon von Tags
   erbracht.
3. **Nur noch `main`** (gewählt).

## Entscheidung

Gewählt: **Option 3**, mit dem Schnitt beim Release **v1.0.1**. Dann fallen `main`
und `develop` auf einem released, bekannt-guten Stand zusammen — statt dass `main`
unvermittelt unreleasten Code enthält.

## Konsequenzen

- **Positiv:** Automatisierungs-Konfiguration liegt dort, wo GitHub sie liest.
  Kein Cherry-Pick-Ritual, kein Merge-Tanz beim Release, `releasing.md` verliert
  einen ganzen Ablaufschritt.
- **Negativ:** Die GitHub-Startseite zeigt künftig den Entwicklungsstand statt des
  Releases. Abgefedert durch den „Status"-Block in beiden READMEs, der aktuelles
  Stable und aktuelle Vorabversion ausdrücklich benennt und über
  `make readme-parity-gate` sowie [`releasing.md`](../user/releasing.md) 3.6
  gepflegt wird.
- **Abgrenzung:** Betrifft **nur** die Branch-Struktur. Tags, Versionierung
  (SemVer mit `-SNAPSHOT` zwischen Releases) und der Release-Ablauf ab dem Tag
  bleiben unverändert.

## Umstellung (nach dem v1.0.1-Tag)

1. `develop` löschen (lokal und remote), nachdem `main` den Tag trägt.
2. `target-branch: develop` aus [`dependabot.yml`](../../.github/dependabot.yml)
   entfernen (dreimal) — ohne die Zeile zielt Dependabot auf den Default-Branch.
3. In den Workflows `develop` aus den `branches:`-Listen nehmen
   (`build.yml`, `integration.yml`, `coverage-modules.yml`,
   `verify-homebrew-formula.yml`, `sample-db-*`, `bi-demo-smoke.yml`).
4. [`releasing.md`](../user/releasing.md) Abschnitt 1 (Branching-Modell) und den
   Merge-Schritt in Abschnitt 4 neu schreiben.
5. Contributing-Abschnitte in [`README.md`](../../README.md) und
   [`README.de.md`](../../README.de.md) auf `main` umstellen — **beide**, sonst
   driften sie wie beim 1.0.0-RC2-Cut auseinander.
