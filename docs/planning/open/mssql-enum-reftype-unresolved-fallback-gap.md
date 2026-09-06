---
id: mssql-enum-reftype-unresolved-fallback-gap
title: "MssqlNeutralTypeCanonicalizer.resolveRefType stimmt nicht mit enumColumns echtem Fallback ueberein"
status: open
---

# `MssqlNeutralTypeCanonicalizer.resolveRefType` vs. `enumColumn`s echter Fallback

## Befund

`MssqlNeutralTypeCanonicalizer.resolveRefType`
(`MssqlNeutralTypeCanonicalizer.kt:91-106`) liefert `null` — der Typ bleibt
unveraendert (`Enum(refType=...)`) — sobald sich `refType` nicht auf einen
Custom Typ mit `values` aufloesen laesst (Typ fehlt im Schema ganz, oder
zeigt auf einen Custom Typ ohne `values`, z. B. `COMPOSITE`).

`MssqlColumnConstraintHelper.enumColumn`
(`MssqlColumnConstraintHelper.kt:184-195`) kennt dieses "unbekannt
bleiben" aber gar nicht: sie faellt in jedem dieser Faelle letztlich auf
`plainColumn(ctx)` zurueck (ungebundenes `NVARCHAR(MAX)`), genau wie ein
werteloser Enum ohne `refType`. Der Kanonisierer und der reale
Spalten-Renderer divergieren also fuer diese Kombination — ein Schema mit
`Enum(refType="x")`, bei dem `x` im Zielschema fehlt oder ein `COMPOSITE`
ohne `values` ist, wuerde nach `generate` → `reverse` als `Text(...)`
zurueckkommen, waehrend der Kanonisierer `Enum(refType="x")` (unveraendert)
projiziert — eine falsche Drift im Postcompare-Fingerprint.

`SchemaColumnValidationRules.validateRefTypeExists` (E007) prueft nur, ob
`refType` **irgendeinen** Custom Type benennt, nicht dessen `kind` — ein
Schema mit `refType` auf einen `COMPOSITE` besteht die Validierung.

## Herkunft

Beim Bau von Oracle Slice 4a (`OracleNeutralTypeCanonicalizer`,
ADR 0052) am identisch aufgebauten Oracle-Pendant gefunden (unabhaengige
Review, 2026-09-06) und dort direkt behoben — Oracles `resolveRefType`
liefert seitdem nie mehr `null`, sondern bildet `enumColumn`s
drei-zweigigen Fallback (DOMAIN → CLOB; Werte vorhanden → begrenzt;
sonst → ungebunden) vollstaendig nach. MSSQLs bereits ausgelieferte
Fassung hat dieselbe Struktur und denselben Fehler, wurde hier aber
bewusst NICHT mitgeaendert — ein Fix an bereits produktivem,
dialekt-eigenem Code braucht seine eigene Verifikation (Unit- + Live-
Integrationstest), kein Nebenprodukt eines Oracle-Slices.

## Reichweite

Nur die schmale Kombination `refType` gesetzt **und** weder der
aufgeloeste Custom Type noch `type.values` liefern eine Werteliste. Der
haeufige Fall (ENUM-Custom-Type mit `values`, oder DOMAIN) ist korrekt.
Aktuell nicht ueber `schema migrate` erreichbar in dem Sinne, dass MSSQLs
migrate-Pfad diesen Postcompare-Vergleich schon fuehrt — ein echter,
aber wahrscheinlich seltener Fund (ein Schema muesste absichtlich einen
`refType` auf einen `COMPOSITE`- oder fehlenden Custom Typ setzen).

## Moegliche Loesung

`resolveRefType` wie bei Oracle umbauen: nie `null` zurueckgeben, sondern
immer `Enum(values = customType?.values ?: type.values)` (bzw. `Text(null)`
fuer `DOMAIN`) — der Aufrufer rekursiert dann unbedingt weiter statt auf
`?: type` auszuweichen. Braucht einen begleitenden Test, der den
divergenten Fall (COMPOSITE-`refType`, keine `values`) tatsaechlich
gegen ein echtes SQL Server belegt, analog
`MssqlNeutralTypeCanonicalizerIntegrationTest`.
