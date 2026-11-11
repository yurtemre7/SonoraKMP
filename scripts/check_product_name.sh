#!/usr/bin/env bash
# Fails if any string resource uses "Sonora" as the product name without the "KMP" suffix.
set -euo pipefail
cd "$(dirname "$0")/.."
hits=$(perl -ne 'print "$ARGV:$.: $_" if /Sonora(?!KMP)/; close ARGV if eof' androidApp/src/main/res/values*/strings*.xml)
if [ -n "$hits" ]; then
  echo "Product name must be \"SonoraKMP\" in string resources:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "Product name check passed."
