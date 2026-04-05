# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-SNAPSHOT] - 2026-04-05

### Added

- Gradle multi-module project structure with three modules: `d-migrate-core`, `d-migrate-formats`, `d-migrate-cli`
- `NeutralType` sealed class with 18 database-agnostic types (identifier, text, char, integer, smallint, biginteger, float, decimal, boolean, datetime, date, time, uuid, json, xml, binary, email, enum, array)
- `SchemaDefinition` model for representing database schemas in a neutral format
- `SchemaValidator` with 18 validation error codes (E001 -- E018) covering structural and semantic checks
- `YamlSchemaCodec` for parsing and writing schema definitions in YAML format
- CLI command `schema validate` for validating schema files from the command line
- CLI output format support: plain text, JSON, and YAML (`--output-format`)
- 83 unit and integration tests across all modules
- Kover code coverage enforcement: >= 90% for core and formats modules, >= 50% for CLI module
- Kotlin 2.1.20 with JVM 21 target
- Gradle 8.12 build configuration
- MIT License
