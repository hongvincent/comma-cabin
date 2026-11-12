#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" app/src/main/java/app/commacabin/RestRules.java checks/RestRulesCheck.java
java -cp "$tmp" app.commacabin.RestRulesCheck
