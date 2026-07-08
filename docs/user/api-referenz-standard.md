# API Referenz Standard

Bei einer **API-Referenz-Doku** geht es weniger um Erklärung und mehr um **exakte, vollständige, maschinennahe Information**. Sie muss so präzise sein, dass ein Entwickler ohne Rückfragen dagegen programmieren kann.

## 1. Ziel und Umfang klar machen

Am Anfang sollte stehen:

* Zweck der API
* Zielgruppe
* unterstützte Version
* Basis-URL
* Protokoll, meist HTTP/HTTPS
* Datenformat, meist JSON
* Authentifizierungsverfahren
* Stabilitätsstatus: stabil, beta, deprecated

Beispiel:

```text
API-Version: v1
Basis-URL: https://api.example.com/v1
Format: JSON
Authentifizierung: Bearer Token
```

## 2. Authentifizierung sauber dokumentieren

Das ist Pflicht. Ohne gute Auth-Doku ist die API praktisch nicht nutzbar.

Dokumentiere:

* Art der Authentifizierung
* Header-Format
* Token-Erzeugung
* Token-Ablauf
* benötigte Rollen oder Scopes
* typische Fehler bei fehlenden Rechten

Beispiel:

```http
Authorization: Bearer <access_token>
```

Auch wichtig: Nie echte Tokens, Passwörter oder produktive Kundendaten in Beispielen verwenden.

## 3. Jeden Endpunkt vollständig beschreiben

Für jeden API-Endpunkt sollten mindestens diese Angaben enthalten sein:

```text
Methode: GET, POST, PUT, PATCH, DELETE
Pfad: /users/{id}
Beschreibung
Berechtigung / Scope
Pfadparameter
Query-Parameter
Request-Body
Response-Body
Statuscodes
Fehlerfälle
Beispiele
```

Gute Struktur:

```markdown
## Benutzer abrufen

GET /users/{id}

Ruft einen Benutzer anhand seiner ID ab.

### Pfadparameter

| Name | Typ    | Pflicht | Beschreibung           |
| ---- | ------ | ------: | ---------------------- |
| id   | string |      ja | Eindeutige Benutzer-ID |

### Antwort

200 OK
```

## 4. Request- und Response-Schemas exakt angeben

Für jedes Feld:

* Name
* Typ
* Pflichtfeld oder optional
* Beschreibung
* erlaubte Werte
* Default-Wert
* Format
* Mindest-/Maximalwerte
* Beispielwert
* Nullable ja/nein
* Deprecated ja/nein

Beispiel:

```json
{
  "id": "usr_123",
  "email": "max@example.com",
  "status": "active"
}
```

Und dazu:

| Feld   | Typ    | Pflicht | Beschreibung                   |
| ------ | ------ | ------: | ------------------------------ |
| id     | string |      ja | Eindeutige Benutzer-ID         |
| email  | string |      ja | E-Mail-Adresse                 |
| status | string |      ja | `active`, `inactive`, `locked` |

## 5. Beispiele liefern, die wirklich funktionieren

Eine API-Referenz ohne Beispiele ist zäh.

Sinnvoll sind Beispiele für:

* cURL
* HTTP roh
* JavaScript/TypeScript
* Java/Kotlin, falls eure Zielgruppe das nutzt
* typische Erfolgsantwort
* typische Fehlerantwort

Beispiel:

```bash
curl -X GET "https://api.example.com/v1/users/usr_123" \
  -H "Authorization: Bearer <access_token>"
```

## 6. Statuscodes und Fehlerformat standardisieren

Dokumentiere nicht nur `200 OK`.

Wichtig sind:

* `400 Bad Request`
* `401 Unauthorized`
* `403 Forbidden`
* `404 Not Found`
* `409 Conflict`
* `422 Unprocessable Entity`
* `429 Too Many Requests`
* `500 Internal Server Error`

Lege ein einheitliches Fehlerformat fest:

```json
{
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "User was not found.",
    "details": []
  }
}
```

Dann pro Endpunkt erklären, welche Fehler realistisch auftreten.

## 7. Pagination, Sorting und Filtering beschreiben

Wenn Listen-Endpunkte existieren, muss klar sein:

* Wie wird paginiert?
* Gibt es `limit` und `offset`?
* Cursor-basiert oder seitenbasiert?
* Maximalwerte?
* Standardsortierung?
* Filter-Syntax?
* Verhalten bei leeren Ergebnissen?

Beispiel:

```http
GET /users?limit=50&cursor=abc123&sort=createdAt:desc
```

## 8. Versionierung und Breaking Changes erklären

Sehr wichtig für produktive APIs.

Dokumentiere:

* aktuelle API-Version
* Versionierung im Pfad, Header oder Media Type
* Umgang mit Breaking Changes
* Deprecation-Fristen
* Migrationshinweise
* Changelog-Link

Beispiel:

```text
/v1/users
/v2/users
```

Ohne Versionierungsregeln wird die API mit der Zeit chaotisch.

## 9. Rate Limits und Quotas angeben

Entwickler müssen wissen, wann sie gedrosselt werden.

Dokumentiere:

* Requests pro Minute/Stunde/Tag
* Limit pro Nutzer, Tenant oder Token
* Header für verbleibende Requests
* Verhalten bei Überschreitung
* Retry-After-Header

Beispiel:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
```

## 10. Nebenwirkungen und Idempotenz erklären

Gerade bei `POST`, `PUT`, `PATCH`, `DELETE` wichtig:

* Ist der Endpunkt idempotent?
* Wird wirklich gelöscht oder nur deaktiviert?
* Gibt es asynchrone Verarbeitung?
* Gibt es Webhooks?
* Können doppelte Requests entstehen?
* Unterstützt die API Idempotency Keys?

Beispiel:

```http
Idempotency-Key: 9b3f0d2e-7c1a-4f3c-a2e7
```

## 11. Sicherheit und Datenschutz beachten

Dokumentiere:

* keine sensiblen Daten in Logs
* TLS-Pflicht
* benötigte Scopes
* Mandantentrennung
* PII-Felder
* Datenaufbewahrung
* Löschverhalten
* Audit-Logging, falls vorhanden

Auch hier gilt: Keine internen Servernamen, produktiven IDs, echten Kundendaten oder Secrets in Beispielen.

## 12. OpenAPI/Swagger verwenden

Für REST-APIs ist eine **OpenAPI-Spezifikation** praktisch Pflicht.

Damit bekommst du:

* maschinenlesbare API-Beschreibung
* Swagger UI / Redoc
* Codegenerierung
* Contract Testing
* bessere Reviews
* konsistente Schemas

Empfehlung:

```text
openapi.yaml
```

Die handgeschriebene Doku sollte möglichst aus oder neben der OpenAPI-Spezifikation entstehen, nicht komplett getrennt davon. Sonst laufen Code und Doku schnell auseinander.

## 13. Konsistenz erzwingen

Eine gute API-Referenz ist langweilig konsistent.

Achte auf:

* gleiche Begriffe für gleiche Konzepte
* einheitliche Fehlercodes
* einheitliche Datumsformate
* einheitliche ID-Formate
* einheitliche Beispiele
* einheitliche Reihenfolge der Abschnitte
* einheitliche Schreibweise von Headern und Parametern

Beispiel:

```text
createdAt: ISO 8601, UTC
```

Nicht mal `created_at`, mal `createdAt`, mal `created`.

## 14. Testbarkeit sicherstellen

Gute API-Doku ist prüfbar.

Sinnvoll:

* Beispiel-Requests regelmäßig testen
* OpenAPI gegen Implementierung validieren
* Contract Tests
* Mock-Server
* Postman-/Bruno-/Insomnia-Collection
* CI-Prüfung für OpenAPI-Dateien
* Linting mit Spectral oder ähnlichen Tools

## 15. Empfohlene Struktur

```markdown
# API-Referenz: <Produktname>

Version: <API-Version>  
Stand: <Datum>

## 1. Überblick

### Zweck der API
### Basis-URL
### Datenformat
### Authentifizierung
### Versionierung

## 2. Allgemeine Konzepte

### IDs
### Zeitstempel
### Pagination
### Sorting
### Filtering
### Fehlerformat
### Rate Limits
### Idempotenz

## 3. Authentifizierung

### Token erhalten
### Token verwenden
### Scopes und Rollen

## 4. Ressourcen

### Benutzer
#### Benutzer auflisten
#### Benutzer abrufen
#### Benutzer erstellen
#### Benutzer ändern
#### Benutzer löschen

### Projekte
#### Projekt auflisten
#### Projekt abrufen
#### Projekt erstellen
#### Projekt ändern
#### Projekt löschen

## 5. Webhooks

## 6. Fehlercodes

## 7. Changelog

## 8. Migration
```

## Kurz gesagt

Eine gute API-Referenz muss sein:

**vollständig, eindeutig, versioniert, beispielreich, sicher, konsistent und maschinenlesbar.**

Der häufigste Fehler ist eine Doku, die „ungefähr“ beschreibt, was die API macht. Für APIs reicht ungefähr nicht. Entweder ein Entwickler kann daraus korrekt implementieren, oder die Referenz ist nicht gut genug.
