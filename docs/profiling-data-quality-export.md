# Plan: Profiling-Report-Export fuer Data-Quality-Tools

> Dokumenttyp: Integrations- und Architekturplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/profiling.md`, `docs/roadmap.md`,
> `docs/architecture.md`, `docs/cli-spec.md`

---

## 1. Ziel

`d-migrate data profile` erzeugt strukturierte Profiling-Reports mit
Kennzahlen, Warnungen und Typkompatibilitaet. Diese Reports sollen perspektivisch
in gaengige Data-Quality-Werkzeuge exportiert werden koennen, ohne dass
`d-migrate` selbst ein vollstaendiges Data-Quality-Framework wird.

Ziel ist Interoperabilitaet:

- Great Expectations
- Soda
- Pandera
- optional spaeter weitere regelbasierte Formate

---

## 2. Motivation

Profiling ist fuer Migrationen nur dann voll nutzbar, wenn daraus wiederholbare
Checks entstehen. Ein Profiling-Report beantwortet "Wie sehen die Daten jetzt
aus?". Ein Data-Quality-Export soll daraus einen startbaren Pruefvertrag
ableiten:

- Nullability-Erwartungen
- Typ- und Wertebereichserwartungen
- Kardinalitaets- und Uniqueness-Hinweise
- Pattern-Erwartungen fuer E-Mail, UUID, Datum, Telefonnummer
- Grenzwerte fuer Laengen, Min/Max und Top-Value-Abdeckung

Der Export muss konservativ bleiben: Er darf keine harten Constraints erfinden,
die nur aus einer zufaelligen Stichprobe stammen.

---

## 3. Scope

### 3.1 In Scope

- Mapping von `DatabaseProfile` / `TableProfile` / `ColumnProfile` auf
  externe Quality-Artefakte
- Export als Dateien pro Tool
- konservative Regelableitung mit Confidence-/Source-Hinweisen
- CLI- oder Tool-Export-Erweiterung fuer Profiling-Artefakte
- Golden-Master-Tests fuer erzeugte Artefakte

### 3.2 Nicht in Scope

- Ausfuehrung der externen Tools als Kernfeature
- Live-Anbindung an SaaS-Quality-Plattformen
- automatisches Uebernehmen aller Profiling-Warnungen als harte Regeln
- Ersetzen der bestehenden d-migrate-Warning-Engine

---

## 4. Zielbild

Ein moeglicher CLI-Vertrag:

```text
d-migrate data profile --source prod --output profile.json

d-migrate export quality --source-profile profile.json \
  --target great-expectations --output quality/ge
```

Alternativ kann der Export spaeter als Untermodus von `data profile` entstehen:

```text
d-migrate data profile --source prod \
  --quality-export great-expectations --output out/profile
```

Die erste Variante trennt Profiling und Tool-Export klarer und passt besser zu
den bestehenden Tool-Integrationen.

---

## 5. Tool-Zielartefakte

| Tool | Zielartefakt | Erste sinnvolle Abdeckung |
| ---- | ------------ | ------------------------- |
| Great Expectations | Expectation Suite JSON/YAML | Nullability, type, uniqueness, value ranges, regex |
| Soda | SodaCL YAML | missing_count, duplicate_count, invalid_count, ranges |
| Pandera | Python schema module oder YAML | DataFrame schema, nullable, dtype, checks |

Die genaue Syntax muss gegen die jeweils aktuelle Tool-Version validiert
werden, bevor ein Implementierungsplan freigegeben wird.

---

## 6. Regelableitung

Regeln sollten nach Herkunft gekennzeichnet werden:

- `schema`: aus Datenbankschema oder neutralem Schema abgeleitet
- `profile_exact`: aus vollstaendigem Profiling abgeleitet
- `profile_sample`: aus Stichprobe abgeleitet
- `heuristic`: aus Pattern-/Top-Value-Heuristik abgeleitet

Nur `schema` und robuste `profile_exact`-Befunde sollten standardmaessig harte
Checks erzeugen. Heuristiken sollten als kommentierte oder deaktivierte
Vorschlaege erscheinen.

---

## 7. Architekturposition

Der Export sollte als optionaler driven adapter oder Integrationsmodul
entstehen, nicht im Profiling-Core:

```text
hexagon:profiling
        |
        v
Profiling report model
        |
        v
adapters:driven:integrations
        |
        +--> GreatExpectationsExporter
        +--> SodaExporter
        +--> PanderaExporter
```

Damit bleibt das deterministische Profiling unabhaengig von externen
Tool-Versionen.

---

## 8. Akzeptanzkriterien

- Fuer ein kleines Beispielprofil entstehen valide Artefakte fuer mindestens
  ein Zieltool.
- Abgeleitete Regeln sind nachvollziehbar und tragen Herkunftsinformationen.
- Heuristische Regeln werden nicht still als harte Constraints exportiert.
- Golden-Master-Tests pruefen stabile Ausgabe.
- Die Dokumentation zeigt, wie User die erzeugten Artefakte mit dem jeweiligen
  Tool ausfuehren.

---

## 9. Arbeitspakete

1. Profiling-Modell gegen moegliche Quality-Regeln kartieren.
2. Great-Expectations-, Soda- und Pandera-Zielformate konkret pruefen.
3. Minimalen Exportvertrag fuer ein Zieltool entwerfen.
4. Herkunfts- und Confidence-Modell fuer Regeln definieren.
5. Golden-Master-Fixtures auf Basis eines Beispielprofils anlegen.
6. Entscheidung treffen, ob der Export in `integrations` oder ein eigenes
   optionales Modul gehoert.

---

## 10. Risiken

- Tool-Formate und Best Practices koennen sich schnell aendern.
- Profiling-Heuristiken koennen zu strenge Quality-Regeln erzeugen, wenn sie
  nicht klar als Vorschlag markiert werden.
- Pandera ist code-naeher als Great Expectations oder Soda; ein Python-Export
  muss besonders vorsichtig versioniert werden.

