# Docker-Hub-Repository-Metadaten

Quelle für die Darstellung von [`pt9912/d-migrate`](https://hub.docker.com/r/pt9912/d-migrate)
auf Docker Hub. Docker Hub hat **drei getrennte Felder**; sie werden hier
unterschiedlich gepflegt:

| Feld auf Docker Hub | Quelle | Limit | Gepflegt durch |
|---|---|---|---|
| **Description** (Kurztext unter dem Repo-Namen) | [`description.txt`](description.txt) | 100 Bytes | Tag-Build ([`build.yml`](../../.github/workflows/build.yml), Job `docker`) |
| **Repository overview** (Markdown-Seite) | [`overview.md`](overview.md) | 25.000 Bytes | Tag-Build, derselbe Step |
| **Category** | dieses Dokument (siehe unten) | — | **manuell im Web-UI** |

Die ersten beiden setzt `peter-evans/dockerhub-description` bei jedem Tag-Build.
Die Action hat **keinen** Input für die Kategorie — deshalb steht die
Entscheidung hier als Text, statt still im UI zu leben.

## Category

**Gesetzt: „Databases & storage"** (`databases-and-storage`).

Ergänzend sinnvoll: „Developer tools" (`developer-tools`).

Begründung: d-migrate operiert auf Datenbankschemata und -inhalten (Migration,
Reverse Engineering, Transfer) über PostgreSQL, MySQL und SQLite — die Kategorie
beschreibt die Domäne, nicht die Implementierungssprache. „Languages &
frameworks" wäre falsch (Kotlin ist Implementierungsdetail, kein Nutzen für den
Suchenden).

Docker Hubs Taxonomie ist eine feste Liste (abrufbar unter
`https://hub.docker.com/v2/categories/`); freie Schlagworte gibt es nicht.

## Warum die Description nicht im Workflow steht

Sie stand dort — als String-Literal im `short-description:`-Input. Damit war der
Text nur im YAML auffindbar und nicht als Repo-Inhalt reviewbar, während der
Overview daneben eine eigene Datei hatte. Jetzt liest der Workflow
`description.txt` ein und prüft dabei das 100-Byte-Limit **fail-fast**, statt der
Action das stille Abschneiden zu überlassen.

## Änderungen testen

Beide Dateien wirken erst beim nächsten Tag-Build. Das Rendering lokal
nachvollziehen:

```bash
sed 's/__VERSION__/1.0.0/g' packaging/dockerhub/overview.md | less
wc -c < packaging/dockerhub/description.txt   # muss <= 100 sein (inkl. Zeilenumbruch)
```

`overview.md` trägt den Platzhalter `__VERSION__`, den der Tag-Build durch die
gerade veröffentlichte Version ersetzt — so zeigen die `docker run`-Beispiele
immer einen Tag, den es auf dem Spiegel wirklich gibt. Fehlt der Platzhalter,
bricht der Build-Step ab.
