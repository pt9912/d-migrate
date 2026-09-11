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
# Warum `apt_update` und nicht schlicht `apt-get update`: der Ubuntu-Spiegel
# liefert waehrend einer laufenden Synchronisierung ein `Packages.gz`, dessen
# Groesse nicht zu der passt, die die schon geholte `Release`-Datei zusichert
# ("File has unexpected size … Mirror sync in progress?"). `Acquire::Retries`
# allein hilft dagegen nicht — es wiederholt denselben Download gegen dieselbe
# alte `Release`-Datei. Was hilft, ist ein ganzer neuer Lauf, der beide Dateien
# frisch holt. Schlaegt auch der dritte fehl, ist es kein Zucken mehr und der
# Bau darf scheitern.
RUN set -eu; \
    apt_update() { \
        for attempt in 1 2 3; do \
            apt-get -o Acquire::Retries=3 update && return 0; \
            echo "apt-get update fehlgeschlagen (Versuch ${attempt}) - erneut in 15 s"; \
            sleep 15; \
        done; \
        return 1; \
    }; \
    apt_update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    install -d -m 0755 /etc/apt/keyrings; \
    curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
        -o /etc/apt/keyrings/microsoft.asc; \
    chmod 0644 /etc/apt/keyrings/microsoft.asc; \
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/microsoft.asc] \
https://packages.microsoft.com/ubuntu/24.04/mssql-server-2025 noble main" \
        > /etc/apt/sources.list.d/mssql-server-2025.list; \
    apt_update; \
    ACCEPT_EULA=Y apt-get install -y mssql-server-fts=17.0.4085.5-1; \
    rm -rf /var/lib/apt/lists/*

USER mssql
