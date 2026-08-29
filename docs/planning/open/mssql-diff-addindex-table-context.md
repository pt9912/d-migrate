# Tracker: `AddIndex` im MSSQL-Diff sieht die rohe Schematabelle

> **Status:** Tracker / Vorabklärung (29.08.2026)
> **Trigger:** Beim Review von Sub-Slice 8b (Volltext) aufgefallen; der Befund
> ist aber älter als 8b und trifft auch andere Indexarten.
> **Aktivierungsbedingung:** Wird priorisiert → `next/`-Plan; sonst
> Trigger-Watch.

## Befund

Der Diff rendert Indizes auf zwei Wegen, und die beiden sehen **verschiedene
Tabellen**:

- `MssqlDiffTableOps` (Index als Teil eines `CreateTable`) übergibt die
  *effektive* Tabelle — also die, die dialektspezifische Umbauten schon
  berücksichtigt.
- `MssqlDiffObjectOps` (Operation `AddIndex`) übergibt **kein** `tableDef` und
  fällt damit auf die rohe Schematabelle zurück.

Sichtbar wird das an der HASH-Emulation (Sub-Slice 7d): dort wandert eine
berechnete Eimerspalte in jeden eindeutigen Schlüssel. Der `CreateTable`-Weg
weiß das, der `AddIndex`-Weg nicht — er rendert gegen einen Primärschlüssel,
den es in der Datenbank so nicht gibt.

## Warum das trotzdem kein akuter Fehler ist

Beide Wege entstehen aus demselben Plan, und eine HASH-emulierte Tabelle
entsteht heute immer über `CreateTable`. Der `AddIndex`-Weg trifft sie erst,
wenn ein Index **nachträglich** zu einer schon emulierten Tabelle kommt.

Das ist erreichbar, aber kein Standardweg — und deshalb ein Tracker und kein
Blocker.

## Arbeitspaket (Skizze)

Die dialektspezifische Tabellensicht an einer Stelle auflösen, statt sie je
Aufrufweg zu übergeben oder zu vergessen. Beide Wege müssen dieselbe Antwort
bekommen.
