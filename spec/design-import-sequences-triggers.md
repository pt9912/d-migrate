# Design Draft: Datenimport mit Sequences, Identity und Triggern

**Status: Draft**

> Dokumenttyp: Design-Dokument / Entscheidungsgrundlage
>
> Dieses Dokument beschreibt den geplanten Umgang mit datenbankeigenen
> Sequenz- und Trigger-Mechanismen beim Datenimport. Es konkretisiert
> [LF-010](./lastenheft-d-migrate.md) für den Milestone 0.4.0, ist aber noch
> kein final freigegebener Implementierungsvertrag.

---

## 1. Ziel

Beim Import von Daten aus JSON, YAML oder CSV in eine Zieldatenbank darf der
Import keine inkonsistenten Folgezustände in datenbankeigenen Objekten
hinterlassen.

Das betrifft insbesondere:

- benannte Sequences
- Identity- und `AUTO_INCREMENT`-Spalten
- datenbankseitige Trigger

Der Import muss nach Abschluss in einem Zustand enden, in dem nachfolgende
normale `INSERT`-Operationen erwartbar funktionieren und keine stillen
Semantikänderungen auftreten.

---

## 2. Nicht-Ziele

Nicht Teil dieses Dokuments:

- Schema-Reverse-Engineering von Triggern und Sequences
- DDL-Generierung für Trigger und Sequences
- Inkrementeller Import
- Checkpoint/Resume-Design im Detail
- Replikations- oder CDC-spezifische Importpfade

---

## 3. Problemstellung

Ein Datenimport mit expliziten Primärschlüsselwerten kann den internen Zustand
einer Datenbank von den importierten Daten entkoppeln:

- eine Sequence kann weiter auf einem zu kleinen Wert stehen
- ein `AUTO_INCREMENT`-Zähler kann nach dem Import einen bereits vergebenen
  Schlüssel erzeugen
- Trigger können während des Imports zusätzliche Seiteneffekte auslösen
- Trigger können fachlich gewollt oder technisch störend sein

Ohne explizite Regeln ist das Verhalten zwischen PostgreSQL, MySQL und SQLite
uneinheitlich und für Anwender schwer vorhersagbar.

---

## 4. Fachlicher Vertrag

Für `d-migrate data import` gelten folgende Grundsätze:

1. Importierte Daten haben Vorrang vor impliziten Generatoren.
2. Wenn explizite Schlüsselwerte importiert werden, muss der Folgezustand von
   Sequence-/Identity-/`AUTO_INCREMENT`-Mechanismen danach konsistent sein.
3. Das Verhalten von Triggern beim Import muss explizit konfigurierbar und
   dokumentiert sein.
4. Wenn ein gewünschter Trigger-Modus auf dem Zieldialekt nicht sicher
   unterstützt wird, darf d-migrate das nicht stillschweigend ignorieren.
   Stattdessen muss der Import mit einer klaren Fehlermeldung abbrechen oder
   nur einen dokumentierten, explizit angeforderten Fallback verwenden.
5. Rollbacks dürfen keine teilweise angepassten Sequence-/Trigger-Zustände
   hinterlassen, soweit der Zieldialekt das innerhalb des gewählten
   Transaktionsmodells zulässt.

---

## 5. Vorgeschlagene Import-Modi

### 5.1 Trigger-Modus

Vorgeschlagene CLI-Option:

```text
--trigger-mode=fire|disable|strict
```

Bedeutung:

- `fire`
  - Trigger laufen normal während des Imports.
  - Das ist der konservative Default, weil die Zieldatenbank ihre normale
    Semantik beibehält.

- `disable`
  - Trigger werden für die Dauer des Imports deaktiviert, falls der Dialekt
    das sicher und explizit unterstützt.
  - Nach Abschluss werden sie wieder aktiviert.
  - Wenn der Dialekt das nicht sicher unterstützt, schlägt der Import fehl.

- `strict`
  - Import wird nur ausgeführt, wenn der aktuelle Plan garantiert, dass keine
    unerwarteten Trigger-Seiteneffekte auftreten.
  - Für 0.4.0 praktisch als Sicherheitsmodus gedacht: Trigger vorhanden und
    Verhalten nicht eindeutig steuerbar -> Abbruch mit Hinweis.

### 5.2 Schlüsselgenerator-Modus

Es braucht keinen separaten User-Modus, aber einen festen internen Vertrag:

- Wenn eine importierte Tabelle explizite Werte für eine Identity-/
  `AUTO_INCREMENT`-/Sequence-getriebene Spalte enthält, wird nach dem Import
  der nächste gültige Wert für diese Tabelle bzw. Sequence bestimmt und
  gesetzt.
- Wenn keine expliziten Werte importiert wurden, erfolgt keine künstliche
  Anhebung.

---

## 6. Dialektspezifische Regeln

### 6.1 PostgreSQL

Erwartetes Verhalten:

- Für klassische Sequences und `SERIAL`/`BIGSERIAL` wird nach dem Import ein
  `setval(...)` auf den höchsten importierten Wert durchgeführt.
- Für Identity-Spalten wird analog der zugrunde liegende Generator auf den
  nächsten gültigen Wert gebracht.
- `trigger-mode=fire` ist nativ unterstützt.
- `trigger-mode=disable` ist nur über einen expliziten, dokumentierten
  Mechanismus zulässig.
- Globale oder breit wirkende Mechanismen wie
  `session_replication_role = replica` sind nur mit Vorsicht zu verwenden, da
  sie Semantik und Sicherheitsannahmen stark verändern.

Offene Designfrage:

