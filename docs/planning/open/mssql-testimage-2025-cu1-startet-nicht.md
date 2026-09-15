---
id: mssql-testimage-2025-cu1-startet-nicht
title: "Das MSSQL-Testimage 2025-CU1 startet auf manchen Hosts nicht"
status: open
---

# Das MSSQL-Testimage `2025-CU1-ubuntu-24.04` startet auf manchen Hosts nicht

## Der Befund (gemessen 2026-09-15)

Beim Erweitern des MCP-E2E-Harness um die Dialekt-Matrix startete der
SQL-Server-Dienst nicht. Der Container stirbt mit einem **paldumper-Abbild**:

```
This program has encountered a fatal error and cannot continue running
FAILED to capture a dump. Details in paldumper log.
```

**Nicht die Konfiguration, das Image.** Gegenprobe:

| Image | Ergebnis |
| --- | --- |
| `mcr.microsoft.com/mssql/server:2025-CU1-ubuntu-24.04` | **stirbt** |
| `mcr.microsoft.com/mssql/server:2025-latest` | läuft |
| `mcr.microsoft.com/mssql/server:2022-latest` | läuft |

Gemessen mit einem blanken `docker run` — **ohne** Compose, ohne Volumes, mit
gesetztem `ACCEPT_EULA`/`MSSQL_PID`/`MSSQL_SA_PASSWORD`. 18 GB Speicher frei
zum Zeitpunkt des Versuchs, also kein Ressourcenproblem.

Host: Linux 6.8.0-139-generic.

## Wen es trifft

**`examples/sample-db/docker-compose.yml` pinnt genau dieses Image** (Zeile
mit dem `mssql`-Dienst, Digest
`sha256:698682bab57c02c42bc0aa274b158aeb242d8e9104149a7489628d5535805816`).
Der MSSQL-Leg jenes Harness (`make sample-db-cross-smoke-pg2ms`,
`…-ms2pg`) ist damit auf diesem Host **rot** — unbemerkt, weil die beiden
Läufe als `main`-Gate **best-effort** gefahren werden (siehe die Tabelle in
`examples/sample-db/README.md`).

Der MCP-E2E-Harness hat für sich auf `2025-latest` gewechselt
(`examples/mcp-e2e/docker-compose.yml`, mit Begründung im Kommentar).

## Was zu klären ist

1. **Ist es der Host oder das Image?** Der Befund ist auf **einem** Host
   gemessen. Läuft dasselbe Image auf einem anderen Host (oder in CI)? Wenn
   ja, ist es eine Host-Eigenheit — dann gehört sie dokumentiert, und
   `sample-db` bleibt auf dem gepinnten Image. Wenn nein, ist das gepinnte
   Image die falsche Wahl.
2. **Falls es das Image ist:** auf welches wechseln? `2025-latest` und
   `2022-latest` laufen beide; die Digest-Pin-Konvention (ADR 0014) verlangt
   einen festen Bezugspunkt, und `-latest`-Tags wandern. Ein Wechsel braucht
   also einen konkreten CU-Tag, der läuft — nicht `latest`.
3. **Der best-effort-Gate ist die eigentliche Lücke.** Ein MSSQL-Leg, das
   still rot sein darf, meldet nichts. Beide sample-db-Läufe sind
   `main`-Gate mit `continue-on-error`-Charakter; ob sie zuletzt durchliefen,
   ist nicht abzulesen. Vor dem nächsten Release prüfen, welche
   best-effort-Gates zuletzt tatsächlich grün waren.

## Einschätzung

Kein Produktdefekt — die ausgelieferten Artefakte sind nicht betroffen, und
`d-migrate` selbst hat mit dem Abbild nichts zu tun. Es ist ein
**Test-Infrastruktur-Befund**: die MSSQL-Abdeckung ist auf dem betroffenen
Host dünner als sie aussieht.
