#!/bin/sh
set -u

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
RUN_DIR="$ROOT_DIR/.local/run"
COUNTRIES_JAR="$ROOT_DIR/countries-mcp-server/target/countries-mcp-server-0.0.1-SNAPSHOT.jar"
ASSISTANT_JAR="$ROOT_DIR/assistant-app/target/assistant-app-0.0.1-SNAPSHOT.jar"
COUNTRIES_PID_FILE="$RUN_DIR/countries.pid"
ASSISTANT_PID_FILE="$RUN_DIR/assistant.pid"
COUNTRIES_IDENTITY_FILE="$RUN_DIR/countries.identity"
ASSISTANT_IDENTITY_FILE="$RUN_DIR/assistant.identity"

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

stop_owned_java() {
    name=$1
    expected_jar=$2
    pid_file=$3
    identity_file=$4
    [ -f "$pid_file" ] || return 0
    candidate=$(sed -n '1p' "$pid_file")
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        :
    else
        state=$?
        case "$state" in
            1|3)
                printf '%s\n' "stop-local: removed stale $name PID file"
                rm -f -- "$pid_file" "$identity_file"
                return 0
                ;;
            2)
                printf '%s\n' \
                    "stop-local: refusing to signal unrelated process in $pid_file (PID $candidate)" >&2
                return 1
                ;;
        esac
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
    if pid_state "$candidate" "$expected_jar" "$identity_file"; then
        printf '%s\n' "stop-local: $name process $candidate is still running" >&2
        return 1
    else
        state=$?
    fi
    if [ "$state" -eq 2 ]; then
        printf '%s\n' \
            "stop-local: PID $candidate was reused; refusing to signal the new process" >&2
        return 1
    fi
    rm -f -- "$pid_file" "$identity_file"
    printf '%s\n' "stop-local: stopped $name"
}

if [ "$#" -ne 0 ]; then
    printf '%s\n' 'Usage: ./scripts/stop-local.sh' >&2
    exit 2
fi

status=0
stop_owned_java countries "$COUNTRIES_JAR" "$COUNTRIES_PID_FILE" \
    "$COUNTRIES_IDENTITY_FILE" || status=1
stop_owned_java assistant "$ASSISTANT_JAR" "$ASSISTANT_PID_FILE" \
    "$ASSISTANT_IDENTITY_FILE" || status=1
if ! docker compose -f "$ROOT_DIR/compose.yaml" down; then
    printf '%s\n' 'stop-local: Docker Compose shutdown failed' >&2
    status=1
fi
exit "$status"
