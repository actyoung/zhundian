#!/bin/sh
set -eu
app_home=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin=java
fi
exec "$java_bin" -Xmx64m -Xms64m -cp "$app_home/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
