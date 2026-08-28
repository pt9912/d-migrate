# Abgeleitetes SQL-Server-Image MIT Full-Text Search.
#
# Warum ueberhaupt: das gepinnte Basis-Image kann kein Full-Text Search.
# `SERVERPROPERTY('IsFullTextInstalled')` liefert dort `0`, und `mssql-server-fts`
# ist nicht aufloesbar — die Microsoft-Paketquelle des Images fuehrt die
# Server-Pakete nicht.
#
# Zwei Eigenschaften, die man kennen muss (ADR 0014 pinnt die Harness sonst auf
# Upstream-Digests):
#
# 1. Der Bau braucht **Netz**. Er laeuft deshalb nicht in den netzlosen Gates,
#    sondern nur ueber `make mssql-fts-image` bzw. den Integrationslauf.
# 2. Ein selbst gebautes Image hat **keinen Upstream-Digest**. Gepinnt bleibt der
#    Basis-Digest; die Reproduzierbarkeit kommt aus diesem Dockerfile.
#
# Das Paket zieht eine passende `mssql-server`-Version mit — die Engine wird also
# mitgehoben, das Image ist keine reine Ergaenzung des Basis-Images.
FROM mcr.microsoft.com/mssql/server@sha256:ba4c8329f48fb8f02e1416be6a930ebfd71268caee78aa985f3af4315e457c89

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gnupg ca-certificates \
    && curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
       | gpg --dearmor -o /usr/share/keyrings/microsoft-prod.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/microsoft-prod.gpg] \
https://packages.microsoft.com/ubuntu/22.04/mssql-server-2022 jammy main" \
       > /etc/apt/sources.list.d/mssql-server-2022.list \
    && apt-get update \
    && ACCEPT_EULA=Y apt-get install -y mssql-server-fts \
    && apt-get purge -y gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

USER mssql
