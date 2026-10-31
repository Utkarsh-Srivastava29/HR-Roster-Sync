#!/usr/bin/env sh
# Minimal Maven wrapper: downloads Maven 3.9.9 once into ~/.m2/wrapper and runs it.
# Needs only Java 21 (and curl or wget, and unzip).
set -e
MVN_VERSION=3.9.9
DIR="$HOME/.m2/wrapper"
MVN_HOME="$DIR/apache-maven-$MVN_VERSION"
if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  echo "Downloading Maven $MVN_VERSION ..."
  mkdir -p "$DIR"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VERSION/apache-maven-$MVN_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$DIR/maven.zip"; else wget -q "$URL" -O "$DIR/maven.zip"; fi
  unzip -q -o "$DIR/maven.zip" -d "$DIR"
  rm -f "$DIR/maven.zip"
fi
exec "$MVN_HOME/bin/mvn" "$@"
