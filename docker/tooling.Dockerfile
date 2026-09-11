# Werkzeug-Basisimage der Integrationstests.
#
# Enthaelt, was die Integrationstests zur Laufzeit brauchen und was sich fast
# nie aendert: Python mit Django, Node mit pnpm, und die SpatiaLite-Erweiterung.
# Der Gradle-Cache gehoert NICHT hierher — der haengt an den Build-Dateien des
# Repos und wird im Haupt-`Dockerfile` aus der `deps`-Stage kopiert.
#
# **Dieses Image wird veroeffentlicht und im Haupt-`Dockerfile` per Digest
# gepinnt.** Eine Aenderung hier wirkt deshalb nicht von selbst: sie muss ueber
# den Workflow `tooling-image.yml` gebaut, gepusht und der neue Digest im
# Haupt-`Dockerfile` eingetragen werden. Genau das ist der Zweck — so baut
# niemand die zweiminuetige apt-Schicht bei jedem Lauf neu, und wann sie sich
# aendert, steht als Digest im Repo statt im Zufall der Paketquellen.
FROM gradle:8.14-jdk21

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    curl ca-certificates gnupg \
    build-essential \
    # SpatiaLite-Extension fuer die SQLite-Integrationstests. Sie laeuft ohne
    # Testcontainers gegen eine Datei, braucht die Bibliothek aber im Image:
    # `load_extension('mod_spatialite')` sucht sie im Standard-Library-Pfad.
    libsqlite3-mod-spatialite && \
    python3 -m pip install --break-system-packages --quiet django && \
    # Node 20 aus dem NodeSource-Repo (CWE-494): kein `curl | bash`. Der GPG-Key
    # wird ueber HTTPS geholt, per SHA256 gepinnt und als signed-by-Keyring
    # hinterlegt; danach installiert apt `nodejs` signaturverifiziert.
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o /tmp/nodesource.key && \
    echo "b42e0321dabdc24e892115da705cf061167eac12a317f23d329862d0aa0a271d  /tmp/nodesource.key" | sha256sum -c - && \
    gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg /tmp/nodesource.key && \
    rm /tmp/nodesource.key && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    # pnpm gepinnt wie der Node-Zweig darueber. Ungepinnt zog `npm install -g`
    # die jeweils neueste Version, und die kippte den Build gleich zweifach:
    # sie liest die Einstellung onlyBuiltDependencies nicht mehr aus der
    # package.json, und ab Hauptversion 11 verlangt sie Node >= 22.13.
    # Die beiden Pins gehoeren deshalb zusammen -- wer die Node-Zeile hebt,
    # darf pnpm mitheben, aber nicht umgekehrt.
    npm install -g pnpm@10.34.5 node-gyp && \
    rm -rf /var/lib/apt/lists/*
