#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
RUN_DIR="$ROOT_DIR/.local/run"
LOG_DIR="$ROOT_DIR/.local/logs"
COUNTRIES_JAR="$ROOT_DIR/countries-mcp-server/target/countries-mcp-server-0.0.1-SNAPSHOT.jar"
ASSISTANT_JAR="$ROOT_DIR/assistant-app/target/assistant-app-0.0.1-SNAPSHOT.jar"
COUNTRIES_PID_FILE="$RUN_DIR/countries.pid"
ASSISTANT_PID_FILE="$RUN_DIR/assistant.pid"
COUNTRIES_IDENTITY_FILE="$RUN_DIR/countries.identity"
ASSISTANT_IDENTITY_FILE="$RUN_DIR/assistant.identity"
COUNTRIES_LOG="$LOG_DIR/countries.log"
ASSISTANT_LOG="$LOG_DIR/assistant.log"
compose_started=0
countries_pid=
assistant_pid=
countries_nonce=
assistant_nonce=
cleaned=0

trim_ps_value() {
    sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

is_exact_java_command() {
    process_command=$1
    launcher=$2
    expected_jar=$3
    instance_nonce=$4
    case "$launcher" in
        /*/java)
            ;;
        *)
            return 1
            ;;
    esac
    case "$process_command" in
        "java -Dcdq.local.instance=$instance_nonce -jar $expected_jar"|"$launcher -Dcdq.local.instance=$instance_nonce -jar $expected_jar"|"/bin/sh $launcher -Dcdq.local.instance=$instance_nonce -jar $expected_jar")
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

new_process_nonce() {
    instance_nonce=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')
    case "$instance_nonce" in
        ''|*[!0-9a-fA-F]*)
            return 1
            ;;
    esac
    [ "${#instance_nonce}" -eq 32 ] || return 1
    printf '%s\n' "$instance_nonce"
}

pid_state() {
    candidate=$1
    expected_jar=$2
    identity_file=$3
    case "$candidate" in
        ''|*[!0-9]*)
            return 3
            ;;
    esac
    process_stat=$(ps -p "$candidate" -o stat= 2>/dev/null || true)
    case "$process_stat" in
        ''|Z*)
            return 1
            ;;
    esac
    [ -f "$identity_file" ] && [ ! -L "$identity_file" ] || return 2
    [ "$(wc -l < "$identity_file" | tr -d ' ')" = '4' ] || return 2
    recorded_start=$(sed -n '1p' "$identity_file")
    recorded_command=$(sed -n '2p' "$identity_file")
    recorded_launcher=$(sed -n '3p' "$identity_file")
    recorded_nonce=$(sed -n '4p' "$identity_file")
    [ -n "$recorded_start" ] && [ -n "$recorded_command" ] || return 2
    case "$recorded_nonce" in
        ''|*[!0-9a-fA-F]*)
            return 2
            ;;
    esac
    [ "${#recorded_nonce}" -eq 32 ] || return 2
    process_start=$(ps -p "$candidate" -o lstart= 2>/dev/null | trim_ps_value)
    process_command=$(ps -p "$candidate" -o command= 2>/dev/null | trim_ps_value)
    [ "$process_start" = "$recorded_start" ] || return 2
    [ "$process_command" = "$recorded_command" ] || return 2
    is_exact_java_command \
        "$process_command" "$recorded_launcher" "$expected_jar" "$recorded_nonce" \
        || return 2
    return 0
}

write_process_identity() {
    candidate=$1
    expected_jar=$2
    identity_file=$3
    instance_nonce=$4
    launcher=$(command -v java 2>/dev/null || true)
    attempts=0
    while [ "$attempts" -lt 100 ]; do
        process_stat=$(ps -p "$candidate" -o stat= 2>/dev/null || true)
        process_start=$(ps -p "$candidate" -o lstart= 2>/dev/null | trim_ps_value)
        process_command=$(ps -p "$candidate" -o command= 2>/dev/null | trim_ps_value)
        case "$process_stat" in
            ''|Z*)
                ;;
            *)
                if [ -n "$process_start" ] \
                    && is_exact_java_command \
                        "$process_command" "$launcher" "$expected_jar" "$instance_nonce"; then
                    temporary_identity="$identity_file.tmp.$$"
                    printf '%s\n%s\n%s\n%s\n' \
                        "$process_start" "$process_command" "$launcher" "$instance_nonce" \
                        > "$temporary_identity"
                    mv -f -- "$temporary_identity" "$identity_file"
                    return 0
                fi
                ;;
        esac
        sleep 0.05
        attempts=$((attempts + 1))
    done
    printf '%s\n' \
        "run-local: could not prove ownership of newly started Java PID $candidate" >&2
    return 1
}

remove_stale_pid_file() {
    name=$1
    expected_jar=$2
    pid_file=$3
    identity_file=$4
    if [ ! -f "$pid_file" ]; then
        rm -f -- "$identity_file"
        return 0
    fi
    candidate=$(sed -n '1p' "$pid_file")
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        printf '%s\n' "run-local: $name is already running with PID $candidate" >&2
        return 1
    else
        state=$?
    fi
    if [ "$state" -eq 2 ]; then
        printf '%s\n' \
            "run-local: refusing startup because $pid_file names an unrelated live process" >&2
        return 1
    fi
    rm -f -- "$pid_file" "$identity_file"
}

stop_owned_child() {
    name=$1
    expected_jar=$2
    pid_file=$3
    identity_file=$4
    candidate=$5
    [ -n "$candidate" ] || return 0
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        :
    else
        state=$?
        if [ "$state" -eq 1 ]; then
            rm -f -- "$pid_file" "$identity_file"
            wait "$candidate" 2>/dev/null || true
            return 0
        fi
        printf '%s\n' \
            "run-local: refusing to signal unrelated process recorded for $name (PID $candidate)" >&2
        return 1
    fi
    kill -TERM "$candidate" 2>/dev/null || true
    attempts=0
    while pid_state "$candidate" "$expected_jar" "$identity_file" \
        && [ "$attempts" -lt 10 ]; do
        sleep 1
        attempts=$((attempts + 1))
    done
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        kill -KILL "$candidate" 2>/dev/null || true
        attempts=0
        while pid_state "$candidate" "$expected_jar" "$identity_file" \
            && [ "$attempts" -lt 10 ]; do
            sleep 1
            attempts=$((attempts + 1))
        done
    fi
    wait "$candidate" 2>/dev/null || true
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        printf '%s\n' "run-local: $name process $candidate is still running" >&2
        return 1
    else
        state=$?
    fi
    if [ "$state" -eq 2 ]; then
        printf '%s\n' \
            "run-local: PID $candidate was reused; refusing to signal the new process" >&2
        return 1
    fi
    rm -f -- "$pid_file" "$identity_file"
}

cleanup() {
    [ "$cleaned" -eq 0 ] || return 0
    cleaned=1
    trap - EXIT HUP INT TERM
    cleanup_status=0
    stop_owned_child assistant "$ASSISTANT_JAR" "$ASSISTANT_PID_FILE" \
        "$ASSISTANT_IDENTITY_FILE" "$assistant_pid" \
        || cleanup_status=1
    stop_owned_child countries "$COUNTRIES_JAR" "$COUNTRIES_PID_FILE" \
        "$COUNTRIES_IDENTITY_FILE" "$countries_pid" \
        || cleanup_status=1
    if [ "$compose_started" -eq 1 ]; then
        docker compose -f "$ROOT_DIR/compose.yaml" stop pgvector || cleanup_status=1
    fi
    return "$cleanup_status"
}

finish() {
    status=$1
    if ! cleanup && [ "$status" -eq 0 ]; then
        status=1
    fi
    exit "$status"
}

print_log_tail() {
    name=$1
    log_file=$2
    printf '%s\n' "run-local: last 50 lines from $name log:" >&2
    tail -n 50 "$log_file" >&2 || true
}

wait_for_service() {
    name=$1
    candidate=$2
    expected_jar=$3
    identity_file=$4
    health_url=$5
    log_file=$6
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        if pid_state "$candidate" "$expected_jar" "$identity_file"; then
            :
        else
            print_log_tail "$name" "$log_file"
            printf '%s\n' "run-local: $name exited before becoming healthy" >&2
            return 1
        fi
        if curl -fsS --max-time 2 "$health_url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        attempts=$((attempts + 1))
    done
    print_log_tail "$name" "$log_file"
    printf '%s\n' "run-local: $name did not become healthy within 60 seconds" >&2
    return 1
}

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'Usage: ./scripts/run-local.sh' >&2
    exit 2
fi

"$ROOT_DIR/scripts/preflight.sh"

umask 077
mkdir -p "$RUN_DIR" "$LOG_DIR"
remove_stale_pid_file countries "$COUNTRIES_JAR" "$COUNTRIES_PID_FILE" \
    "$COUNTRIES_IDENTITY_FILE" || exit 1
remove_stale_pid_file assistant "$ASSISTANT_JAR" "$ASSISTANT_PID_FILE" \
    "$ASSISTANT_IDENTITY_FILE" || exit 1

trap 'finish $?' EXIT
trap 'finish 130' INT
trap 'finish 143' HUP TERM

cd "$ROOT_DIR"
docker compose -f "$ROOT_DIR/compose.yaml" up -d pgvector
compose_started=1

attempts=0
while [ "$attempts" -lt 60 ]; do
    if docker compose -f "$ROOT_DIR/compose.yaml" exec -T pgvector \
        sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; then
        break
    fi
    sleep 1
    attempts=$((attempts + 1))
done
if [ "$attempts" -eq 60 ]; then
    docker compose -f "$ROOT_DIR/compose.yaml" logs --tail 50 pgvector >&2 || true
    printf '%s\n' 'run-local: pgvector did not become healthy within 60 seconds' >&2
    exit 1
fi

build_commit=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)
case "$build_commit" in
    *[!0-9a-fA-F]*|'')
        printf '%s\n' 'run-local: could not record the Git build commit' >&2
        exit 1
        ;;
esac
[ "${#build_commit}" -eq 40 ] || {
    printf '%s\n' 'run-local: could not record the Git build commit' >&2
    exit 1
}
if ! build_status=$(git -C "$ROOT_DIR" status \
    --porcelain --untracked-files=normal \
    -- . ':(exclude,literal)evaluation/answers.md' 2>/dev/null); then
    printf '%s\n' 'run-local: could not verify the Git worktree state' >&2
    exit 1
fi
if [ -n "$build_status" ]; then
    printf '%s\n' \
        'run-local: worktree must be clean except for evaluation/answers.md before building' >&2
    exit 1
fi

"$ROOT_DIR/mvnw" --batch-mode package

if [ ! -f "$COUNTRIES_JAR" ]; then
    printf '%s\n' "run-local: missing executable JAR $COUNTRIES_JAR" >&2
    exit 1
fi
if [ ! -f "$ASSISTANT_JAR" ]; then
    printf '%s\n' "run-local: missing executable JAR $ASSISTANT_JAR" >&2
    exit 1
fi

verified_commit=$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)
if ! verified_status=$(git -C "$ROOT_DIR" status \
    --porcelain --untracked-files=normal \
    -- . ':(exclude,literal)evaluation/answers.md' 2>/dev/null); then
    printf '%s\n' 'run-local: could not reverify the Git worktree state' >&2
    exit 1
fi
if [ "$verified_commit" != "$build_commit" ] || [ -n "$verified_status" ]; then
    printf '%s\n' \
        'run-local: source changed during the build; refusing an unverifiable launch' >&2
    exit 1
fi

countries_nonce=$(new_process_nonce) || {
    printf '%s\n' 'run-local: could not generate Countries process identity' >&2
    exit 1
}
java "-Dcdq.local.instance=$countries_nonce" -jar "$COUNTRIES_JAR" \
    >"$COUNTRIES_LOG" 2>&1 &
countries_pid=$!
write_process_identity \
    "$countries_pid" "$COUNTRIES_JAR" "$COUNTRIES_IDENTITY_FILE" "$countries_nonce"
printf '%s\n' "$countries_pid" > "$COUNTRIES_PID_FILE"
wait_for_service countries "$countries_pid" "$COUNTRIES_JAR" "$COUNTRIES_IDENTITY_FILE" \
    'http://127.0.0.1:8081/actuator/health' "$COUNTRIES_LOG"

assistant_nonce=$(new_process_nonce) || {
    printf '%s\n' 'run-local: could not generate assistant process identity' >&2
    exit 1
}
BUILD_COMMIT=$build_commit BUILD_WORKTREE_CLEAN=true \
    java "-Dcdq.local.instance=$assistant_nonce" -jar "$ASSISTANT_JAR" \
    >"$ASSISTANT_LOG" 2>&1 &
assistant_pid=$!
write_process_identity \
    "$assistant_pid" "$ASSISTANT_JAR" "$ASSISTANT_IDENTITY_FILE" "$assistant_nonce"
printf '%s\n' "$assistant_pid" > "$ASSISTANT_PID_FILE"
wait_for_service assistant "$assistant_pid" "$ASSISTANT_JAR" "$ASSISTANT_IDENTITY_FILE" \
    'http://127.0.0.1:8080/actuator/health' "$ASSISTANT_LOG"

printf '%s\n' 'Assistant ready at http://127.0.0.1:8080'
if wait "$assistant_pid"; then
    assistant_status=0
else
    assistant_status=$?
fi
assistant_pid=
rm -f -- "$ASSISTANT_PID_FILE" "$ASSISTANT_IDENTITY_FILE"
finish "$assistant_status"
