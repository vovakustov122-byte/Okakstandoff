#!/bin/sh
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "${0%/*}" && pwd -P)
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
