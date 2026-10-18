#!/usr/bin/env sh
set -eu
if ! command -v java >/dev/null 2>&1; then echo 'Java 21+ is required.' >&2; exit 1; fi
if ! command -v mvn >/dev/null 2>&1; then echo 'Maven 3.9+ is required for this script. No database or search server is required in local mode.' >&2; exit 1; fi
java -version
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--app.search.mode=memory
