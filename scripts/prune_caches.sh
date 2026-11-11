#!/usr/bin/env bash
# Prunes GitHub Actions caches so the repo stays well under the 10 GB limit and
# restores keep hitting the newest entries.
#
# Rules (per git ref):
#   1. Cache keys are grouped into families by stripping the version tag (-vN)
#      and the trailing content hash, e.g. "gradle-dependencies-v2-<hash>"
#      -> family "gradle-dependencies", version 2.
#   2. Only the highest version of a family is kept (v1 goes once v2 exists).
#   3. Of that version only the most recently used entry is kept; restores match
#      by prefix, so older siblings are never hit again.
#   4. Anything not used for MAX_IDLE_DAYS is deleted.
#   5. Caches of closed pull requests are deleted.
#
# Usage: scripts/prune_caches.sh [--dry-run] [--pr <number>]
#   --pr N   only delete the caches of pull request N (for the PR-closed event)
# Needs: gh (authenticated, actions:write), jq.
set -euo pipefail

DRY_RUN=false
ONLY_PR=""
MAX_IDLE_DAYS="${MAX_IDLE_DAYS:-3}"
REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true ;;
    --pr) ONLY_PR="$2"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

delete_cache() { # id key ref reason
  if $DRY_RUN; then
    echo "would delete  [$4]  $3  $2"
  else
    gh api -X DELETE "repos/$REPO/actions/caches/$1" >/dev/null && echo "deleted  [$4]  $3  $2"
  fi
}

caches=$(gh api --paginate "repos/$REPO/actions/caches?per_page=100" --jq '.actions_caches[]' | jq -s '.')
before=$(jq '[.[].size_in_bytes] | add // 0' <<<"$caches")
echo "Caches: $(jq length <<<"$caches"), $(( before / 1048576 )) MB"

if [[ -n "$ONLY_PR" ]]; then
  jq -r --arg ref "refs/pull/$ONLY_PR/merge" '.[] | select(.ref == $ref) | [.id, .key, .ref] | @tsv' <<<"$caches" |
    while IFS=$'\t' read -r id key ref; do delete_cache "$id" "$key" "$ref" "closed PR"; done
  exit 0
fi

# Closed pull requests: delete all of their caches.
open_prs=$(gh pr list --repo "$REPO" --state open --limit 200 --json number --jq '[.[].number | tostring]')
jq -r --argjson open "$open_prs" '
  .[] | select(.ref | test("^refs/pull/[0-9]+/merge$"))
      | select((.ref | capture("refs/pull/(?<n>[0-9]+)/").n) as $n | ($open | index($n)) | not)
      | [.id, .key, .ref] | @tsv' <<<"$caches" |
  while IFS=$'\t' read -r id key ref; do delete_cache "$id" "$key" "$ref" "closed PR"; done

# Family / version / recency rules for everything else.
cutoff=$(date -u -d "-$MAX_IDLE_DAYS days" +%s 2>/dev/null || date -u -v "-${MAX_IDLE_DAYS}d" +%s)
jq -r --argjson cutoff "$cutoff" '
  map(select(.ref | test("^refs/pull/") | not))
  | map(. + (
      (.key | capture("^(?<a>.*?)-v(?<v>[0-9]+)(?<b>.*)-[^-]+$")
        // {a: (.key | sub("-[^-]+$"; "")), v: "0", b: ""})
      | {family: (.a + .b), version: (.v | tonumber)}))
  | map(. + {used: (.last_accessed_at | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)})
  | group_by([.ref, .family])[]
  | (map(.version) | max) as $top
  | (map(select(.version == $top)) | max_by(.used) | .id) as $keep
  | .[]
  | if .version < $top then [.id, .key, .ref, "old version"]
    elif .id != $keep then [.id, .key, .ref, "superseded"]
    elif .used < $cutoff then [.id, .key, .ref, "idle"]
    else empty end
  | @tsv' <<<"$caches" |
  while IFS=$'\t' read -r id key ref reason; do delete_cache "$id" "$key" "$ref" "$reason"; done

if ! $DRY_RUN; then
  after=$(gh api "repos/$REPO/actions/cache/usage" --jq '.active_caches_size_in_bytes')
  echo "Cache usage: $(( before / 1048576 )) MB -> $(( after / 1048576 )) MB"
fi
