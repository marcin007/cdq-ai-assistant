#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
TMP_ROOT=$(mktemp -d)
CHILD_PIDS=

cleanup() {
    for pid in $CHILD_PIDS; do
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    done
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
    printf '%s\n' "test-local-operations: $*" >&2
    exit 1
}

assert_contains() {
    file=$1
    expected=$2
    grep -F "$expected" "$file" >/dev/null 2>&1 \
        || fail "expected $file to contain: $expected"
}

assert_not_contains() {
    file=$1
    unexpected=$2
    if grep -F "$unexpected" "$file" >/dev/null 2>&1; then
        fail "did not expect $file to contain: $unexpected"
    fi
}

assert_file_absent() {
    if [ -e "$1" ]; then
        [ ! -f "${output:-}" ] || sed -n '1,200p' "$output" >&2
        fail "expected $1 to be absent"
    fi
}

assert_identity_has_nonce() {
    identity_file=$1
    [ "$(wc -l < "$identity_file" | tr -d ' ')" = '4' ] \
        || fail "expected four-line identity metadata in $identity_file"
    identity_command=$(sed -n '2p' "$identity_file")
    identity_nonce=$(sed -n '4p' "$identity_file")
    case "$identity_nonce" in
        ''|*[!0-9a-fA-F]*)
            fail "expected a hexadecimal process nonce in $identity_file"
            ;;
    esac
    [ "${#identity_nonce}" -eq 32 ] \
        || fail "expected a 128-bit process nonce in $identity_file"
    case "$identity_command" in
        *" -Dcdq.local.instance=$identity_nonce -jar "*)
            ;;
        *)
            fail "identity nonce is not bound to the Java command in $identity_file"
            ;;
    esac
}

wait_for_file() {
    file=$1
    attempts=0
    while [ ! -f "$file" ] && [ "$attempts" -lt 100 ]; do
        sleep 0.05
        attempts=$((attempts + 1))
    done
    if [ ! -f "$file" ]; then
        [ ! -f "${output:-}" ] || sed -n '1,200p' "$output" >&2
        fail "timed out waiting for $file"
    fi
}

new_fixture() {
    name=$1
    FIXTURE_ROOT="$TMP_ROOT/$name"
    FAKE_BIN="$FIXTURE_ROOT/fake-bin"
    FAKE_STATE="$FIXTURE_ROOT/state"
    EVENTS="$FAKE_STATE/events"
    mkdir -p \
        "$FIXTURE_ROOT/scripts" \
        "$FIXTURE_ROOT/.local/mcp-weather/.git" \
        "$FIXTURE_ROOT/assistant-app/target" \
        "$FIXTURE_ROOT/countries-mcp-server/target" \
        "$FAKE_BIN" \
        "$FAKE_STATE"
    cp "$PROJECT_ROOT/scripts/preflight.sh" "$FIXTURE_ROOT/scripts/preflight.sh"
    cp "$PROJECT_ROOT/scripts/run-local.sh" "$FIXTURE_ROOT/scripts/run-local.sh"
    cp "$PROJECT_ROOT/scripts/stop-local.sh" "$FIXTURE_ROOT/scripts/stop-local.sh"
    chmod +x "$FIXTURE_ROOT/scripts/"*.sh
    : > "$EVENTS"
    printf '%s\n' '21.0.2' > "$FAKE_STATE/java-version"
    printf '%s\n' 'v20.12.2' > "$FAKE_STATE/node-version"
    printf '%s\n' \
        'qwen3:4b-instruct-2507-q4_K_M 0edcdef34593 2.5 GB now' \
        'qwen3-embedding:0.6b ac6da0dfba84 639 MB now' \
        > "$FAKE_STATE/models"
    printf '%s\n' 'ok' > "$FAKE_STATE/docker-daemon"
    printf '%s\n' '8bb7bd1b8fa7364e6f0ea7772be48c25f4a38038' > "$FAKE_STATE/weather-head"
    printf '%s\n' '43b2bc98e6a84ae1c80dd5e4aec53a750941bcac' > "$FAKE_STATE/project-head"
    printf '%s\n' 'clean' > "$FAKE_STATE/weather-status"
    printf '%s\n' 'clean' > "$FAKE_STATE/project-status"
    printf '%s\n' 'ready' > "$FAKE_STATE/weather-dependencies"
    printf '%s\n' 'https://github.com/semdin/mcp-weather.git' > "$FAKE_STATE/weather-remote"
    printf '%s\n' \
        'REST_COUNTRIES_API_KEY="fixture rest secret"' \
        "WEATHER_API_KEY='fixture weather secret'" \
        'WEATHER_API_URL=https://weather.fixture.test/current.json' \
        'OLLAMA_BASE_URL=http://127.0.0.1:21434' \
        'COUNTRIES_MCP_URL=http://127.0.0.1:28081' \
        'POSTGRES_HOST=127.0.0.1' \
        'POSTGRES_PORT=25432' \
        'POSTGRES_DB=fixture_db' \
        'POSTGRES_USER=fixture_user' \
        'POSTGRES_PASSWORD=fixture_password' > "$FIXTURE_ROOT/.env"
    : > "$FIXTURE_ROOT/compose.yaml"

    apply_fixture_commands
    apply_fixture_maven
}

