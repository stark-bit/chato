#!/usr/bin/env bash
# PostToolUse hook: after a Java file is edited, run Checkstyle (package boundaries and project
# rules, see docs/ARCHITECTURE.md). Violations go to stderr with exit 2, which Claude Code feeds
# back to Claude in the same turn.
set -u
file=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')
case "$file" in *.java) ;; *) exit 0 ;; esac
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
out=$(./gradlew -q checkstyle 2>&1) && exit 0
errors=$(printf '%s\n' "$out" | grep -F '[ERROR]' | sed -e 's/^\[ant:checkstyle\] \[ERROR\] //' -e "s#^$PWD/##")
if [ -z "$errors" ]; then
    printf 'Checkstyle could not run:\n%s\n' "$(printf '%s\n' "$out" | tail -20)" >&2
else
    printf 'Checkstyle found violations (fix the cause, do not suppress it):\n%s\n' "$errors" >&2
fi
exit 2
