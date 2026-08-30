# Benutzerhandbuch Standard

Beim Erstellen eines Benutzerhandbuchs für Software zählt vor allem eines: **Der Nutzer muss sein Ziel erreichen, ohne die Software oder den Entwickler verstehen zu müssen.** Ein gutes Handbuch ist kein Ablageort für Feature-Beschreibungen, sondern eine Arbeitsanleitung.

## 1. Zielgruppe klären

Vor dem Schreiben musst du wissen, für wen das Handbuch ist:

* Endanwender ohne technisches Vorwissen
* Administratoren
* Power-User
* Support-Mitarbeiter
* Entwickler oder Integratoren

Davon hängt ab, welche Begriffe du erklärst, wie detailliert du wirst und welche Aufgaben du beschreibst.

Schlecht:

> Der Benutzer kann über die Konfigurationsmaske die Parameter persistieren.

Besser:

> Klicken Sie auf **Speichern**, um die Einstellungen dauerhaft zu übernehmen.

## 2. Aufgaben statt Funktionen beschreiben

Viele Handbücher sind schlecht, weil sie die Oberfläche der Reihe nach erklären:

> Menüpunkt Datei
> Menüpunkt Bearbeiten
> Menüpunkt Einstellungen

Besser ist eine Struktur nach Nutzerzielen:

* Erste Anmeldung
* Passwort ändern
* Neues Projekt anlegen
* Daten importieren
* Bericht exportieren
* Fehler beheben
* Benutzer verwalten

Der Nutzer fragt nicht: „Was macht dieser Button?“, sondern: „Wie erledige ich meine Aufgabe?“

## 3. Klare Struktur verwenden

Ein typisches Benutzerhandbuch sollte enthalten:

1. **Einleitung**

   * Zweck der Software
   * Zielgruppe des Handbuchs
   * Voraussetzungen

2. **Installation oder Zugriff**

   * Systemanforderungen
   * Installation
   * Login
   * Rollen und Rechte

3. **Erste Schritte**

   * Schneller Einstieg
   * Beispielablauf
   * Wichtigste Bedienkonzepte

4. **Bedienungsanleitung nach Aufgaben**

   * Schritt-für-Schritt-Anleitungen
   * Screenshots
   * Hinweise
   * Ergebnis der Aktion

5. **Konfiguration**

   * Einstellungen
   * Benutzerrollen
   * Schnittstellen
   * Import/Export

6. **Fehlerbehebung**

   * Häufige Probleme
   * Fehlermeldungen
   * Ursachen
   * Lösungen

7. **FAQ**

   * Kurze Antworten auf typische Fragen

8. **Glossar**

   * Fachbegriffe
   * Abkürzungen
   * Produktspezifische Begriffe

9. **Anhang**

   * Tastenkürzel
   * Formate
   * Grenzwerte
   * Kontakt zum Support

## 4. Einheitliche Sprache nutzen

Sprich den Nutzer direkt und eindeutig an.

Gut:

> Klicken Sie auf **Neues Projekt**.

Nicht gut:

> Man kann dann gegebenenfalls ein neues Projekt erstellen.

Wichtig:

* Ein Begriff = immer derselbe Begriff
* Button-Namen exakt wie in der Software schreiben
* Keine unnötigen Fremdwörter
* Keine langen Schachtelsätze
* Keine Entwicklerbegriffe, wenn sie für Nutzer nicht nötig sind

## 5. Schritt-für-Schritt-Anleitungen sauber schreiben

Eine gute Anleitung hat immer:

* Ausgangssituation
* Ziel
* Voraussetzungen
* nummerierte Schritte
* erwartetes Ergebnis
* Hinweise auf mögliche Fehler

Beispiel:

```markdown
## Neues Projekt anlegen

### Voraussetzung

Sie sind angemeldet und haben die Rolle **Projektmanager**.

### Vorgehen

1. Öffnen Sie im Hauptmenü den Bereich **Projekte**.
2. Klicken Sie auf **Neues Projekt**.
3. Geben Sie einen Projektnamen ein.
4. Wählen Sie einen Projektstatus aus.
5. Klicken Sie auf **Speichern**.

### Ergebnis

Das neue Projekt wird angelegt und erscheint in der Projektübersicht.
```

## 6. Screenshots sinnvoll einsetzen

Screenshots helfen, aber sie dürfen nicht die Anleitung ersetzen.

Achte auf:

* aktuelle Oberfläche
* keine echten Kundendaten
* keine Passwörter, Tokens, E-Mail-Adressen oder internen URLs
* Markierungen nur dort, wo sie helfen
* einheitliche Bildgröße
* gute Lesbarkeit
* Screenshots nach jedem UI-Update prüfen

Nicht jeder Klick braucht einen Screenshot. Zu viele Bilder machen das Handbuch schwer wartbar.

## 7. Rollen und Rechte erklären

Gerade bei Business-Software ist wichtig:

* Wer darf was sehen?
* Wer darf was bearbeiten?
* Warum fehlt ein Button?
* Welche Rolle braucht man für welche Aufgabe?

Beispiel:

```markdown
Hinweis: Die Schaltfläche **Benutzer löschen** wird nur angezeigt, wenn Sie die Rolle **Administrator** besitzen.
```

## 8. Fehlermeldungen und Problemfälle dokumentieren

Ein gutes Handbuch behandelt nicht nur den Idealfall.

Dokumentiere:

* häufige Fehlermeldungen
* ungültige Eingaben
* fehlende Rechte
* Netzwerkprobleme
* Importfehler
* Konflikte bei gleichzeitiger Bearbeitung
* abgelaufene Sitzungen

Gute Struktur:

```markdown
## Fehler: "Datei konnte nicht importiert werden"

### Ursache

Die Datei hat nicht das erwartete CSV-Format.

### Lösung

1. Öffnen Sie die Datei in einem Texteditor.
2. Prüfen Sie, ob die erste Zeile die Spaltenüberschriften enthält.
3. Speichern Sie die Datei erneut im UTF-8-Format.
4. Starten Sie den Import erneut.
```

## 9. Aktualität sicherstellen

Ein Handbuch veraltet schnell. Der Schutz dagegen ist nicht ein Stempel, sondern
die Regel, dass im Handbuch **nur der Ist-Zustand** steht: keine Chronik, keine
Forensik, kein Ausblick. Ändert sich, was ein Anwender tun oder erwarten kann,
ändert sich der beschreibende Text — nicht ein Changelog-Eintrag daneben.

Ein Versionsstempel im Dokument leistet das Gegenteil. Er behauptet Zustand und
muss dafür gepflegt werden; bleibt die Pflege aus, steht dort eine Falschaussage
statt einer fehlenden. In diesem Repository trugen sechs Nutzerdokumente einen
solchen Stempel, und vier nannten die falsche Software-Version.

Was ein Handbuch stattdessen führt:

* **Gültigkeitsbereich** — für welche Datenbanken und Schnittstellen es gilt
* **Vertrags-Versionen**, wo sie Teil der Schnittstelle sind (etwa
  `Contract-Version: v1`) — die beschreiben den Ist-Zustand, nicht seinen Verlauf

Die Versionsgeschichte steht in der Versionsverwaltung und im Changelog des
Projekts, nicht im Handbuch.

## 10. Rechtliche und organisatorische Punkte beachten

Je nach Software können wichtig sein:

* Datenschutz
* Umgang mit personenbezogenen Daten
* Lizenzhinweise
* Sicherheitswarnungen
* Barrierefreiheit
* Aufbewahrungsfristen
* Exportkontrolle
* Haftungsausschlüsse
* interne Compliance-Vorgaben

Besonders bei Admin-Handbüchern solltest du keine sensiblen Details veröffentlichen, die Angreifern helfen könnten, etwa interne Servernamen, Tokens, genaue Sicherheitskonfigurationen oder Produktionszugänge.

## 11. Barrierefreiheit berücksichtigen

Ein Handbuch sollte auch ohne perfekte Sicht, Maus oder Spezialwissen nutzbar sein.

Achte auf:

* klare Überschriftenstruktur
* Alternativtexte für Bilder
* ausreichende Kontraste
* keine Informationen nur über Farbe vermitteln
* Tabellen sparsam und sauber verwenden
* PDF mit Lesezeichen und sinnvoller Struktur exportieren
* Suchfunktion nutzbar machen

## 12. Wartbarkeit einplanen

Das wird oft unterschätzt. Ein Benutzerhandbuch ist kein einmaliges Dokument, sondern Teil des Produkts.

Praktisch sinnvoll:

* Handbuch in Markdown, AsciiDoc oder einem Docs-System pflegen
* Screenshots automatisiert oder halbautomatisiert aktualisieren
* Review-Prozess mit Releases verbinden
* Verantwortliche Person oder Team festlegen
* veraltete Abschnitte markieren oder entfernen

## 13. Qualität prüfen

Vor Veröffentlichung sollte geprüft werden:

* Sind alle beschriebenen Funktionen in der Software vorhanden?
* Stimmen Button-Namen, Menüs und Screenshots?
* Kann ein neuer Nutzer die Schritte ausführen?
* Sind Fehlermeldungen korrekt beschrieben?
* Gibt es tote Links?
* Sind sensible Daten entfernt?
* Ist die Sprache einheitlich?
* Gibt es eine PDF- oder Online-Version?
* Funktioniert die Suche?

Der beste Test: Gib das Handbuch jemandem, der die Software nicht kennt. Wenn diese Person die Aufgabe ohne Rückfragen erledigt, ist die Anleitung gut.

## 14. Empfohlene Vorlage

```markdown
# Benutzerhandbuch: <Produktname>

Gültigkeitsbereich: <wofür dieses Handbuch gilt>

## 1. Einleitung

### Zweck der Software
### Zielgruppe
### Voraussetzungen

## 2. Erste Schritte

### Anmeldung
### Überblick über die Oberfläche
### Grundlegende Bedienung

## 3. Aufgaben ausführen

### Aufgabe 1
#### Voraussetzung
#### Vorgehen
#### Ergebnis
#### Hinweise

### Aufgabe 2
#### Voraussetzung
#### Vorgehen
#### Ergebnis
#### Hinweise

## 4. Einstellungen

## 5. Rollen und Rechte

## 6. Import und Export

## 7. Fehlerbehebung

## 8. FAQ

## 9. Glossar

## 10. Support und Kontakt

```

## Kurz gesagt

Ein gutes Benutzerhandbuch ist:

**zielgruppenorientiert, aufgabenbasiert, aktuell, eindeutig, suchbar, bebildert, barrierearm und wartbar.**

Der häufigste Fehler ist, zu sehr aus Entwicklersicht zu schreiben. Schreibe nicht, was die Software kann. Schreibe, wie der Nutzer damit seine Arbeit erledigt.
