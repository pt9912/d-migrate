# Beispiel: Stored Procedure Migration von PostgreSQL zu MySQL

**Use Case**: UC-04 - KI-gestützte Stored Procedure Migration
**Szenario**: Ein Entwickler möchte eine Stored Procedure von PostgreSQL nach MySQL migrieren

Status: Draft

---

## Übersicht

Dieses Dokument illustriert die Anforderung **LF-017** (KI-gestützte Transformation von Stored Procedures) anhand eines konkreten Beispiels. Es zeigt, wie eine Stored Procedure mit Hilfe eines Sprachmodells wie Grok, Claude, GPT-4 oder einem lokalen Ollama-Modell zwischen verschiedenen Datenbanksystemen migriert werden kann.

---

## Ausgangssituation

Ein Unternehmen betreibt eine PostgreSQL-Datenbank mit einer Stored Procedure, die die Gesamtsumme einer Bestellung berechnet. Im Rahmen einer System-Migration soll die Anwendung auf MySQL umgestellt werden. Die Stored Procedure muss entsprechend migriert werden.

---

## Schritt 1: Extraktion der Stored Procedure

Die Stored Procedure wird aus der PostgreSQL-Datenbank exportiert (gemäß **LF-004**).

### Quell-Code (PostgreSQL)

```sql
CREATE OR REPLACE FUNCTION calculate_order_total(order_id INT)
RETURNS DECIMAL(10,2) AS $$
DECLARE
    total DECIMAL(10,2) := 0;
BEGIN
    SELECT SUM(item_price * quantity) INTO total
    FROM order_items
    WHERE order_id = order_id;
    RETURN total;
END;
$$ LANGUAGE plpgsql;
```

### d-migrate Kommando

```bash
d-migrate export schema \
  --source postgres://user:pass@localhost/mydb \
  --format yaml \
  --include-procedures \
  --output schema-export.yaml
```

**Ergebnis**: Die Stored Procedure wird zusammen mit dem Schema in ein neutrales Format exportiert.

---

## Schritt 2: KI-gestützte Transformation

Das System (oder ein KI-Modell wie Grok, Claude, GPT-4, Ollama) analysiert die Stored Procedure und transformiert sie in ein neutrales, textbasiertes Format (z.B. Markdown gemäß **LF-017**).

### Abstraktes Zwischenformat (Markdown)

```markdown
# Stored Procedure: calculate_order_total

## Metadaten
- **Quellsystem**: PostgreSQL (PL/pgSQL)
- **Zielsystem**: MySQL (SQL/Procedural)
- **Transformiert am**: 2025-10-22
- **KI-Modell**: Grok-2 (oder alternatives Modell)

## Signatur
- **Name**: `calculate_order_total`
- **Parameter**:
  - `order_id` (Integer, IN)
- **Rückgabetyp**: DECIMAL(10,2)

## Beschreibung
Diese Funktion berechnet die Gesamtsumme aller Artikel einer Bestellung, indem sie die Produkte aus `item_price` und `quantity` für alle Zeilen in der Tabelle `order_items` mit der angegebenen `order_id` summiert.

## Logik

### Schritt 1: Variable initialisieren
- Deklariere Variable `total` vom Typ DECIMAL(10,2)
- Initialisiere `total` mit Wert `0`

### Schritt 2: Summe berechnen
- Führe SQL-Query aus:
  ```sql
  SELECT SUM(item_price * quantity)
  FROM order_items
  WHERE order_id = <input_parameter>
  ```
- Weise das Ergebnis der Variable `total` zu

### Schritt 3: Rückgabe
- Gib den Wert von `total` zurück

## Abhängigkeiten
- **Tabelle**: `order_items`
  - **Spalten**: `order_id` (INT), `item_price` (DECIMAL), `quantity` (INT)

## Besonderheiten
- Bei keinen Treffern in `order_items` wird `NULL` zurückgegeben (SQL-Standardverhalten von SUM)
- Parameter-Name `order_id` kollidiert mit Spaltenname `order_id` → Muss im Zielsystem disambiguiert werden

## Hinweise für Transformation
- PostgreSQL: Verwendung von `LANGUAGE plpgsql` mit `DECLARE`-Block
- MySQL: Verwendung von `DELIMITER //` und `BEGIN...END`-Block
- MySQL: Keine `RETURNS`-Klausel bei Procedures, stattdessen `OUT`-Parameter oder `SELECT`-Statement
```

### d-migrate Kommando

```bash
d-migrate transform procedure \
  --source schema-export.yaml \
  --procedure calculate_order_total \
  --ai-backend ollama \
  --model llama3.1:70b \
  --output-format markdown \
  --output procedure-spec.md
```

**Konfiguration** (`.d-migrate.yaml`):

```yaml
ai:
  default_backend: ollama  # Alternativen: openai, anthropic, xai, google
  backends:
    ollama:
      base_url: http://localhost:11434
      model: llama3.1:70b
      timeout: 30
    openai:
      api_key: ${OPENAI_API_KEY}
      model: gpt-4o
    anthropic:
      api_key: ${ANTHROPIC_API_KEY}
      model: claude-3-5-sonnet-20241022
    xai:
      api_key: ${XAI_API_KEY}
      model: grok-2
  privacy:
    prefer_local: true  # Bevorzuge lokale Modelle für sensible Daten
    allow_external: false  # Explizit externe APIs erlauben
```

---

## Schritt 3: Generierung für MySQL

Das System generiert aus dem neutralen Format eine MySQL-kompatible Stored Procedure.

### Ziel-Code (MySQL)

```sql
DELIMITER //

