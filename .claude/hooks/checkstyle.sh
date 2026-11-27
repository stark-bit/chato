#!/usr/bin/env bash
# External: Claude Code feeds stderr of a PostToolUse hook that exits 2 back to Claude
set -u
file=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
case "$file" in *.java) tasks="checkstyle pmd" ;; *.xml|*.gradle|*/Makefile|*.sh) tasks="checkstyle" ;; *) exit 0 ;; esac
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
out=$(./gradlew -q --continue $tasks 2>&1) && exit 0
printf '%s\n' "$out" | grep -qE '\[ERROR\]|\.java:[0-9]+:'$'\t' || { printf 'The checks could not run:\n%s\n' "$(printf '%s\n' "$out" | tail -20)" >&2; exit 2; }
errors=$(printf '%s\n' "$out" | grep -E '\[ERROR\]|\.java:[0-9]+:'$'\t' | sed -e 's/^\[ant:checkstyle\] \[ERROR\] //' -e "s#^$PWD/##" | grep -F "${file#"$PWD"/}")
[ -z "$errors" ] && exit 0
printf 'Checks found violations in this file (fix the cause, do not suppress it):\n%s\n' "$errors" >&2
exit 2
