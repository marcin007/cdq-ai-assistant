#!/bin/sh
set -u

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
WEATHER_COMMIT='8bb7bd1b8fa7364e6f0ea7772be48c25f4a38038'
WEATHER_REMOTE='https://github.com/semdin/mcp-weather.git'
CHAT_MODEL='qwen3:4b-instruct-2507-q4_K_M'
CHAT_MODEL_ID='0edcdef34593'
EMBEDDING_MODEL='qwen3-embedding:0.6b'
errors=0

fail() {
    printf '%s\n' "preflight: $*" >&2
    errors=1
}

need_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        fail "install $1 and retry"
        return 1
    fi
    return 0
}

major_version() {
    printf '%s\n' "$1" | sed -n 's/^[vV]*\([0-9][0-9]*\).*/\1/p'
}

has_nonempty_env_assignment() {
    awk -v key="$1" '
        $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
            value = $0
            sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", value)
            sub("[[:space:]]*(#.*)?$", "", value)
            if (value != "" && value != "\"\"" && value != "\047\047") found = 1
        }
        END { exit found ? 0 : 1 }
    ' "$ROOT_DIR/.env"
}

has_model_name() {
    printf '%s\n' "$ollama_models" \
        | awk -v name="$1" 'NR > 1 && $1 == name { found = 1 } END { exit found ? 0 : 1 }'
}

has_model_name_and_id() {
    printf '%s\n' "$ollama_models" \
        | awk -v name="$1" -v id="$2" \
            'NR > 1 && $1 == name && $2 == id { found = 1 } END { exit found ? 0 : 1 }'
}

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'Usage: ./scripts/preflight.sh' >&2
    exit 2
fi

if need_command java; then
    java_output=$(java -version 2>&1 || true)
    java_version=$(printf '%s\n' "$java_output" \
        | sed -n 's/.*version "\([^"]*\)".*/\1/p' \
        | sed -n '1p')
    java_major=$(major_version "$java_version")
    if [ -z "$java_major" ] || [ "$java_major" -lt 21 ]; then
        fail "Java 21 or newer is required (install it and ensure java is on PATH)"
    fi
fi

if need_command docker; then
    if ! docker compose version >/dev/null 2>&1; then
        fail "Docker Compose v2 is required (install the docker compose plugin)"
    fi
    if ! docker info >/dev/null 2>&1; then
        fail "Docker daemon is not reachable (start Docker and retry)"
    fi
fi

if need_command ollama; then
    ollama_models=$(ollama list 2>/dev/null || true)
    if ! has_model_name_and_id "$CHAT_MODEL" "$CHAT_MODEL_ID"; then
        fail "run: ollama pull $CHAT_MODEL"
    fi
    if ! has_model_name "$EMBEDDING_MODEL"; then
        fail "run: ollama pull $EMBEDDING_MODEL"
    fi
fi

if need_command node; then
    node_version=$(node --version 2>/dev/null || true)
    node_major=$(major_version "$node_version")
    if [ -z "$node_major" ] || [ "$node_major" -lt 20 ]; then
        fail "Node.js 20 or newer is required (install it and ensure node is on PATH)"
    fi
fi

npm_available=1
if ! need_command npm; then
    npm_available=0
fi
need_command curl || true
git_available=1
if ! need_command git; then
    git_available=0
fi

if [ ! -f "$ROOT_DIR/.env" ]; then
    fail "create .env from .env.example and fill the required keys"
else
    for key in REST_COUNTRIES_API_KEY WEATHER_API_KEY; do
        if ! has_nonempty_env_assignment "$key"; then
            fail "fill $key in .env"
        fi
    done
fi

WEATHER_DIR="$ROOT_DIR/.local/mcp-weather"
weather_ready=1
if [ ! -d "$WEATHER_DIR/.git" ]; then
    weather_ready=0
elif [ "$git_available" -eq 1 ]; then
    weather_git_ok=1
    weather_head=$(git -C "$WEATHER_DIR" rev-parse HEAD 2>/dev/null) \
        || weather_git_ok=0
    weather_status=$(git -C "$WEATHER_DIR" status \
        --porcelain --untracked-files=normal 2>/dev/null) \
        || weather_git_ok=0
    weather_remote=$(git -C "$WEATHER_DIR" remote get-url origin 2>/dev/null) \
        || weather_git_ok=0
    if [ "$weather_git_ok" -ne 1 ] \
        || [ "$weather_head" != "$WEATHER_COMMIT" ] \
        || [ "$weather_remote" != "$WEATHER_REMOTE" ] \
        || [ -n "$weather_status" ]; then
        weather_ready=0
    fi
else
    weather_ready=0
fi
if [ "$weather_ready" -ne 1 ]; then
    fail "Weather MCP checkout is not the pinned clean commit"
    fail "run: ./scripts/bootstrap-weather-mcp.sh"
elif [ "$npm_available" -eq 1 ] \
    && ! npm --prefix "$WEATHER_DIR" ls --depth=0 --offline >/dev/null 2>&1; then
    fail "Weather MCP dependencies are not installed"
    fail "run: ./scripts/bootstrap-weather-mcp.sh"
fi

if [ "$errors" -ne 0 ]; then
    printf '%s\n' 'preflight: fix the failures above; no secrets were displayed' >&2
    exit 1
fi

printf '%s\n' 'preflight: all local prerequisites are ready'