CREATE PROCEDURE calculate_order_total(IN p_order_id INT)
BEGIN
    DECLARE v_total DECIMAL(10,2) DEFAULT 0;

    SELECT SUM(item_price * quantity) INTO v_total
    FROM order_items
    WHERE order_id = p_order_id;

    -- MySQL: Rückgabe via SELECT
    SELECT v_total AS order_total;
END //

DELIMITER ;
```

### d-migrate Kommando

```bash
d-migrate generate procedure \
  --source procedure-spec.md \
  --target mysql \
  --mysql-version 8.0 \
  --output calculate_order_total.sql
```

**Ergebnis**: Eine MySQL-kompatible Stored Procedure wird generiert, die:
- Parameter mit Präfix `p_` benennt (Best Practice)
- Lokale Variablen mit Präfix `v_` benennt
- Kollision zwischen Parameter und Spaltenname vermeidet
- MySQL-Syntax verwendet (`DELIMITER`, `BEGIN...END`)
- Rückgabe via `SELECT`-Statement realisiert (MySQL-Standard)

---

## Schritt 4: Validierung

Das System validiert die generierte Procedure gegen das Zielschema (**LF-002**) und stellt sicher, dass die Logik konsistent bleibt.

### Syntaktische Validierung

```bash
d-migrate validate procedure \
  --source calculate_order_total.sql \
  --target mysql://user:pass@localhost/mydb \
  --check-syntax
```

**Prüfungen**:
- ✅ SQL-Syntax korrekt für MySQL 8.0
- ✅ Parameter-Datentyp kompatibel
- ✅ Rückgabetyp kompatibel
- ✅ Verwendete Tabellen und Spalten existieren im Zielschema

### Semantische Validierung (Optional)

```bash
d-migrate validate procedure \
  --source-db postgres://user:pass@localhost/source_db \
  --target-db mysql://user:pass@localhost/target_db \
  --source-procedure calculate_order_total \
  --target-procedure calculate_order_total \
  --test-data test-orders.yaml \
  --check-equivalence
```

**Prüfungen**:
- ✅ Ausführung mit identischen Testdaten
- ✅ Vergleich der Ergebnisse (SHA-256 Hash)
- ✅ Performance-Vergleich (optional)
- ✅ Edge-Cases (NULL-Werte, leere Ergebnisse)

### Validierungsbericht

```yaml
validation_report:
  procedure: calculate_order_total
  source_system: PostgreSQL 14.5
  target_system: MySQL 8.0.34
  timestamp: 2025-10-22T14:30:00Z

  syntax_validation:
    status: passed
    checks:
      - sql_syntax: valid
      - parameter_types: compatible
      - return_type: compatible
      - dependencies: resolved

  semantic_validation:
    status: passed
    test_cases: 25
    test_results:
      - test_case: order_with_items
        source_result: 1234.56
        target_result: 1234.56
        match: true
      - test_case: order_without_items
        source_result: null
        target_result: null
        match: true
      - test_case: order_large_values
        source_result: 99999999.99
        target_result: 99999999.99
        match: true

    performance:
      source_avg_time_ms: 2.3
      target_avg_time_ms: 1.8
      performance_ratio: 1.28

  recommendation: "Die Stored Procedure wurde erfolgreich migriert. Alle Tests bestanden. Die MySQL-Version ist 28% schneller als das PostgreSQL-Original."
```

---

## Zusammenfassung

Dieses Beispiel zeigt, wie **d-migrate** die Migration einer Stored Procedure zwischen verschiedenen Datenbanksystemen vereinfacht:

### Vorteile

1. **KI-gestützte Transformation**: Automatische Analyse und Konvertierung der Logik
2. **Neutrales Zwischenformat**: Menschenlesbare Dokumentation der Prozedur-Logik
3. **Best Practices**: Automatische Anwendung von Namenskonventionen und Syntax-Standards
4. **Validierung**: Automatische Überprüfung von Syntax und Semantik
5. **Nachvollziehbarkeit**: Vollständige Dokumentation des Transformationsprozesses

### Datenschutz und Sicherheit

- **Lokale Modelle bevorzugt**: Verwendung von Ollama für sensible Code-Transformationen
- **Opt-in für externe APIs**: Keine automatische Übertragung an Cloud-Services
- **Audit-Trail**: Vollständige Nachvollziehbarkeit der Transformation
- **Versionierung**: Quell- und Zielcode werden für Auditing aufbewahrt

### Performance

- **Transformation**: < 10 Sekunden für durchschnittliche Stored Procedure (<100 Zeilen)
- **Validierung**: < 5 Sekunden syntaktische Prüfung
- **Semantische Tests**: Abhängig von Testdaten-Umfang

---

## Nächste Schritte

Nach erfolgreicher Migration können weitere Schritte durchgeführt werden:

1. **Integration in CI/CD**: Automatische Validierung bei Schema-Änderungen
2. **Batch-Migration**: Migration aller Stored Procedures in einem Durchgang
3. **Dokumentation**: Automatische Generierung von Prozedur-Dokumentation
4. **Monitoring**: Überwachung der Prozedur-Performance im Produktivbetrieb

---

## Verwandte Dokumentation

- [Lastenheft d-migrate](./lastenheft-d-migrate.md) - Vollständige Anforderungsspezifikation
- **LF-004**: Reverse-Engineering von Datenbankstrukturen
- **LF-017**: KI-gestützte Transformation von Stored Procedures
- **UC-04**: KI-gestützte Stored Procedure Migration
- **LN-032 bis LN-036**: KI-Integration und Datenschutz-Anforderungen

---

**Version**: 1.0
**Stand**: 2025-10-22
**Lizenz**: Apache 2.0 / MIT (gemäß Projekt-Lizenz)
