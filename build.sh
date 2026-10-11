#!/usr/bin/env bash
# Builds build/tcirviz.jar against a TornadoVM SDK with the TC compiler (TORNADOVM_HOME), or against
# TCIRVIZ_CP when set. No other dependency.
set -euo pipefail
cd "$(dirname "$0")"
CP="${TCIRVIZ_CP:-${TORNADOVM_HOME:?set TORNADOVM_HOME to a TornadoVM SDK with TC}/share/java/tornado/*}"
rm -rf build && mkdir -p build/classes
javac --release 21 -proc:none -Xlint:all,-serial,-processing -Werror -g -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
VERSION=$(git describe --tags --always --dirty 2>/dev/null || echo dev)
printf 'Main-Class: io.github.mikepapadim.tcirviz.TcIrViz\nImplementation-Title: tcirviz\nImplementation-Version: %s\n' "$VERSION" > build/MANIFEST.MF
jar --create --file build/tcirviz.jar --manifest build/MANIFEST.MF -C build/classes .
echo "built build/tcirviz.jar ($VERSION)"
