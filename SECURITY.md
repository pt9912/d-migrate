# Sicherheitsrichtlinie

## Schwachstellen melden

**Bitte melden Sie Sicherheitslücken nicht über öffentliche GitHub-Issues,
Diskussionen oder Pull Requests.**

Nutzen Sie stattdessen GitHubs private Schwachstellenmeldung:

1. Zum [Security-Tab](https://github.com/pt9912/d-migrate/security) gehen
2. **Report a vulnerability** anklicken

Das erzeugt ein privates Advisory, das nur die Maintainer sehen.

Bitte geben Sie an:

- Die betroffene Version (`d-migrate --version`) und, falls relevant, den
  Dialekt (PostgreSQL / MySQL / SQLite)
- Eine Beschreibung des Problems und seiner Auswirkung
- Schritte zur Reproduktion — ideal ist ein minimales Schema-YAML oder ein
  CLI-Aufruf
- Eine Gegenmaßnahme, falls Sie eine kennen

Sie erhalten binnen **7 Tagen** eine erste Rückmeldung. Wir halten Sie über den
Fortschritt zur Behebung auf dem Laufenden und fragen gegebenenfalls nach
weiteren Details. Sobald ein Fix veröffentlicht ist, nennen wir Sie im Advisory
— außer Sie möchten anonym bleiben.

## Unterstützte Versionen

Sicherheitsfixes fließen ausschließlich in die aktuelle Release-Linie.
d-migrate hat 1.0.0 noch nicht erreicht; ältere Minor-Versionen erhalten keine
Backports.

| Version   | Unterstützt                    |
| --------- | ------------------------------ |
| 1.0.0-RC  | ✅                             |
| 0.9.x     | ❌ (Upgrade auf 1.0.0-RC nötig) |

## Bedrohungsmodell

Wogegen d-migrate sich verteidigt und wogegen nicht — das hilft Ihnen zu
beurteilen, ob ein beobachtetes Verhalten eine Schwachstelle ist.

d-migrate ist ein Werkzeug, das ein Operator selbst ausführt: eine CLI (und ein
MCP-Server), die gegen Datenbanken laufen, auf die er ohnehin zugriffsberechtigt
ist. **Der Operator ist nicht der Angreifer** — er kann seine eigenen
Verbindungs-Credentials bereits lesen und beliebiges SQL gegen seine eigenen
Datenbanken absetzen. „Der Operator kann sein eigenes Passwort sehen" ist daher
keine Schwachstelle.

**Nicht vertrauenswürdige Eingaben** — hiergegen soll d-migrate sich verteidigen:

- **Die Quell-Datenbank.** Schema und Daten, die aus einer Datenbank gelesen
  werden, sind nicht vertrauenswürdig. Eine Datenbank, deren Inhalt ein
  Angreifer kontrolliert, darf weder die Maschine kompromittieren, auf der
  d-migrate läuft, noch SQL in die Zieldatenbank einschleusen. Die schärfste
  Kante sind hier Identifier wie Tabellen- und Spaltennamen.
- **Eingabedateien.** Schema-YAML, Datendateien (CSV, JSON, Parquet) und
  Konfigurationsdateien können aus nicht vertrauenswürdiger Quelle stammen.
- **MCP-Requests.** Bei laufendem `mcp serve` sind Requests und Tool-Parameter
  nicht vertrauenswürdig. Das Authentifizierungsmodell beschreibt
  [ADR 0009](docs/adr/0009-mcp-resource-server-no-auth-server.md): d-migrate ist
  ein Resource-Server, der extern ausgestellte Token validiert — bewusst kein
  Authorisierungsserver.
- **Credential-Speicherung.** Der Credential-Store
  ([ADR 0034](docs/adr/0034-master-key-architektur-credential-store.md),
  [ADR 0035](docs/adr/0035-credential-provider-scheme-registry.md)) schützt
  Credentials im Ruhezustand gegen einen Angreifer mit Lesezugriff auf die
  Datei — nicht gegen einen Angreifer, der die Sitzung des Operators bereits
  kontrolliert.

**Außerhalb des Bedrohungsmodells:**

- Angriffe, die die Rechte des Operators auf seiner eigenen Maschine
  voraussetzen
- Denial of Service gegen den lokalen CLI-Prozess durch den eigenen Operator
- Schwachstellen in den Zieldatenbanken selbst oder in den JDBC-Treibern (bitte
  dort upstream melden; sagen Sie uns trotzdem Bescheid, falls wir abmildern
  können)
- Bewusste Designentscheidungen, die in `docs/adr/` festgehalten sind. Wenn Sie
  die Sicherheitsbegründung einer ADR für falsch halten, ist das sehr wohl eine
  Meldung wert — nennen Sie die ADR und Ihre Begründung.

## Sicherheitsmaßnahmen

Der Build erzwingt mehrere Sicherheits-Gates, die alle lokal laufen:

- `make semgrep` — statische Analyse mit gepinntem, SHA256-verifiziertem
  Regelsatz, hermetisch ausgeführt (`--network none`)
- `make gates` — die vollständige Gate-Suite

Bewusst akzeptierte Befunde sind inline mit `# nosemgrep: <rule-id>` und einer
Begründung annotiert.
