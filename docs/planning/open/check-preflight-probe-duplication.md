---
id: check-preflight-probe-duplication
title: "Die CHECK-Preflight-Sonde steht fuenfmal zeichengleich im Repo"
status: open
---

# Fuenf identische CHECK-Preflight-Sonden

## Befund

`PostgresCheckPreflightProbe`, `MysqlCheckPreflightProbe`,
`SqliteCheckPreflightProbe`, `MssqlCheckPreflightProbe` und (seit
Oracle-Sub-Slice 5e-2) `OracleCheckPreflightProbe` tragen dieselbe
`probe`/`countViolations`-Schleife. Zwischen zwei Fassungen unterscheiden
sich genau zwei Dinge: der `DatabaseDialect`-Wert (zweimal) und der
Identifier-Quoter.

Die eigentliche Dialektabhaengigkeit ist bereits herausgezogen — die
Abfrage baut der geteilte `CheckPreflightPlanner`. Was blieb, ist die
Ausfuehrungs- und Fehlerbehandlungsschleife.

## Warum das zaehlt

Jede Aenderung an der Fehlerbehandlung (z. B. ein weiterer
`CheckPreflightStatus`, ein Timeout, eine Ausnahme, die nicht
`SQLException` ist) muss heute fuenfmal nachgezogen werden. Vier
Fassungen mitzuziehen und eine zu vergessen faellt in keinem Test auf,
weil jede Fassung ihren eigenen Dialekt-Test hat.

## Moegliche Loesungsrichtung

Einen gemeinsamen Helfer in `driver-common` (dort liegt bereits
`AbstractDdlGenerator`), der `dialect` und `identifierQuoter` entgegen
nimmt; die fuenf Objekte bleiben als duenne Einstiegspunkte bestehen, weil
die CLI sie namentlich verdrahtet.

## Herkunft

Aufgefallen im Review des Oracle-Sub-Slice 5e-2, der die fuenfte Kopie
angelegt hat. Bewusst dort nicht behoben: ein Umbau ueber fuenf
Treibermodule gehoert nicht in einen Dialekt-Slice.
