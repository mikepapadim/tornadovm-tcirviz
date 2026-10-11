#!/usr/bin/env bash
# Builds tcirviz and installs it into the TornadoVM SDK, where `tornado --tcirviz` finds it.
set -euo pipefail
cd "$(dirname "$0")"
: "${TORNADOVM_HOME:?set TORNADOVM_HOME to a TornadoVM SDK with the TC compiler (source setvars.sh)}"
./build.sh
mkdir -p "$TORNADOVM_HOME/share/java/tcirviz"
cp build/tcirviz.jar "$TORNADOVM_HOME/share/java/tcirviz/tcirviz.jar"
echo "installed: $TORNADOVM_HOME/share/java/tcirviz/tcirviz.jar  ->  tornado --tcirviz [File.java]"
