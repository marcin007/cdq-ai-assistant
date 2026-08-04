#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
SCRIPT="$ROOT_DIR/scripts/bootstrap-weather-mcp.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

FIXTURE="$TMP/weather-fixture"
CHECKOUT_ROOT="$TMP/checkout-root"
FAKE_BIN="$TMP/bin"
NPM_LOG="$TMP/npm.log"
mkdir -p "$FIXTURE" "$CHECKOUT_ROOT" "$FAKE_BIN"

git -C "$FIXTURE" init -q
git -C "$FIXTURE" config user.email test@example.invalid
git -C "$FIXTURE" config user.name 'Weather bootstrap test'
printf '%s\n' '{"name":"weather-fixture","version":"1.0.0"}' > "$FIXTURE/package.json"
printf '%s\n' '{"lockfileVersion":3,"packages":{"":{"name":"weather-fixture","version":"1.0.0"}}}' > "$FIXTURE/package-lock.json"
git -C "$FIXTURE" add package.json package-lock.json
git -C "$FIXTURE" commit -qm fixture
COMMIT=$(git -C "$FIXTURE" rev-parse HEAD)

cat > "$FAKE_BIN/npm" <<'EOF'
#!/bin/sh
printf '%s|%s\n' "$PWD" "$*" >> "$WEATHER_MCP_NPM_LOG"
case "$*" in
    'ci')
        if [ "${WEATHER_MCP_NPM_DIRTY_DURING_CI:-}" = '1' ]; then
            printf '%s\n' unexpected > "$PWD/unexpected-after-ci.txt"
        fi
        ;;
    --prefix\ *\ ls\ --depth=0\ --offline)
        ;;
    *)
        exit 2
        ;;
esac
EOF
chmod +x "$FAKE_BIN/npm"

run_bootstrap() {
    PATH="$FAKE_BIN:$PATH" \
    WEATHER_MCP_TEST_MODE=1 \
    WEATHER_MCP_UPSTREAM="$FIXTURE" \
    WEATHER_MCP_COMMIT="$COMMIT" \
    WEATHER_MCP_ROOT="$CHECKOUT_ROOT" \
    WEATHER_MCP_NPM_LOG="$NPM_LOG" \
    "$SCRIPT"
}

run_bootstrap
DEST="$CHECKOUT_ROOT/.local/mcp-weather"
[ "$(git -C "$DEST" rev-parse HEAD)" = "$COMMIT" ]
[ "$(git -C "$DEST" remote get-url origin)" = "$FIXTURE" ]
[ "$(sed -n '1p' "$NPM_LOG")" = "$DEST|ci" ]
[ "$(sed -n '2p' "$NPM_LOG")" = "$ROOT_DIR|--prefix $DEST ls --depth=0 --offline" ]

run_bootstrap
[ "$(wc -l < "$NPM_LOG" | tr -d ' ')" = 4 ]

git -C "$DEST" remote set-url origin "$TMP/unexpected-origin"
if run_bootstrap >/dev/null 2>&1; then
    printf '%s\n' 'expected unexpected origin to fail' >&2
    exit 1
fi
[ "$(wc -l < "$NPM_LOG" | tr -d ' ')" = 4 ]

git -C "$DEST" remote set-url origin "$FIXTURE"
printf '%s\n' dirty > "$DEST/tracked.txt"
git -C "$DEST" add tracked.txt
if run_bootstrap >/dev/null 2>&1; then
    printf '%s\n' 'expected tracked local change to fail' >&2
    exit 1
fi
[ "$(wc -l < "$NPM_LOG" | tr -d ' ')" = 4 ]

git -C "$DEST" reset -q --hard "$COMMIT"
printf '%s\n' unexpected > "$DEST/untracked.txt"
if run_bootstrap >/dev/null 2>&1; then
    printf '%s\n' 'expected unexpected untracked file to fail' >&2
    exit 1
fi
[ "$(wc -l < "$NPM_LOG" | tr -d ' ')" = 4 ]

rm "$DEST/untracked.txt"
export WEATHER_MCP_NPM_DIRTY_DURING_CI=1
if run_bootstrap >/dev/null 2>&1; then
    printf '%s\n' 'expected install-created untracked file to fail final validation' >&2
    exit 1
fi
unset WEATHER_MCP_NPM_DIRTY_DURING_CI
[ "$(wc -l < "$NPM_LOG" | tr -d ' ')" = 6 ]

printf '%s\n' 'bootstrap-weather-mcp tests passed'