apply_fixture_commands() {
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/java" "$FAKE_BIN/java"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/docker" "$FAKE_BIN/docker"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/ollama" "$FAKE_BIN/ollama"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/node" "$FAKE_BIN/node"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/npm" "$FAKE_BIN/npm"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/curl" "$FAKE_BIN/curl"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/git" "$FAKE_BIN/git"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/sleep" "$FAKE_BIN/sleep"
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/decoy" "$FAKE_BIN/decoy"
    chmod +x "$FAKE_BIN/"*
}

apply_fixture_maven() {
    cp "$PROJECT_ROOT/scripts/test-fixtures/local-operations/mvnw" "$FIXTURE_ROOT/mvnw"
    chmod +x "$FIXTURE_ROOT/mvnw"
}

run_fixture() {
    PATH="$FAKE_BIN:/usr/bin:/bin" \
    LOCAL_OPS_FAKE_STATE="$FAKE_STATE" \
    "$@"
}

test_env_example_matches_runtime_contract() {
    expected="$TMP_ROOT/env-example.expected"
    cat > "$expected" <<'EOF'
REST_COUNTRIES_API_KEY=
WEATHER_API_KEY=
WEATHER_API_URL=https://api.weatherapi.com/v1/current.json
OLLAMA_BASE_URL=http://127.0.0.1:11434
COUNTRIES_MCP_URL=http://127.0.0.1:8081
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5432
POSTGRES_DB=cdq_assistant
POSTGRES_USER=cdq
POSTGRES_PASSWORD=cdq
EOF
    cmp "$expected" "$PROJECT_ROOT/.env.example" >/dev/null \
        || fail '.env.example does not match the required runtime contract'
}

test_compose_database_contract() {
    assert_contains "$PROJECT_ROOT/compose.yaml" \
        '127.0.0.1:${POSTGRES_PORT:-5432}:5432'
    assert_contains "$PROJECT_ROOT/compose.yaml" \
        'POSTGRES_DB: ${POSTGRES_DB:-cdq_assistant}'
    assert_contains "$PROJECT_ROOT/compose.yaml" \
        'POSTGRES_USER: ${POSTGRES_USER:-cdq}'
    assert_contains "$PROJECT_ROOT/compose.yaml" \
        'POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-cdq}'
}

test_preflight_ready() {
    new_fixture preflight-ready
    output="$FAKE_STATE/output"
    run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1
    assert_contains "$output" 'preflight: all local prerequisites are ready'
    assert_not_contains "$output" 'fixture rest secret'
    assert_not_contains "$output" 'fixture weather secret'
}

test_preflight_rejects_wrong_chat_model_id() {
    new_fixture preflight-wrong-chat-id
    printf '%s\n' \
        'qwen3:4b-instruct-2507-q4_K_M wrong-model 2.5 GB now' \
        'qwen3-embedding:0.6b ac6da0dfba84 639 MB now' \
        > "$FAKE_STATE/models"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected wrong chat model ID to fail'
    fi
    assert_contains "$output" \
        'run: ollama pull qwen3:4b-instruct-2507-q4_K_M'
    assert_not_contains "$output" 'wrong-model'
}

