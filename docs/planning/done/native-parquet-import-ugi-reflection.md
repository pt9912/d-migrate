# Tracker: Parquet-Import im nativen Binary scheitert an fehlender UGI-Reflection

> **BEHOBEN 2026-08-16.** Der Lesepfad geht jetzt über `LocalInputFile` statt
> über Hadoop-`Path` — damit wird Hadoops `FileSystem` nie betreten und die
> `UserGroupInformation` nie initialisiert. Das behebt zugleich den zweiten,
> tieferen Fehler `getSubject is not supported` (auf dem JDK des
> GraalVM-Builders entfernt), gegen den Reflection-Metadata nichts ausrichtet.
> Die Sonde `native-probe.sh` übt den Import jetzt aus (in eine frische DB,
> Exit-0-Vertrag), und `native-image.yml` fährt einen Parquet-Round-Trip-Smoke
> über SQLite mit Zeilenzahl-Assertion auf beiden Legs. Funktional belegt:
> alle drei Import-Formen liefern 500 Zeilen mit exakten Prüfsummen durch das
> native Binary.
>
> **Status:** Befund mit Repro und Kontrollnachweis (2026-08-16)
> **Trigger:** Beim Verifizieren der v1.0.1-Native-Fixes fiel auf, dass
> `data import` einer Parquet-Quelle im nativen Binary abbricht.
> **Kontrolllauf:** Das publizierte `ghcr.io/pt9912/d-migrate:native` (1.0.0)
> scheitert **identisch** — die Lücke ist vorbestehend und besteht in jedem bisher
> veröffentlichten nativen Binary. Keine Regression der CVE-Arbeit vom 15./16.08.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung, ob die
> Registrierungen per `make native-diagnose` erhoben und ergänzt werden oder der
> Parquet-Import im nativen Binary als dokumentierte Nichtfähigkeit ausgewiesen wird.

## Symptom

```
d-migrate data import --target sqlite:///w/x.db --source /w/bundle

Error: Import failed: Cannot reflectively read or write field
  'final org.apache.hadoop.metrics2.lib.MetricsRegistry
   org.apache.hadoop.security.UserGroupInformation$UgiMetrics.registry'.
```

Kein Datensatz wird geschrieben. Der Parquet-**Export** läuft im nativen Binary
durch; JVM-Distributionen (Fat JAR, Container-Image) sind nicht betroffen.

## Warum es nie auffiel

Kein einziger Native-Smoke übt den Parquet-Import aus:

- `native-image.yml` prüft `--version`, `--help`, `schema validate`.
- Die sample-db-Smokes gegen das native Runtime-Image nutzen keine
  Parquet-Import-Strecke.
- In der Reachability-Metadata des Repos
  (`cli/reachability-metadata.json`, `cli-manual/reflect-config.json`) existiert
  **kein** `UserGroupInformation`-Eintrag — der Pfad wurde beim Erheben der
  Registrierungen (F.0-Diagnoselauf) offenbar nie betreten.

Dasselbe Muster wie beim Bundle-Mitglied-Import auf der JVM
([done/parquet-single-file-import-type-cast.md](../done/parquet-single-file-import-type-cast.md)):
Ein Pfad, den kein Test betritt, ist kaputt, ohne dass etwas rot wird.

## Wege

1. **Registrierungen erheben und ergänzen.** `make native-diagnose` ist genau
   dafür gebaut („alle fehlenden Registrierungen auf einmal erheben") — mit einem
   Lauf, der den Parquet-Import enthält. Danach ein Import-Schritt im
   `native-image.yml`-Smoke, damit die Lücke nicht wieder aufgeht. Risiko:
   Hadoop-Reflection kann kaskadieren (UGI → Metrics → …); der Diagnoselauf
   beantwortet das in einem Durchgang statt per Whack-a-mole.
2. **Als Nichtfähigkeit dokumentieren.** Falls der Parquet-Import im nativen
   Binary kein Ziel ist: klare Fehlermeldung samt Verweis auf die
   JVM-Distributionen, Eintrag im Administrationshandbuch. Billiger, aber das
   native Binary bliebe funktional schmaler als dokumentiert.

Unabhängig vom Weg: **ein Parquet-Import-Schritt gehört in den Native-Smoke** —
sonst bewacht auch Weg 2 niemand.
