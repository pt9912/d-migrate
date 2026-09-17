#!/usr/bin/env bash
# SHELL des make-Targets `doc-immutable` (Verdrahtung: make/gate.mk).
#
# Warum: d-checks git-Leser sieht in einem gewachsenen Arbeits-Repo nicht jedes
# Objekt — Packs, deren Name nicht mit `pack-` beginnt (`git maintenance` legt
# `loose-*.pack` an), liest er nicht. Eine Kernaenderung endete dort mit
# 0 Befunden und Exit 0. Befund und Messung:
# docs/planning/done/doc-immutable-lokal-still-gruen.md.
#
# Deshalb laeuft die Recipe-Zeile aus make/d-check.mk (generiert, bleibt
# unberuehrt) gegen einen frischen `git clone --no-local`. make ruft dieses
# Skript als `<skript> <.SHELLFLAGS> <recipe-zeile>` auf; es
#   1. loest RANGE im Arbeits-Repo zu Commits auf (auch `origin/main`, `HEAD~3`),
#   2. klont, holt genau diese Commits und legt sie im Klon unter den beiden
#      Refs ab, auf die make/gate.mk RANGE umlenkt,
#   3. uebertraegt bei STAGED=1 den Index des Arbeits-Repos in den Klon,
#   4. fuehrt die Recipe-Zeile aus (sie haengt den Klon ein) und
#   5. raeumt den Klon weg, auch bei Fehler oder Abbruch.
# Nicht gestagte Aenderungen im Arbeitsbaum zaehlen fuer keinen der beiden
# Modi; nur `.d-check.yml` kommt aus dem Arbeitsbaum, wie vorher.
#
# Parameter aus der Umgebung (make/gate.mk exportiert sie nur fuer dieses Target):
#   DOC_IMMUTABLE_REPO    Arbeits-Repo
#   DOC_IMMUTABLE_CLONE   Klon = Mount-Quelle der Recipe-Zeile; liegt in einem
#                         Verzeichnis d-migrate-doc-immutable-*, das nur dem
#                         Aufrufer gehoert und nur diesem make-Lauf dient
#                         (make/gate.mk setzt die PID von make in den Namen).
#   DOC_IMMUTABLE_RANGE   RANGE, wie beim make-Aufruf angegeben
#   DOC_IMMUTABLE_STAGED  STAGED, wie beim make-Aufruf angegeben
set -euo pipefail

die() {
  printf 'doc-immutable: %s\n' "$*" >&2
  exit 2
}

[ "$#" -gt 0 ] || die "ohne Recipe-Zeile aufgerufen (nur als SHELL von make gedacht)"

repo=${DOC_IMMUTABLE_REPO:?DOC_IMMUTABLE_REPO fehlt}
clone=${DOC_IMMUTABLE_CLONE:?DOC_IMMUTABLE_CLONE fehlt}
range=${DOC_IMMUTABLE_RANGE-}
staged=${DOC_IMMUTABLE_STAGED-}
workdir=${clone%/*}

# Die Refs, auf die make/gate.mk RANGE im Klon umlenkt.
readonly base_ref=refs/heads/doc-immutable-base
readonly head_ref=refs/heads/doc-immutable-head

# rm -rf nur auf einem Pfad, der erkennbar dieser Klon ist.
case $clone in
  /*) ;;
  *) die "DOC_IMMUTABLE_CLONE ist nicht absolut: $clone" ;;
esac
case ${workdir##*/} in
  d-migrate-doc-immutable-*) ;;
  *) die "DOC_IMMUTABLE_CLONE liegt nicht in einem Verzeichnis d-migrate-doc-immutable-*: $clone" ;;
esac

resolve() {
  git -C "$repo" rev-parse --verify --quiet --end-of-options "$1^{commit}" \
    || die "\"$1\" ist im Arbeits-Repo kein Commit"
}

base=
if [ -n "$staged" ]; then
  head=$(resolve HEAD)
else
  case $range in
    *...*) die "RANGE hat die Form <base>..<head>, nicht <base>...<head>: $range" ;;
    ?*..*) ;;
    *) die "RANGE=<base>..<head> oder STAGED=1 angeben (RANGE=\"$range\")" ;;
  esac
  base=$(resolve "${range%%..*}")
  head_arg=${range#*..}
  head=$(resolve "${head_arg:-HEAD}")
fi

# Ab hier wirken git-Befehle auf den Klon. Eine geerbte GIT_DIR o. Ae. (Lauf aus
# einem Hook) lenkte sie sonst ins Arbeits-Repo; Hooks laufen im Klon keine.
clone_git() {
  env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_OBJECT_DIRECTORY \
    -u GIT_ALTERNATE_OBJECT_DIRECTORIES -u GIT_COMMON_DIR \
    git -c core.hooksPath=/dev/null "$@"
}

# Das Verzeichnis gehoert nur dem Aufrufer; was darin liegt, darf der
# d-check-Container (eigener Benutzer) lesen.
mkdir -m 700 -- "$workdir" 2>/dev/null || true
[ -d "$workdir" ] && [ ! -L "$workdir" ] && [ -O "$workdir" ] \
  || die "$workdir ist kein eigenes Verzeichnis"
chmod 700 -- "$workdir"
umask 022

trap 'rm -rf -- "$clone"; rmdir -- "$workdir" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

rm -rf -- "$clone"
clone_git clone --quiet --no-local --no-checkout --template= -- "$repo" "$clone"

refspecs=("+$head:$head_ref")
[ -z "$base" ] || refspecs+=("+$base:$base_ref")
clone_git -C "$clone" fetch --quiet --no-tags -- "$repo" "${refspecs[@]}"
clone_git -C "$clone" checkout --quiet --detach "$head"

if [ -n "$staged" ]; then
  # Explizite Praefixe stechen diff.noprefix/diff.mnemonicPrefix der Konfiguration.
  if ! git -C "$repo" diff --cached --quiet --no-ext-diff; then
    git -C "$repo" -c diff.relative=false diff --cached --binary --no-color \
      --no-ext-diff --no-textconv --src-prefix=a/ --dst-prefix=b/ \
      | clone_git -C "$clone" apply --index
  fi
  printf 'doc-immutable: Index auf %s, geprueft im Klon %s\n' "$head" "$clone" >&2
else
  printf 'doc-immutable: Range %s..%s (RANGE=%s), geprueft im Klon %s\n' \
    "$base" "$head" "$range" "$clone" >&2
fi

# Ohne Konfiguration prueft vcs keine Datei und endet mit Exit 0.
[ -f "$repo/.d-check.yml" ] || die "$repo/.d-check.yml fehlt"
cp -- "$repo/.d-check.yml" "$clone/.d-check.yml"

rc=0
bash "$@" || rc=$?
exit "$rc"