test_preflight_reports_all_actionable_failures() {
    new_fixture preflight-failures
    printf '%s\n' '20.0.2' > "$FAKE_STATE/java-version"
    printf '%s\n' 'v19.9.0' > "$FAKE_STATE/node-version"
    printf '%s\n' \
        'qwen3-embedding:0.6b ac6da0dfba84 639 MB now' \
        > "$FAKE_STATE/models"
    printf '%s\n' 'fail' > "$FAKE_STATE/docker-daemon"
    printf '%s\n' \
        'REST_COUNTRIES_API_KEY=' \
        'WEATHER_API_KEY=   # intentionally empty' > "$FIXTURE_ROOT/.env"
    rm -rf "$FIXTURE_ROOT/.local/mcp-weather/.git"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected incomplete preflight to fail'
    fi
    assert_contains "$output" 'Java 21 or newer is required'
    assert_contains "$output" 'Docker daemon is not reachable'
    assert_contains "$output" 'Node.js 20 or newer is required'
    assert_contains "$output" \
        'run: ollama pull qwen3:4b-instruct-2507-q4_K_M'
    assert_contains "$output" 'fill REST_COUNTRIES_API_KEY in .env'
    assert_contains "$output" 'fill WEATHER_API_KEY in .env'
    assert_contains "$output" 'run: ./scripts/bootstrap-weather-mcp.sh'
    assert_contains "$output" 'no secrets were displayed'
}

test_preflight_rejects_dirty_or_wrong_weather_checkout() {
    new_fixture preflight-weather
    printf '%s\n' 'wrong-commit' > "$FAKE_STATE/weather-head"
    printf '%s\n' 'dirty' > "$FAKE_STATE/weather-status"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected invalid Weather MCP checkout to fail'
    fi
    assert_contains "$output" 'Weather MCP checkout is not the pinned clean commit'
    assert_contains "$output" 'run: ./scripts/bootstrap-weather-mcp.sh'
}

test_preflight_rejects_unexpected_untracked_weather_file() {
    new_fixture preflight-weather-untracked
    printf '%s\n' 'untracked' > "$FAKE_STATE/weather-status"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected an unexpected untracked Weather MCP file to fail'
    fi
    assert_contains "$output" 'Weather MCP checkout is not the pinned clean commit'
    assert_contains "$output" 'run: ./scripts/bootstrap-weather-mcp.sh'
}

test_preflight_rejects_missing_weather_dependencies() {
    new_fixture preflight-weather-dependencies
    printf '%s\n' 'missing' > "$FAKE_STATE/weather-dependencies"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected missing Weather MCP dependencies to fail'
    fi
    assert_contains "$output" 'Weather MCP dependencies are not installed'
    assert_contains "$output" 'run: ./scripts/bootstrap-weather-mcp.sh'
}

test_preflight_rejects_unexpected_weather_remote() {
    new_fixture preflight-weather-remote
    printf '%s\n' 'https://example.invalid/unrelated.git' > "$FAKE_STATE/weather-remote"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected an unexpected Weather MCP remote to fail'
    fi
    assert_contains "$output" 'Weather MCP checkout is not the pinned clean commit'
    assert_contains "$output" 'run: ./scripts/bootstrap-weather-mcp.sh'
}

test_preflight_rejects_weather_status_failure() {
    new_fixture preflight-weather-status-error
    printf '%s\n' 'error' > "$FAKE_STATE/weather-status"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/preflight.sh" >"$output" 2>&1; then
        fail 'expected an unreadable Weather MCP status to fail'
    fi
    assert_contains "$output" 'Weather MCP checkout is not the pinned clean commit'
}

