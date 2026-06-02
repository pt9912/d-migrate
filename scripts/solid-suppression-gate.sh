#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

# Detekt rules whose suppressions usually hide SOLID/design pressure.
# Keep this aligned with config/detekt/detekt.yml complexity rules.
solid_rules=(
  ComplexMethod
  CyclomaticComplexMethod
  LargeClass
  LongMethod
  LongParameterList
  NestedBlockDepth
  TooManyFunctions
)

include_tests="${SOLID_SUPPRESSION_INCLUDE_TESTS:-0}"

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  mapfile -t kotlin_files < <(git ls-files --cached --others --exclude-standard '*.kt' '*.kts')
else
  mapfile -t kotlin_files < <(find . \( -name '*.kt' -o -name '*.kts' \) -type f -not -path './.git/*' | sed 's#^\./##')
fi

if [[ "${include_tests}" != "1" ]]; then
  filtered=()
  for file in "${kotlin_files[@]}"; do
    case "${file}" in
      */src/test/*|*/src/testFixtures/*|test/*)
        ;;
      *)
        filtered+=("${file}")
        ;;
    esac
  done
  kotlin_files=("${filtered[@]}")
fi

if [[ "${#kotlin_files[@]}" -eq 0 ]]; then
  echo "[solid-suppression-gate] OK - no Kotlin files found"
  exit 0
fi

solid_rule_list="${solid_rules[*]}"

findings="$(
  SOLID_RULES="${solid_rule_list}" perl -0ne '
    BEGIN {
      %solid = map { $_ => 1 } split /\s+/, $ENV{"SOLID_RULES"};
    }

    while (/@Suppress\s*\((.*?)\)/sg) {
      my $match_start = $-[0];
      my $arguments = $1;
      my $prefix = substr($_, 0, $match_start);
      my $line = 1 + ($prefix =~ tr/\n//);

      while ($arguments =~ /"([^"]+)"/g) {
        my $rule = $1;
        next unless $solid{$rule};
        print "$ARGV:$line:$rule\n";
      }
    }
  ' "${kotlin_files[@]}"
)"

if [[ -z "${findings}" ]]; then
  if [[ "${include_tests}" == "1" ]]; then
    echo "[solid-suppression-gate] OK - no SOLID detekt suppression found"
  else
    echo "[solid-suppression-gate] OK - no SOLID detekt suppression found in production Kotlin sources"
  fi
  exit 0
fi

echo "[solid-suppression-gate] FAIL - SOLID detekt suppressions are not allowed"
if [[ "${include_tests}" != "1" ]]; then
  echo "[solid-suppression-gate] Scope: production Kotlin sources only (set SOLID_SUPPRESSION_INCLUDE_TESTS=1 to include tests)"
fi
echo ""
echo "Findings:"
echo "${findings}" | sort
echo ""
echo "Histogram:"
echo "${findings}" | cut -d: -f3 | sort | uniq -c | sort -nr | awk '{ printf "  %-28s %d\n", $2, $1 }'
echo ""
echo "Refactor the code so detekt passes without suppressing SOLID/design rules."
exit 1
