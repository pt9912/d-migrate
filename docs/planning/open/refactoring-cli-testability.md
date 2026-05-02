# Refactoring: CLI-Command-Testbarkeit

> **Status**: Offen
> **Prioritaet**: Mittel
> **Erstellt**: 2026-04-15

---

## Problem

Die Clikt-Command-Klassen (`DataProfileCommand`, `ExportFlywayCommand`,
`SchemaReverseCommand` etc.) enthalten in `run()` Wiring-Code, der:

- `NamedConnectionResolver` instanziiert
- `ConnectionUrlParser.parse()` aufruft
- `HikariConnectionPoolFactory.create()` aufruft
- Tool-spezifische Adapter-Lookup-Lambdas baut
- `OutputFormatter` und `ProfileReportWriter` verdrahtet

Dieser Code ist ohne echtes Clikt-Framework nicht unit-testbar und drueckt
die Modul-Coverage. Kover-Excludes funktionieren nicht zuverlaessig in CI
(Gradle Actions Cache-Interaktion).

## Loesung

Den Wiring-Code aus `run()` in eine testbare Factory extrahieren:

```kotlin
// Heute:
class DataProfileCommand : CliktCommand(...) {
    override fun run() {
        val runner = DataProfileRunner(
            connectionResolver = { NamedConnectionResolver(...).resolve(it) },
            // ... 20 Zeilen Verdrahtung
        )
        val exit = runner.execute(request)
        if (exit != 0) throw ProgramResult(exit)
    }
}

// Ziel:
class DataProfileCommand : CliktCommand(...) {
    override fun run() {
        val exit = DataProfileWiring.execute(source, tables, schema, topN, format, output, root)
        if (exit != 0) throw ProgramResult(exit)
    }
}

// Testbar ohne Clikt:
object DataProfileWiring {
    fun execute(...): Int { ... }
}
```

## Betroffene Commands

- `DataProfileCommand`
- `DataExportCommand`
- `DataImportCommand`
- `DataTransferCommand`
- `SchemaReverseCommand`
- `SchemaCompareCommand`
- `SchemaGenerateCommand`
- `ExportFlywayCommand` / `ExportLiquibaseCommand` / `ExportDjangoCommand` / `ExportKnexCommand`

## Abgrenzung

Dieses Refactoring ist kein Teil von Milestone 0.7.5. Es betrifft die
gesamte CLI-Schicht und sollte als eigener Scope behandelt werden.
