#!/usr/bin/env bash
# =============================================================================
# sshTools — German-comment linter
#
# Flags `+` lines (added on/after a fixed cutoff date — the "Stichtag") whose
# comments contain German prose. sshTools keeps all source comments English
# (see CLAUDE.md); this prevents regressions. Source committed before the cutoff
# is never swept. The project's initial commit is 2026-06-01, so by default the
# cutoff resolves to the empty tree and the whole tree is checked.
#
# Usage:
#   ./tools/check-german-comments.sh                # changes since the cutoff
#   CHECK_CUTOFF=2026-07-01 ./tools/check-german-comments.sh
#   CHECK_BASE=<ref> ./tools/check-german-comments.sh   # explicit base override
# Exit: 0 clean · 1 violation(s) found · 2 environment problem.
# =============================================================================

set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
awk_script="$script_dir/check-german-comments.awk"

if [ ! -f "$awk_script" ]; then
  echo "ERROR: awk script not found: $awk_script" >&2
  exit 2
fi

# Resolve the comparison base from a fixed cutoff date (the "Stichtag"): the
# linter checks everything added on/after the cutoff, so source committed before
# it is never swept. Base = newest commit strictly before the cutoff; if the
# repo has no such history (it started on/after the cutoff), the base is the
# empty tree → the whole current tree is checked. CHECK_BASE overrides the date
# logic with an explicit ref (handy for testing).
cutoff="${CHECK_CUTOFF:-2026-06-01}"
empty_tree="$(git -C "$repo_root" hash-object -t tree /dev/null)"
base="${CHECK_BASE:-}"
if [ -z "$base" ]; then
  base="$(git -C "$repo_root" rev-list -n1 --before="$cutoff 00:00:00" HEAD)"
  [ -z "$base" ] && base="$empty_tree"
fi

if [ "$base" != "$empty_tree" ] && \
   ! git -C "$repo_root" rev-parse --verify --quiet "$base" >/dev/null; then
  echo "ERROR: comparison base not resolvable: $base" >&2
  exit 2
fi

if [ -n "${CHECK_BASE:-}" ]; then scope="vs $base"; else scope="since cutoff $cutoff"; fi

# Two-stage diff: everything since the base AND working-tree changes. Two-dot
# form so the empty-tree base also works (three-dot needs two commits).
committed=$(git -C "$repo_root" diff "$base" HEAD -- '*.kt' '*.kts')
working=$(git -C "$repo_root" diff HEAD -- '*.kt' '*.kts')

violations=$(printf "%s\n%s\n" "$committed" "$working" \
  | awk -f "$awk_script")

if [ -z "$violations" ]; then
  echo "✓ No German comments ($scope)."
  exit 0
fi

echo "═══ German comments added ($scope) ═══"
echo
# Reformat for readability: <path>:<line>:<text>  →  ✗ <path>:<line>\n   <text>
printf "%s\n" "$violations" | awk -F: '
{
  path = $1
  line = $2
  # Reassemble the comment text — it may have contained colons.
  text = $3
  for (i = 4; i <= NF; i++) text = text ":" $i
  printf "✗ %s:%s\n   %s\n", path, line, text
}'

count=$(printf "%s\n" "$violations" | wc -l | tr -d ' ')
echo
echo "$count violation(s)."
exit 1
