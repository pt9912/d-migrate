---
id: oracle-uppercase-folding-unquoted-identifier-references
title: "Rohtext-Passthrough (CHECK-Ausdrücke, View-/Routinen-Bodies) bricht bei Oracle an unquoted Bezeichnern"
status: resolved
---

# Rohtext-Passthrough bricht bei Oracle an unquoted Bezeichnern

> **Erledigt.** `OracleIdentifierRequoter` setzt in CHECK-Ausdruecken und
> Sichten-Ruempfen die Bezeichner in Anfuehrungszeichen, die das Schema kennt
> — im Generate- **und** im Diff-Pfad, inklusive materialisierter Sichten.
>
> **Zu den drei Fragen des Tickets:**
>
> - *Ob ueberhaupt geparst werden kann.* Es braucht keinen Parser. Angefasst
>   wird nur ein Wort, das zeichenweise einem Tabellen- oder Spaltennamen des
>   Schemas entspricht; Zeichenketten, bereits gequotete Bezeichner,
>   Kommentare, Funktionsaufrufe (Wort vor `(`) und Schluesselwoerter bleiben
>   unveraendert. Was nicht sicher erkennbar ist, bleibt stehen.
> - *Ob eine Warnung genuegt.* Nein — sie haette den Anwender vor ein Skript
>   gestellt, das nicht laeuft, ohne ihm zu sagen, wie es laufen wuerde.
> - *Ob eine schema-weite Lowercase-Policy die Wurzel trifft.* Sie traefe sie,
>   traegt aber eigene Folgen (reservierte Woerter, Sonderzeichen) und aendert
>   jeden erzeugten Bezeichner. Der Requoter kommt ohne das aus.
>
> **Nebenbefund, mitbehoben:** `AbstractDdlGenerator.generateViews` bekam das
> Schema gar nicht — ein Sichten-Rumpf wurde also gerendert, ohne dass der
> Renderer die Bezeichner kannte, auf die er verweist. Die Signatur traegt es
> jetzt; die vier uebrigen Dialekte lassen es liegen.
>
> **Die Goldens waren wirklich kaputt**, nicht nur theoretisch:
> `OracleRawTextIdentifierIntegrationTest` erzeugt dieselbe Form und wendet
> sie gegen ein echtes Oracle an — mit dem Quoting laeuft sie, ohne scheitert
> genau diese Anweisung an `ORA-00904`. Die betroffenen Goldens
> (`e-commerce.oracle.sql`, `full-featured.oracle.sql` und die beiden
> pre-data-Faelle) sind neu erzeugt; der Unterschied besteht ausschliesslich
> aus den vier gequoteten Stellen.

## Befund

`generateConstraintClause` rendert `ConstraintDefinition.expression`
unverändert in die `CHECK (...)`-Klausel — dieselbe Architektur wie bei
PostgreSQL/MySQL/SQLite/MSSQL
(`MssqlColumnConstraintHelper.kt:466-467` macht es identisch). View-Bodies
(`ViewDefinition.query`) passieren ebenso unverändert, außer den in
`ViewQueryTransformer` behandelten Portabilitäts-/Rewrite-Regeln — die
Kommentarzeile in `OracleDdlGenerator.generateView` sagt es explizit:
"d-migrate does not translate view bodies between dialects".

Alle vier bisherigen Dialekte quoten entweder gar nicht (PostgreSQL faltet
unquoted auf lowercase, wie das neutrale Modell es meist erwartet) oder sind
per Default-Collation case-insensitiv (MSSQL) — ein unquoted
Bezeichnerverweis in einem CHECK-Ausdruck oder View-Body "funktioniert"
deshalb zufällig, obwohl die zugehörige Spalte/Tabelle selbst quoted-lowercase
angelegt wird.

Oracle faltet unquoted Bezeichner auf GROSSSCHREIBUNG — die einzige der fünf
Ziel-Engines mit dieser Faltrichtung. Sichtbar in den Slice-2-Goldens:

- `e-commerce.oracle.sql`/`full-featured.oracle.sql`: `CHECK (total_amount >=
  0)` referenziert unquoted, während die Spalte als `"total_amount"` angelegt
  wird → `ORA-00904: invalid identifier` gegen eine echte Instanz.
- `full-featured.oracle.sql`: View-Body referenziert unquoted `orders`/`status`
  gegen die quoted-lowercase Tabelle `"orders"` → `ORA-00942`.

## Reichweite

Betrifft jeden rohen SQL-Ausdruck, den d-migrate aus dem neutralen Modell
unverändert durchreicht: CHECK-Ausdrücke, View-Bodies, (sobald gebaut)
Routinen-/Trigger-Bodies. Nur Oracle als Ziel ist betroffen — die anderen
vier Dialekte tolerieren unquoted Referenzen zufällig, nicht weil sie korrekt
behandelt würden.

## Was der Schnitt klären muss

- **Ob überhaupt geparst werden kann.** Ein CHECK-Ausdruck oder View-Body ist
  beliebiger SQL-Text; um darin Spalten-/Tabellenreferenzen zu quoten, bräuchte
  es einen (Teil-)Parser, keinen Tokenizer wie `ViewQueryTokenizer`.
- **Ob eine Warnung genügt.** Alternative zum Parsen: bei generierten
  Oracle-CHECK-Ausdrücken/View-Bodies grundsätzlich eine Notiz ausgeben, dass
  unquoted Bezeichnerreferenzen gegen die quoted-lowercase Objektnamen
  fehlschlagen können — Aufwand vs. Nutzen eines echten Fixes klären.
- **Ob eine schema-weite Lowercase-Policy für Oracle die Wurzel trifft.**
  Wenn Oracle grundsätzlich nur unquoted (und damit GROSSSCHREIBUNG)
  identifiziert statt durchgängig zu quoten, entfällt das Problem — trägt
  aber eigene Folgen (Kollisionen mit reservierten Wörtern, Bezeichnern mit
  Sonderzeichen).

## Herkunft

Unabhängiges Review von Oracle Slice 2 (`schema generate --target oracle`),
2026-09-05. Der `invoice_seq.NEXTVAL`-Fall (fehlendes Quoting an einer
Oracle-eigenen Rendering-Stelle, nicht geteiltem Passthrough) wurde im selben
Review gefunden und direkt in `OracleTypeMapper.kt` behoben — dieser Eintrag
deckt nur die verbleibenden, architektonisch tieferliegenden Fälle.
