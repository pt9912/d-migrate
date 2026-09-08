---
id: oracle-sequence-bounds-not-round-trippable
title: "Oracle-Sequenzgrenzen round-trippen nicht — und NOMAXVALUE wird beim Lesen stillschweigend verfaelscht"
status: resolved
---

# Sequenzgrenzen: kein Round-Trip, und `NOMAXVALUE` wird verfaelscht

> **Erledigt, Richtung 1 + 2.**
>
> - **Befund 1** (Verkuerzung): die Katalogwerte werden als `BigDecimal`
>   gelesen; was nicht exakt in ein `Long` passt, ist `null` — unbegrenzt.
>   Statt `longValueExact()` auf gut Glueck zu rufen, faengt der Leser den
>   Bereichsfehler ab; `toLong()` haette weiter still verkuerzt.
> - **Befund 2** (Modellfrage): die beiden verbleibenden Default-Grenzen
>   werden ueber die **Richtung** der Sequenz gefaltet — aufsteigend
>   `MINVALUE 1`, absteigend `MAXVALUE -1` → `null`.
>
> **Warum Richtung 2 und nicht 3:** die Kosten, die das Ticket ihr
> zuschreibt — der Verlust der Unterscheidung zwischen „nicht deklariert" und
> „genau auf den Default gesetzt" — sind keine. `ALL_SEQUENCES` fuehrt kein
> Kennzeichen dafuer; Oracle selbst unterscheidet die beiden Faelle nicht,
> also kann kein Leser es. Richtung 3 haette ausserdem nur den
> Post-Compare-Fall geloest und `schema compare` weiter falsch gelassen.
>
> **Gemessen, nicht abgeschrieben** (23c):
>
> ```
> aufsteigend:  min = 1                            max = 9999999999999999999999999999
> absteigend:   min = -999999999999999999999999999 max = -1
> ```
>
> Der absteigende Fall traegt 27 Neunen, nicht 28 — ein weiterer Grund, das
> nicht aus der Dokumentation zu uebernehmen.
>
> `OracleSequenceReverseIntegrationTest` haelt beides fest: die gelesenen
> Grenzen **und** die Katalogwerte, auf denen die Behandlung ruht. Mit
> `toLong()` statt der Bereichspruefung meldet er
> `Expected null but actual was 4477988020393345023L` — genau die Zahl aus
> dem Befund.
>
> Der irrefuehrende Stub (`Long.MAX_VALUE`, ein Wert, den Oracle an dieser
> Stelle nie liefert) ist durch die gemessenen ersetzt.

## Zwei Befunde, derselbe Pfad

### 1. `NOMAXVALUE` wird auf einen willkuerlichen Wert verkuerzt (Fehler)

`OracleMetadataQueries` liest die Katalogspalten ueber
`(this[key] as? Number)?.toLong()`. Oracle liefert `MAX_VALUE` fuer eine
`NOMAXVALUE`-Sequenz als 28-stelligen Wert (`10^28 - 1`), den der Treiber als
`BigDecimal` uebergibt. `BigDecimal.toLong()` verkuerzt ausserhalb des
`Long`-Bereichs **still auf die unteren 64 Bit**:

```
Oracle:                9999999999999999999999999999
nach toLong():         4477988020393345023
```

Eine unbegrenzte Sequenz kommt also als bei ~4,5 Trillionen begrenzt
zurueck. Kein Fehler, keine Notiz.

Warum es bisher niemand sah: `OracleSchemaReaderTest` stubt `Long.MAX_VALUE`
als Katalogwert — ein Wert, den echtes Oracle an dieser Stelle nie liefert.
Kein Test hat je ein reales `NOMAXVALUE` gesehen.

### 2. Auch ohne den Fehler round-trippen die Grenzen nicht (Modellfrage)

`NOMINVALUE` und `NOMAXVALUE` materialisieren in `ALL_SEQUENCES` als
konkrete Zahlen (aufsteigend `1` bzw. der 28-stellige Hoechstwert). Der
Reader liest beide als deklarierte Schranken, ohne sie auf `null`
zurueckzufalten. Ein Modell, das keine Schranken deklariert, erscheint nach
dem Reverse also beschrankt.

Folge fuer den Diff-Pfad (Sub-Slice 5d): jede Oracle-Sequenz zeigt dauerhaft
eine `min_value: null → 1`-Abweichung. `schema compare` erreicht damit nie
„keine Aenderungen", und `migrate` emittiert dasselbe `ALTER SEQUENCE`
immer wieder. Dasselbe gilt fuer `start` — dort ist die Ursache aber eine
andere und in `spec/neutral-model-spec.md` Abschnitt 9.2 bereits
beschrieben (Oracle bewahrt den Startwert nirgends auf).

## Moegliche Loesungsrichtungen

1. **Den Verkuerzungsfehler schliessen**: die Katalogwerte als `BigDecimal`
   lesen und Werte ausserhalb des `Long`-Bereichs als „unbegrenzt"
   (`null`) behandeln statt sie zu verkuerzen. Behebt Befund 1 und
   *einen Teil* von Befund 2 (die Obergrenze).
2. **Die Defaults zurueckfalten**: beim Lesen erkennen, dass `MIN_VALUE = 1`
   auf einer aufsteigenden bzw. `MAX_VALUE = -1` auf einer absteigenden
   Sequenz Oracles Default ist, und `null` ins Modell schreiben. Behebt
   Befund 2 vollstaendig, kostet aber die Unterscheidung zwischen
   „nicht deklariert" und „genau auf den Default gesetzt" — die Oracle
   selbst nicht trifft.
3. **Im Fingerprint/Kanonisierer falten**, analog zur Typ-Kanonisierung aus
   Slice 4a. Loest den Postcompare-Fall, nicht den `schema compare`-Fall.

Befund 1 ist ein klarer Fehler und unabhaengig von der Modellfrage
behebbar. Befund 2 braucht eine Entscheidung, welche der drei Richtungen
gewollt ist.

Aktivierungsbedingung: sobald `schema migrate` fuer Oracle offen ist
(Sub-Slice 5e), wird Befund 2 bei jeder Sequenz sichtbar. Befund 1 wirkt
schon heute im `schema reverse`-Ergebnis.