- Ob `disable` über `ALTER TABLE ... DISABLE TRIGGER USER` pro Tabelle oder
  über einen Sitzungsmechanismus umgesetzt werden soll.

### 6.2 MySQL

Erwartetes Verhalten:

- Nach Imports mit expliziten Schlüsseln wird der `AUTO_INCREMENT`-Folgewert
  auf mindestens `MAX(id)+1` gebracht.
- `trigger-mode=fire` ist nativ unterstützt.
- Für `trigger-mode=disable` gibt es keinen gleichwertig einfachen und sicheren
  Standardpfad wie bei Constraints; deshalb ist für 0.4.0 eher mit Abbruch
  oder Nicht-Unterstützung zu rechnen.

Offene Designfrage:

- Ob MySQL-Importe mit vorhandenen Triggern standardmäßig erlaubt bleiben
  (`fire`) oder in bestimmten Betriebsmodi einen expliziten Opt-in brauchen.

### 6.3 SQLite

Erwartetes Verhalten:

- Für `INTEGER PRIMARY KEY AUTOINCREMENT` ist der relevante Folgezustand in
  `sqlite_sequence` abgebildet und muss nach Imports mit expliziten IDs
  konsistent sein.
- `trigger-mode=fire` ist nativ unterstützt.
- `trigger-mode=disable` ist wegen begrenzter Steuerungsmöglichkeiten in 0.4.0
  voraussichtlich nicht generisch sicher unterstützbar.

Offene Designfrage:

- Ob SQLite ohne `AUTOINCREMENT` gesondert behandelt werden muss, da die
  Schlüsselvergabe dort nicht vollständig an `sqlite_sequence` hängt.

---

## 7. Fehler- und Rollback-Semantik

### 7.1 Importfehler innerhalb eines Chunks

- Der betroffene Chunk wird zurückgerollt.
- Bereits erfolgreich abgeschlossene frühere Chunks bleiben gemäß
  konfiguriertem Importmodus bestehen, sofern kein global atomarer Modus aktiv
  ist.

### 7.2 Fehler bei Generator-Nachführung

- Kann der Folgezustand einer Sequence/Identity/`AUTO_INCREMENT`-Spalte nicht
  sicher hergestellt werden, gilt der Import als fehlgeschlagen.
- d-migrate darf diesen Schritt nicht still überspringen.

### 7.3 Fehler bei Trigger-Reaktivierung

- Wenn Trigger temporär deaktiviert wurden und die Reaktivierung fehlschlägt,
  ist das ein harter Fehler mit hoher Sichtbarkeit.
- Der Fehlertext muss den betroffenen Trigger oder die betroffene Tabelle
  nennen und auf manuellen Reparaturbedarf hinweisen.

---

## 8. CLI- und Reporting-Auswirkungen

Vorgesehene Erweiterungen für `data import`:

- `--trigger-mode=fire|disable|strict`

Vorgesehene Report-/Log-Informationen:

- Anzahl nachgeführter Sequence-/Identity-/`AUTO_INCREMENT`-Generatoren
- verwendeter Trigger-Modus
- Tabellen, bei denen Trigger explizit deaktiviert/reaktiviert wurden
- Warnungen oder Abbrüche wegen nicht unterstütztem Trigger-Handling

---

## 9. Testmatrix

Mindestens folgende Fälle müssen abgedeckt werden:

### 9.1 PostgreSQL

- Import mit expliziten IDs in `SERIAL`/`BIGSERIAL`-Spalten
- nachfolgendes `INSERT` ohne ID liefert konfliktfreien nächsten Wert
- Trigger-Modus `fire`
- Trigger-Modus `disable` oder klarer dokumentierter Nicht-Support

### 9.2 MySQL

- Import mit expliziten IDs in `AUTO_INCREMENT`-Spalten
- nachfolgendes `INSERT` ohne ID liefert konfliktfreien nächsten Wert
- Trigger-Modus `fire`
- erwarteter Fehler oder dokumentierter Nicht-Support für `disable`

### 9.3 SQLite

- Import mit expliziten IDs in `AUTOINCREMENT`-Tabellen
- nachfolgendes `INSERT` ohne ID liefert konfliktfreien nächsten Wert
- Trigger-Modus `fire`
- erwarteter Fehler oder dokumentierter Nicht-Support für `disable`

### 9.4 Integrität

- Rollback bei Fehlern hinterlässt keine still beschädigten Folgewerte
- Multi-Table-Import mit gemischten Trigger-/Sequence-Situationen
- Round-Trip-Import mit expliziten Primärschlüsseln

---

## 10. Offene Entscheidungen

Vor Implementierungsbeginn zu klären:

1. Soll `fire` der Default bleiben oder `strict`?
2. Soll `disable` in 0.4.0 nur dort unterstützt werden, wo es sauber und
   eng begrenzt implementierbar ist?
3. Soll die Nachführung von Generatoren immer automatisch erfolgen oder
   abschaltbar sein?
4. Wie detailliert soll der Import-Report diese Eingriffe dokumentieren?

---

## 11. Empfehlung

Für 0.4.0 ist die pragmatische Linie:

- Default `--trigger-mode=fire`
- automatische Nachführung von Sequence-/Identity-/`AUTO_INCREMENT`-Zuständen
  nach Imports mit expliziten IDs
- `disable` nur dort implementieren, wo der Dialekt einen klaren und sicheren
  Pfad bietet
- sonst expliziter Fehler statt stiller Teilunterstützung

Das ist technisch beherrschbar und vermeidet die gefährlichste Klasse stiller
Importfehler: erfolgreiche Datenübernahme mit anschließend kaputtem
Folgezustand.
