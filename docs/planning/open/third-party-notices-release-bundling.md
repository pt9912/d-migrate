# Trigger Watch: `THIRD-PARTY-NOTICES.md` in Release-Artefakte bündeln

> **Status:** Trigger Watch (2026-09-05)
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

## Warum (noch) nicht gebaut

Kein Lastenheft-/Compliance-Zwang vor dem ersten Oracle-Release-Tag; Oracle
steht heute noch bei Slice 0 (Modul-Skeleton), es gibt also noch keine
tatsächliche Auslieferung, die diese Pflicht akut macht. Bis zu einem
Oracle-tragenden Release reicht die Repo-Datei als dokumentierter Nachweis.

## Herkunft

Aufgefallen während Oracle-Slice 0 (Commit `d280fb8e`, 2026-09-05).
