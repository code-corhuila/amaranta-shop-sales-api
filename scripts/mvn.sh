#!/usr/bin/env bash
# Maven without installing Maven or Java: the whole build runs in the official image, so it behaves
# on your machine as it does in CI. Arguments go straight to Maven:
#
#   bash scripts/mvn.sh verify              # build and test every module
#   bash scripts/mvn.sh -pl sales-core test
#
# The Maven repository is kept in a Docker volume, so only the first run downloads the world.
# MVN_DOCKER_ARGS adds docker options, for example a network to reach a database.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows must not rewrite the container paths below

# shellcheck disable=SC2086  # MVN_DOCKER_ARGS is a list of options on purpose
exec docker run --rm \
  -v "$PWD:/src" -v amaranta-sales-m2:/root/.m2 -w /src \
  ${MVN_DOCKER_ARGS:-} \
  maven:3.9-eclipse-temurin-21 mvn -B "$@"
