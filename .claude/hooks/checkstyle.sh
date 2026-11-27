#!/usr/bin/env bash
# External: Claude Code feeds stderr of a PostToolUse hook that exits 2 back to Claude
set -u
file=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
case "$file" in *.java|*.xml|*.gradle|*/Makefile|*.sh) ;; *) exit 0 ;; esac
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
out=$(./gradlew -q checkstyle 2>&1) && exit 0
errors=$(printf '%s\n' "$out" | grep -F '[ERROR]' | sed -e 's/^\[ant:checkstyle\] \[ERROR\] //' -e "s#^$PWD/##")
if [ -z "$errors" ]; then
    printf 'Checkstyle could not run:\n%s\n' "$(printf '%s\n' "$out" | tail -20)" >&2
else
    printf 'Checkstyle found violations (fix the cause, do not suppress it):\n%s\n' "$errors" >&2
fi
exit 2