test_run_orders_startup_and_signal_cleanup() {
    new_fixture run-success
    output="$FAKE_STATE/output"
    /usr/bin/env \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        LOCAL_OPS_FAKE_STATE="$FAKE_STATE" \
        "$FIXTURE_ROOT/scripts/run-local.sh" >"$output" 2>&1 &
    supervisor_pid=$!
    CHILD_PIDS="$CHILD_PIDS $supervisor_pid"
    wait_for_file "$FIXTURE_ROOT/.local/run/assistant.pid"
    wait_for_file "$FAKE_STATE/assistant-health"
    assert_identity_has_nonce "$FIXTURE_ROOT/.local/run/countries.identity"
    assert_identity_has_nonce "$FIXTURE_ROOT/.local/run/assistant.identity"
    countries_nonce=$(sed -n '4p' "$FIXTURE_ROOT/.local/run/countries.identity")
    assistant_nonce=$(sed -n '4p' "$FIXTURE_ROOT/.local/run/assistant.identity")
    [ "$countries_nonce" != "$assistant_nonce" ] \
        || fail 'each Java child must receive a distinct process nonce'
    [ "$(sed -n '1p' "$FAKE_STATE/countries-cwd")" = "$FIXTURE_ROOT" ] \
        || fail 'Countries Java child did not start from the project root'
    [ "$(sed -n '1p' "$FAKE_STATE/assistant-cwd")" = "$FIXTURE_ROOT" ] \
        || fail 'assistant Java child did not start from the project root'
    [ "$(sed -n '1p' "$FAKE_STATE/assistant-build")" \
        = '43b2bc98e6a84ae1c80dd5e4aec53a750941bcac|true' ] \
        || fail 'assistant did not receive the verified build attestation'
    assert_not_contains "$output" 'fixture rest secret'
    assert_not_contains "$output" 'fixture weather secret'
    kill -TERM "$supervisor_pid"
    if wait "$supervisor_pid"; then
        fail 'expected TERM status from run-local.sh'
    else
        status=$?
    fi
    [ "$status" -eq 143 ] || fail "expected TERM status 143, got $status"
    CHILD_PIDS=
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.identity"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.identity"
    assert_contains "$output" 'Assistant ready at http://127.0.0.1:8080'
    expected="$FAKE_STATE/expected-events"
    cat > "$expected" <<'EOF'
compose up
compose health
maven package
java countries
health countries
java assistant
health assistant
compose stop
EOF
    grep -E '^(compose up|compose health|maven package|java countries|health countries|java assistant|health assistant|compose stop)$' \
        "$EVENTS" > "$FAKE_STATE/actual-events"
    cmp "$expected" "$FAKE_STATE/actual-events" >/dev/null \
        || fail 'startup/cleanup event order differed'
}

test_failed_assistant_health_cleans_owned_children() {
    new_fixture run-health-failure
    : > "$FAKE_STATE/fail-assistant-health"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/run-local.sh" >"$output" 2>&1; then
        fail 'expected assistant health timeout to fail'
    fi
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.identity"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.identity"
    assert_contains "$output" 'assistant did not become healthy'
    assert_contains "$EVENTS" 'compose stop'
}

test_run_refuses_unverifiable_project_status() {
    new_fixture run-project-status-error
    printf '%s\n' 'error' > "$FAKE_STATE/project-status"
    : > "$FAKE_STATE/fail-assistant-health"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/run-local.sh" >"$output" 2>&1; then
        fail 'expected unverifiable project status to fail'
    fi
    assert_contains "$output" 'could not verify the Git worktree state'
    assert_not_contains "$EVENTS" 'maven package'
    assert_not_contains "$EVENTS" 'java countries'
}

start_fixture_java() {
    jar=$1
    pid_file=$2
    identity_file=$3
    instance_token=$4
    /usr/bin/env \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        LOCAL_OPS_FAKE_STATE="$FAKE_STATE" \
        java "-Dcdq.local.instance=$instance_token" -jar "$jar" &
    child=$!
    CHILD_PIDS="$CHILD_PIDS $child"
    printf '%s\n' "$child" > "$pid_file"
    write_process_identity \
        "$child" "$identity_file" "$FAKE_BIN/java" "$instance_token"
}

