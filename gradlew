#!/bin/sh
# Gradle wrapper that uses the pre-installed Gradle 8.6 distribution.
# Falls back to the GRADLE_HOME environment variable if set.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Use JAVA_HOME from common locations if not already set
if [ -z "$JAVA_HOME" ]; then
    for candidate in "$HOME/jdk" /usr/lib/jvm/java-17-openjdk-amd64 \
                      /usr/lib/jvm/java-17 /usr/local/lib/jvm/java-17; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi

if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME is not set." >&2
    exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# Locate Gradle
GRADLE_BIN=""
if [ -n "$GRADLE_HOME" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
    GRADLE_BIN="$GRADLE_HOME/bin/gradle"
elif [ -x "$HOME/gradle-8.6/bin/gradle" ]; then
    GRADLE_BIN="$HOME/gradle-8.6/bin/gradle"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
fi

if [ -z "$GRADLE_BIN" ]; then
    echo "ERROR: Gradle not found. Set GRADLE_HOME or install Gradle 8.6 to ~/gradle-8.6." >&2
    exit 1
fi

exec "$GRADLE_BIN" "$@"
