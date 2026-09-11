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
# **Basis und Paket gehoeren zusammen.** Der Digest unten ist derselbe, den
# `TestImages.MSSQL` traegt — sonst prueft der Volltext-Slice gegen eine andere
# Engine als der Rest der Suiten, und genau das war lange der Fall (2022 hier,
# 2025 ueberall sonst). Die gepinnte Paketversion entspricht der Engine des
# Basis-Images; wer eine hebt, hebt beide. `scripts/test-images-gate.sh` haelt
# den Digest-Gleichstand fest.
FROM mcr.microsoft.com/mssql/server@sha256:b036b61e953e6e660f04514fc3f703b995a9cdda569cf96d07d3f751240f615a

USER root

# Der Schluessel bleibt ASCII-armiert unter `signed-by=` liegen, statt ihn per
# `gpg --dearmor` umzuwandeln: apt liest armierte Schluessel seit 2.4 direkt,
# und gnupg 2.4 greift beim Umwandeln nach `/dev/tty`, das es im Build nicht
# gibt. Das spart dem Image zugleich gnupg samt Anhang.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && install -d -m 0755 /etc/apt/keyrings \
    && curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
       -o /etc/apt/keyrings/microsoft.asc \
    && chmod 0644 /etc/apt/keyrings/microsoft.asc \
    && echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/microsoft.asc] \
https://packages.microsoft.com/ubuntu/24.04/mssql-server-2025 noble main" \
       > /etc/apt/sources.list.d/mssql-server-2025.list \
    && apt-get update \
    && ACCEPT_EULA=Y apt-get install -y mssql-server-fts=17.0.4085.5-1 \
    && rm -rf /var/lib/apt/lists/*

USER mssql
