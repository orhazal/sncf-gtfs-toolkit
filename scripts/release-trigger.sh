#!/bin/bash
# Run every 5 minutes by sncf-release-trigger.timer: read the Last-Modified of the SNCF GTFS and of the IDH transfer
# rules, and dispatch the release workflow when the pair has no release yet. The tag is computed exactly like the
# workflow's first step does, which checks again before doing anything.
set -euo pipefail
repo=${GITHUB_REPO:-orhazal/sncf-gtfs-toolkit}
config=$(dirname "$(readlink -f "$0")")/../data/config.json
state=${STATE_DIRECTORY:-/tmp}/last-dispatched-tag

modified() { curl -sSI "$(jq -r ".$1" "$config")" | tr -d '\r' | awk -F': ' 'tolower($1) == "last-modified" {print $2}'; }
gtfs=$(modified gtfs_url); idh=$(modified transfer_rules_url)
test -n "$gtfs" -a -n "$idh"
tag="$(date -u -d "$gtfs" +%Y-%m-%dT%H-%M-%SZ)_$(date -u -d "$idh" +%Y-%m-%dT%H-%M-%SZ)"

# a tag is dispatched at most once an hour: a run that fails does not spawn new runs, a transient failure is retried
if [ -f "$state" ] && [ "$(cat "$state")" = "$tag" ] && [ $(( $(date +%s) - $(stat -c %Y "$state") )) -lt 3600 ]; then exit 0; fi

auth=(); [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
api() { curl -sS -o /dev/null -w '%{http_code}' -H 'Accept: application/vnd.github+json' "${auth[@]}" "$@"; }
code=$(api "https://api.github.com/repos/$repo/releases/tags/$tag")
case $code in
  200) exit 0 ;;
  404) ;;
  *) echo "release check for $tag: HTTP $code" >&2; exit 1 ;;
esac
code=$(api -X POST -d '{"ref":"master"}' "https://api.github.com/repos/$repo/actions/workflows/release.yml/dispatches")
test "$code" = 204 || { echo "dispatch for $tag: HTTP $code" >&2; exit 1; }
echo "$tag" > "$state"
echo "dispatched the release workflow for $tag"
