# Trigger Watch: `THIRD-PARTY-NOTICES.md` in Release-Artefakte bündeln

> **Status:** erledigt (2026-09-08)
> **Trigger:** [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md)
> (Oracle als fünfter Dialekt) verlangt, die Oracle Free Use Terms and
> Conditions (FUTC) bei Weiterverbreitung des `ojdbc11`-Treibers mitzuführen.
> [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md) im Repo-Root
> dokumentiert die Pflicht, ist aber bislang **nur eine Repo-Datei** — sie
> landet nicht automatisch im Docker-Image oder in den Release-Assets
> (Fat-JAR/ZIP).
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung, WELCHER
> Mechanismus die Bündelung trägt (Dockerfile-`COPY` ins Runtime-Image,
> Aufnahme in `assembleReleaseAssets`, oder beides) und ob künftige
> Drittanbieter-Hinweise (weitere Treiber mit Hinweispflicht) denselben Weg
> nehmen sollen.

## Befund

- Kein bestehendes Muster im Repo: weder für MSSQL (`mssql-jdbc`, MIT, keine
  Hinweispflicht) noch für andere Abhängigkeiten gibt es eine
  Lizenz-Bündelungs-Mechanik in `Dockerfile` oder den Release-Asset-Tasks.
- `THIRD-PARTY-NOTICES.md` ist inhaltlich vollständig (FUTC-Pflichten,
  unmodifizierte Einbindung dokumentiert) — nur die physische Verteilung
  fehlt.

## Warum es doch dringend wurde

Der ursprüngliche Aufschub stand auf „Oracle steht bei Slice 0, es gibt noch
keine Auslieferung". Das gilt nicht mehr: Oracle ist durchgebaut, die CLI
hängt produktiv an `:adapters:driven:driver-oracle`, und **gemessen** liegt
`ojdbc11` im ausgelieferten ZIP. Die Pflicht trifft die Weiterverbreitung, und
weiterverbreitet wird seither.

## Entschieden und gebaut: alle Kanäle, die den Treiber tragen

Die offene Frage war, **welcher** Mechanismus die Bündelung trägt. Die Antwort
ist keine Auswahl, sondern eine Aufzählung: jeder Kanal, der den Treiber
ausliefert, liefert den Hinweis mit.

| Kanal | Weg | Ort im Artefakt |
| --- | --- | --- |
| ZIP / TAR | `distributions.main.contents` | `d-migrate-<v>/THIRD-PARTY-NOTICES.md` |
| Fat-JAR | `shadowJar { from(…) }` | `/THIRD-PARTY-NOTICES.md` |
| Release-Assets | `stageReleaseAssets` | neben den Downloads (und damit im GitHub-Release) |
| Runtime-Image | `COPY` im `Dockerfile` | `/opt/d-migrate/THIRD-PARTY-NOTICES.md` |

**Zwei Gates halten das fest**, weil eine entfernte `from(…)`-Zeile sonst erst
jemandem auffiele, der das Artefakt schon geladen hat:
`:adapters:driving:cli:verifyThirdPartyNotices` (läuft in
`assembleReleaseAssets` mit und öffnet jedes Archiv) und `make docker-smoke`
fürs Image. Beide sabotage-geprüft — ohne die Zeile meldet der Bau
`d-migrate-<v>-all.jar ships without THIRD-PARTY-NOTICES.md`.

Künftige Hinweispflichten nehmen denselben Weg: die Datei ist der Sammelort,
die vier Kanäle transportieren sie.

## Herkunft

Aufgefallen während Oracle-Slice 0 (Commit `d280fb8e`, 2026-09-05).
