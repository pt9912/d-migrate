# d-migrate — Release-Register

> Kanonischer, **auflösender** Link-Ziel-Ort für Erwähnungen eigener d-migrate-Releases.
> **Nur die aktuelle Version** trägt einen expliziten HTML-Anker `#vX.Y.Z` (wörtlich, mit
> Punkten — der Heading-/Tabellen-Slug verschluckt sie). Beim Release **wandert** der Anker
> zur neuen aktuellen Version; die bisherige Zeile verliert ihn. Dadurch **bricht** jeder
> feste Link auf eine veraltete Version (`anchor-missing`), und ein vergessener Bump fällt
> auf — das ist der Zweck dieses Registers.
>
> **Kein Duplikat** der Änderungen — die stehen im [CHANGELOG](CHANGELOG.md). Hier nur
> Versions-Koordinaten (Datum, Art, Tag). **Fremde** Versionen (d-check, semgrep, GraalVM,
> Kotlin) gehören nicht hierher, sondern verlinken auf ihre eigene Quelle.

## Aktuell

<a id="aktuell"></a>
Aktuelle Version: [`v1.0.0-RC3`](#v1.0.0-RC3) — 2026-08-09 (Vorabversion).

Aus anderen Dokumenten stabil referenzierbar als `version.md#aktuell` (zeigt immer hierher,
nie auf eine feste Nummer). Pro Release sind diese Zeile, eine neue Tabellenzeile im Verlauf
**und der `<a id>`-Anker** nachzuziehen — der einzige Bump-Punkt.

Welche Version `:latest`, der Homebrew-Tap und ein `docker pull` ohne Tag liefern, steht
nicht hier: Vorabversionen bewegen diese Zeiger bewusst nicht
([`releasing.md` 4.9](docs/user/releasing.md)). Die Spalte **Art** im Verlauf macht
sichtbar, welches der jüngste Stable-Eintrag ist.

## Verlauf

| Version | Datum | Art | Release |
| ------- | ----- | --- | ------- |
| `v1.0.0-RC3` <a id="v1.0.0-RC3"></a> | 2026-08-09 | Vorabversion | [Tag v1.0.0-RC3](https://github.com/pt9912/d-migrate/releases/tag/v1.0.0-RC3) |
| `v1.0.0-RC2` | 2026-07-31 | Vorabversion | [Tag v1.0.0-RC2](https://github.com/pt9912/d-migrate/releases/tag/v1.0.0-RC2) |
| `v1.0.0-RC1` | 2026-07-16 | Vorabversion | [Tag v1.0.0-RC1](https://github.com/pt9912/d-migrate/releases/tag/v1.0.0-RC1) |
| `v0.9.12` | 2026-07-13 | Stable | [Tag v0.9.12](https://github.com/pt9912/d-migrate/releases/tag/v0.9.12) |
| `v0.9.11` | 2026-07-12 | Stable | [Tag v0.9.11](https://github.com/pt9912/d-migrate/releases/tag/v0.9.11) |
| `v0.9.10` | 2026-07-11 | Stable | [Tag v0.9.10](https://github.com/pt9912/d-migrate/releases/tag/v0.9.10) |
| `v0.9.9` | 2026-07-08 | Stable | [Tag v0.9.9](https://github.com/pt9912/d-migrate/releases/tag/v0.9.9) |
| `v0.9.8` | 2026-06-14 | Stable | [Tag v0.9.8](https://github.com/pt9912/d-migrate/releases/tag/v0.9.8) |
| `v0.9.7` | 2026-06-02 | Stable | [Tag v0.9.7](https://github.com/pt9912/d-migrate/releases/tag/v0.9.7) |
| `v0.9.6` | 2026-05-08 | Stable | [Tag v0.9.6](https://github.com/pt9912/d-migrate/releases/tag/v0.9.6) |
| `v0.9.5` | 2026-04-24 | Stable | [Tag v0.9.5](https://github.com/pt9912/d-migrate/releases/tag/v0.9.5) |
| `v0.9.4` | 2026-04-21 | Stable | [Tag v0.9.4](https://github.com/pt9912/d-migrate/releases/tag/v0.9.4) |
| `v0.9.3` | 2026-04-21 | Stable | [Tag v0.9.3](https://github.com/pt9912/d-migrate/releases/tag/v0.9.3) |
| `v0.9.2` | 2026-04-20 | Stable | [Tag v0.9.2](https://github.com/pt9912/d-migrate/releases/tag/v0.9.2) |
| `v0.9.1` | 2026-04-19 | Stable | [Tag v0.9.1](https://github.com/pt9912/d-migrate/releases/tag/v0.9.1) |
| `v0.9.0` | 2026-04-17 | Stable | [Tag v0.9.0](https://github.com/pt9912/d-migrate/releases/tag/v0.9.0) |
| `v0.8.0` | 2026-04-16 | Stable | [Tag v0.8.0](https://github.com/pt9912/d-migrate/releases/tag/v0.8.0) |
| `v0.7.5` | 2026-04-15 | Stable | [Tag v0.7.5](https://github.com/pt9912/d-migrate/releases/tag/v0.7.5) |
| `v0.7.0` | 2026-04-15 | Stable | [Tag v0.7.0](https://github.com/pt9912/d-migrate/releases/tag/v0.7.0) |
| `v0.6.0` | 2026-04-14 | Stable | [Tag v0.6.0](https://github.com/pt9912/d-migrate/releases/tag/v0.6.0) |
| `v0.5.5` | 2026-04-13 | Stable | [Tag v0.5.5](https://github.com/pt9912/d-migrate/releases/tag/v0.5.5) |
| `v0.5.0` | 2026-04-13 | Stable | [Tag v0.5.0](https://github.com/pt9912/d-migrate/releases/tag/v0.5.0) |
| `v0.4.0` | 2026-04-12 | Stable | [Tag v0.4.0](https://github.com/pt9912/d-migrate/releases/tag/v0.4.0) |
| `v0.3.0` | 2026-04-06 | Stable | [Tag v0.3.0](https://github.com/pt9912/d-migrate/releases/tag/v0.3.0) |
| `v0.2.0` | 2026-04-06 | Stable | [Tag v0.2.0](https://github.com/pt9912/d-migrate/releases/tag/v0.2.0) |
| `v0.1.0` | 2026-04-05 | Stable | [Tag v0.1.0](https://github.com/pt9912/d-migrate/releases/tag/v0.1.0) |