write_process_identity() {
    child=$1
    identity_file=$2
    launcher=$3
    instance_token=$4
    attempts=0
    while [ "$attempts" -lt 100 ]; do
        process_start=$(ps -p "$child" -o lstart= 2>/dev/null \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        process_command=$(ps -p "$child" -o command= 2>/dev/null || true)
        if [ -n "$process_start" ] && [ -n "$process_command" ]; then
            printf '%s\n%s\n%s\n%s\n' \
                "$process_start" "$process_command" "$launcher" "$instance_token" \
                > "$identity_file"
            return 0
        fi
        /bin/sleep 0.01
        attempts=$((attempts + 1))
    done
    fail "could not record identity for fixture PID $child"
}

test_explicit_stop_terminates_owned_java_and_preserves_volume() {
    new_fixture explicit-stop
    mkdir -p "$FIXTURE_ROOT/.local/run"
    start_fixture_java \
        "$FIXTURE_ROOT/countries-mcp-server/target/countries-mcp-server-0.0.1-SNAPSHOT.jar" \
        "$FIXTURE_ROOT/.local/run/countries.pid" \
        "$FIXTURE_ROOT/.local/run/countries.identity" \
        '11111111111111111111111111111111'
    start_fixture_java \
        "$FIXTURE_ROOT/assistant-app/target/assistant-app-0.0.1-SNAPSHOT.jar" \
        "$FIXTURE_ROOT/.local/run/assistant.pid" \
        "$FIXTURE_ROOT/.local/run/assistant.identity" \
        '22222222222222222222222222222222'
    sleep 0.1
    output="$FAKE_STATE/output"
    run_fixture "$FIXTURE_ROOT/scripts/stop-local.sh" >"$output" 2>&1
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.pid"
    assert_file_absent "$FIXTURE_ROOT/.local/run/countries.identity"
    assert_file_absent "$FIXTURE_ROOT/.local/run/assistant.identity"
    assert_contains "$EVENTS" 'compose down'
    assert_not_contains "$EVENTS" '--volumes'
    assert_not_contains "$EVENTS" ' -v'
    CHILD_PIDS=
}

test_stop_refuses_command_with_jar_as_irrelevant_argument() {
    new_fixture unsafe-jar-argument
    mkdir -p "$FIXTURE_ROOT/.local/run"
    expected_jar="$FIXTURE_ROOT/countries-mcp-server/target/countries-mcp-server-0.0.1-SNAPSHOT.jar"
    "$FAKE_BIN/decoy" "$expected_jar" &
    unrelated_pid=$!
    CHILD_PIDS="$CHILD_PIDS $unrelated_pid"
    printf '%s\n' "$unrelated_pid" > "$FIXTURE_ROOT/.local/run/countries.pid"
    write_process_identity \
        "$unrelated_pid" \
        "$FIXTURE_ROOT/.local/run/countries.identity" \
        "$FAKE_BIN/java" \
        '33333333333333333333333333333333'
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/stop-local.sh" >"$output" 2>&1; then
        fail 'expected exact-invocation PID refusal'
    fi
    kill -0 "$unrelated_pid" 2>/dev/null || fail 'decoy process was killed'
    [ -f "$FIXTURE_ROOT/.local/run/countries.pid" ] \
        || fail 'unsafe PID file should remain for inspection'
    [ -f "$FIXTURE_ROOT/.local/run/countries.identity" ] \
        || fail 'unsafe identity file should remain for inspection'
    assert_contains "$output" 'refusing to signal unrelated process'
}

test_stop_refuses_expected_jar_process_with_wrong_nonce() {
    new_fixture unsafe-wrong-nonce
    mkdir -p "$FIXTURE_ROOT/.local/run"
    expected_jar="$FIXTURE_ROOT/countries-mcp-server/target/countries-mcp-server-0.0.1-SNAPSHOT.jar"
    current_nonce='44444444444444444444444444444444'
    recorded_nonce='55555555555555555555555555555555'
    /usr/bin/env \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        LOCAL_OPS_FAKE_STATE="$FAKE_STATE" \
        java "-Dcdq.local.instance=$current_nonce" -jar "$expected_jar" &
    unrelated_pid=$!
    CHILD_PIDS="$CHILD_PIDS $unrelated_pid"
    printf '%s\n' "$unrelated_pid" > "$FIXTURE_ROOT/.local/run/countries.pid"
    write_process_identity \
        "$unrelated_pid" \
        "$FIXTURE_ROOT/.local/run/countries.identity" \
        "$FAKE_BIN/java" \
        "$recorded_nonce"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/stop-local.sh" >"$output" 2>&1; then
        fail 'expected wrong-nonce PID refusal'
    fi
    kill -0 "$unrelated_pid" 2>/dev/null || fail 'wrong-nonce process was killed'
    [ -f "$FIXTURE_ROOT/.local/run/countries.pid" ] \
        || fail 'wrong-nonce PID file should remain for inspection'
    [ -f "$FIXTURE_ROOT/.local/run/countries.identity" ] \
        || fail 'wrong-nonce identity file should remain for inspection'
    assert_contains "$output" 'refusing to signal unrelated process'
}

test_stop_refuses_unrelated_reused_pid() {
    new_fixture unsafe-pid
    mkdir -p "$FIXTURE_ROOT/.local/run"
    sleep 30 &
    unrelated_pid=$!
    CHILD_PIDS="$CHILD_PIDS $unrelated_pid"
    printf '%s\n' "$unrelated_pid" > "$FIXTURE_ROOT/.local/run/countries.pid"
    output="$FAKE_STATE/output"
    if run_fixture "$FIXTURE_ROOT/scripts/stop-local.sh" >"$output" 2>&1; then
        fail 'expected unrelated PID refusal'
    fi
    kill -0 "$unrelated_pid" 2>/dev/null || fail 'unrelated process was killed'
    [ -f "$FIXTURE_ROOT/.local/run/countries.pid" ] \
        || fail 'unsafe PID file should remain for inspection'
    assert_contains "$output" 'refusing to signal unrelated process'
}

test_repository_ignore_policy_allows_ds_store_only() {
    fixture="$TMP_ROOT/repository-ignore-policy"
    mkdir -p "$fixture/nested" "$fixture/src"
    cp "$PROJECT_ROOT/.gitignore" "$fixture/.gitignore"
    git -C "$fixture" init -q
    : > "$fixture/.DS_Store"
    : > "$fixture/nested/.DS_Store"
    : > "$fixture/src/Unexpected.java"

    git -C "$fixture" check-ignore -q .DS_Store \
        || fail 'expected Git to ignore root .DS_Store'
    git -C "$fixture" check-ignore -q nested/.DS_Store \
        || fail 'expected Git to ignore nested .DS_Store'
    if git -C "$fixture" check-ignore -q src/Unexpected.java; then
        fail 'did not expect Git to ignore an unrelated source file'
    fi
    [ "$(git -C "$fixture" status --short --untracked-files=normal -- src/Unexpected.java)" \
        = '?? src/Unexpected.java' ] \
        || fail 'expected an unrelated source file to remain visible to Git status'
}

for script in \
    "$PROJECT_ROOT/scripts/preflight.sh" \
    "$PROJECT_ROOT/scripts/run-local.sh" \
    "$PROJECT_ROOT/scripts/stop-local.sh" \
    "$PROJECT_ROOT/scripts/test-local-operations.sh"; do
    sh -n "$script"
done

test_repository_ignore_policy_allows_ds_store_only
test_env_example_matches_runtime_contract
test_compose_database_contract
test_preflight_ready
test_preflight_rejects_wrong_chat_model_id
test_preflight_reports_all_actionable_failures
test_preflight_rejects_dirty_or_wrong_weather_checkout
test_preflight_rejects_unexpected_untracked_weather_file
test_preflight_rejects_missing_weather_dependencies
test_preflight_rejects_unexpected_weather_remote
test_preflight_rejects_weather_status_failure
test_run_orders_startup_and_signal_cleanup
test_failed_assistant_health_cleans_owned_children
test_run_refuses_unverifiable_project_status
test_explicit_stop_terminates_owned_java_and_preserves_volume
test_stop_refuses_unrelated_reused_pid
test_stop_refuses_command_with_jar_as_irrelevant_argument
test_stop_refuses_expected_jar_process_with_wrong_nonce

printf '%s\n' 'local operations tests passed'
