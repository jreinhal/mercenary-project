#!/bin/sh

#
# Gradle start up script for POSIX systems
#

# Resolve links and canonicalize the path
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

# Add default JVM options here
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
