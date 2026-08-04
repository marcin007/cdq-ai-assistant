#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)

fail() {
    printf '%s\n' "bootstrap-weather-mcp: $*" >&2
    exit 1
}

if [ "${WEATHER_MCP_TEST_MODE:-}" = '1' ]; then
    : "${WEATHER_MCP_UPSTREAM:?WEATHER_MCP_UPSTREAM is required in test mode}"
    : "${WEATHER_MCP_COMMIT:?WEATHER_MCP_COMMIT is required in test mode}"
    UPSTREAM=$WEATHER_MCP_UPSTREAM
    COMMIT=$WEATHER_MCP_COMMIT
    ROOT_DIR=${WEATHER_MCP_ROOT:-$ROOT_DIR}
else
    UPSTREAM='https://github.com/semdin/mcp-weather.git'
    COMMIT='8bb7bd1b8fa7364e6f0ea7772be48c25f4a38038'
fi

DEST="$ROOT_DIR/.local/mcp-weather"

mkdir -p "$ROOT_DIR/.local"
if [ -e "$DEST" ] && [ ! -d "$DEST/.git" ]; then
    fail "$DEST exists but is not a Git checkout"
fi

if [ ! -e "$DEST" ]; then
    git clone "$UPSTREAM" "$DEST"
else
    actual_remote=$(git -C "$DEST" remote get-url origin 2>/dev/null || true)
    [ "$actual_remote" = "$UPSTREAM" ] || fail "unexpected origin for $DEST: $actual_remote"
    if ! checkout_status=$(git -C "$DEST" status \
        --porcelain --untracked-files=normal); then
        fail "could not inspect local changes in $DEST"
    fi
    [ -z "$checkout_status" ] \
        || fail "$DEST has unexpected local changes"
fi

git -C "$DEST" fetch --no-tags origin "$COMMIT"
git -C "$DEST" checkout --detach "$COMMIT"
actual_commit=$(git -C "$DEST" rev-parse HEAD)
[ "$actual_commit" = "$COMMIT" ] || fail "checked out $actual_commit, expected $COMMIT"

(cd "$DEST" && npm ci)
npm --prefix "$DEST" ls --depth=0 --offline >/dev/null \
    || fail "installed dependencies failed offline validation"

final_remote=$(git -C "$DEST" remote get-url origin 2>/dev/null) \
    || fail "could not verify the final origin in $DEST"
final_commit=$(git -C "$DEST" rev-parse HEAD 2>/dev/null) \
    || fail "could not verify the final commit in $DEST"
final_status=$(git -C "$DEST" status --porcelain --untracked-files=normal) \
    || fail "could not inspect final local changes in $DEST"
[ "$final_remote" = "$UPSTREAM" ] \
    || fail "origin changed during installation: $final_remote"
[ "$final_commit" = "$COMMIT" ] \
    || fail "commit changed during installation: $final_commit"
[ -z "$final_status" ] \
    || fail "$DEST has unexpected local changes after installation"

printf '%s\n' "Weather MCP ready at $DEST ($final_commit)"
