---
id: neutral-default-function-fidelity
title: "Fremde Funktions-Defaults verlieren beim Round-Trip ihre Funktions-Natur"
status: open
---

# Fremde Funktions-Defaults verlieren ihre Funktions-Natur

## Befund

`SchemaNodeStructureParsers.parseScalarDefault` kennt genau vier Funktionen —
`current_timestamp`, `current_date`, `current_time`, `gen_uuid` — plus die Form
`nextval(...)`. Alles andere wird zum **String-Literal**:

```kotlin
text in KNOWN_FUNCTION_DEFAULTS -> DefaultValue.FunctionCall(text)
text.matches(Regex("""^nextval\(.+\)$""")) -> DefaultValue.FunctionCall(text)
else -> DefaultValue.StringLiteral(text)
```

Ein reverse-gelesener Default wie `newid()`, `uuid_generate_v4()` oder
`now() - interval '1 day'` kommt damit als Zeichenkette zurück. Beim
Zurückschreiben rendert der Generator ihn gequotet — aus einem Funktionsaufruf
wird ein Text, der zufällig wie einer aussieht.

## Reichweite

**Dialektübergreifend**, nicht MSSQL-spezifisch — aufgefallen ist es am
MSSQL-Leg. Betroffen ist jeder Default, den der Reverse als Text liefert und
der nicht in der Liste steht.

Die Dialekt-Reverse-Leser normalisieren teilweise vorher (MSSQL faltet etwa
`getdate()`/`sysdatetime()` auf `current_timestamp`), was den Schaden
eingrenzt, aber nicht behebt: was sie nicht kennen, geht durch den
`else`-Zweig.

## Was der Schnitt klären muss

- **Woran erkennt man einen Funktionsaufruf?** Ein Muster `name(...)` ist
  billig, aber ein String-Default `'foo(bar)'` sähe genauso aus. Die
  Objektform (`default: { function: ... }`) ist eindeutig — ob der skalare Weg
  überhaupt raten soll, ist die eigentliche Frage.
- **Was passiert beim Ziel, das die Funktion nicht kennt?** Ein
  `newid()`-Default auf PostgreSQL ist kein Text, sondern ein Fehler. Heute
  entsteht daraus stillschweigend ein Literal; ehrlicher wäre eine Meldung.

## Herkunft

Aus den offenen Punkten des MSSQL-Scoping-Plans, dort ohne Slice-Zuordnung.
