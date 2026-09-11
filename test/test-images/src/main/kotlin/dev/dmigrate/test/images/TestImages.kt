package dev.dmigrate.test.images

import org.testcontainers.utility.DockerImageName

/**
 * Die Container-Images, gegen die die Integrationstests laufen — eine Stelle je
 * Dialekt. Wer eine Version hebt, hebt sie hier, und nur hier.
 *
 * **Jede Angabe traegt Version und Digest.** Der Tag sagt einem Menschen, was
 * laeuft; der Digest sorgt dafuer, dass zwei Laeufe dieselben Bytes sehen. Ein
 * blosser Tag bewegt sich unter der Hand — `postgres:18-alpine` ist nach jedem
 * Patch ein anderes Image. Den neuen Digest liefert
 * `docker buildx imagetools inspect --format '{{.Manifest.Digest}}' <image>`.
 *
 * Welche Versionen hier stehen duerfen, ist eine Zusage und steht im Lastenheft
 * ("Unterstuetzte Datenbankversionen"): geprueft wird gegen die **Obergrenze**
 * der unterstuetzten Spanne.
 *
 * **Warum `DockerImageName` und nicht blosse Zeichenketten.** Sobald ein Name
 * einen Digest traegt, kann Testcontainers ihn nicht mehr gegen den erwarteten
 * Dialekt pruefen und bricht ab ("Failed to verify that image … is a compatible
 * substitute"). Die Zusicherung dazu gehoert genau einmal je Dialekt neben den
 * Namen — nicht an jede der gut hundert Aufrufstellen.
 */
object TestImages {

    /** PostgreSQL — Obergrenze der unterstuetzten Spanne. */
    val POSTGRESQL: DockerImageName =
        DockerImageName.parse(
            "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2",
        ).asCompatibleSubstituteFor("postgres")

    /**
     * PostgreSQL mit PostGIS, fuer die Geometrie-Suiten. Gleicher Serverstand
     * wie [POSTGRESQL] — ein Geometriepfad, der gegen eine aeltere Engine
     * prueft, sagt nichts ueber die, gegen die alles andere laeuft.
     */
    val POSTGIS: DockerImageName =
        DockerImageName.parse(
            "postgis/postgis:18-3.6@" +
                "sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677",
        ).asCompatibleSubstituteFor("postgres")

    /** MySQL — Obergrenze der unterstuetzten Spanne. */
    val MYSQL: DockerImageName =
        DockerImageName.parse(
            "mysql:9.7.2@sha256:b2cf29815e62fea06b7de22b8b8e57dfe0eb32706e38ce951930bde6faef4ab6",
        ).asCompatibleSubstituteFor("mysql")

    /** SQL Server — Obergrenze der unterstuetzten Spanne. */
    val MSSQL: DockerImageName =
        DockerImageName.parse(
            "mcr.microsoft.com/mssql/server:2025-latest@" +
                "sha256:b036b61e953e6e660f04514fc3f703b995a9cdda569cf96d07d3f751240f615a",
        ).asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server")

    /**
     * Oracle, schlanke Variante. Deckt alles ab, was die Tests brauchen, ausser
     * Geometrie — dafuer gibt es [ORACLE_FULL].
     */
    val ORACLE: DockerImageName =
        DockerImageName.parse(
            "gvenzl/oracle-free:23-slim-faststart@" +
                "sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093",
        ).asCompatibleSubstituteFor("gvenzl/oracle-free")

    /**
     * Oracle, volle Variante mit `SDO_GEOMETRY`. Rund ein Gigabyte groesser —
     * nur dort nehmen, wo die schlanke nicht reicht.
     */
    val ORACLE_FULL: DockerImageName =
        DockerImageName.parse(
            "gvenzl/oracle-free:23-faststart@" +
                "sha256:33322969a450d72c507b08d7f690d9e1d9b60aebaebc35a75b7c0b8ab4baac77",
        ).asCompatibleSubstituteFor("gvenzl/oracle-free")
}
