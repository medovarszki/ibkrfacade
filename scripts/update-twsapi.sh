#!/usr/bin/env bash
# Downloads the official TWS API (Linux installer) from Interactive Brokers and installs its Java client jar into
# the vendored Maven repository under lib/. By using it you accept the TWS API license of Interactive Brokers.
#
#   scripts/update-twsapi.sh [stable|latest]
#
# Afterwards update <tws-api.version> in pom.xml and lib/README.md.
set -euo pipefail

CHANNEL="${1:-stable}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

URL="https://download2.interactivebrokers.com/installers/tws-api/${CHANNEL}/tws-api-${CHANNEL}-linux-x64.sh"
echo "Downloading $URL"
curl -fsSL -o "$WORK/installer.sh" "$URL"
sha256sum "$WORK/installer.sh"

# install4j installer, unattended mode
sh "$WORK/installer.sh" -q -dir "$WORK/twsapi" > "$WORK/install.log" 2>&1

JAVA_CLIENT="$WORK/twsapi/source/javaclient"
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$JAVA_CLIENT/pom.xml" | head -1)"
PROTOBUF="$(ls "$JAVA_CLIENT"/jars/protobuf-java-*.jar | sed 's:.*protobuf-java-\(.*\)\.jar:\1:')"
echo "TWS API version: $VERSION (protobuf-java $PROTOBUF), $(cat "$WORK/twsapi/API_VersionNum.txt")"

(cd "$JAVA_CLIENT" && zip -qr "$WORK/sources.jar" com -i '*.java')
cat > "$WORK/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.interactivebrokers</groupId>
    <artifactId>tws-api</artifactId>
    <version>${VERSION}</version>
    <name>Interactive Brokers TWS API (Java client)</name>
    <description>Repackaged unmodified from the official IBKR "${CHANNEL}" installer</description>
    <url>https://interactivebrokers.github.io/</url>
    <licenses>
        <license>
            <name>GNU General Public License v3.0</name>
            <url>https://www.gnu.org/licenses/gpl-3.0.txt</url>
        </license>
    </licenses>
    <dependencies>
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java</artifactId>
            <version>${PROTOBUF}</version>
        </dependency>
    </dependencies>
</project>
POM

mvn -B -q install:install-file -Dfile="$JAVA_CLIENT/TwsApi.jar" -Dsources="$WORK/sources.jar" \
    -DpomFile="$WORK/pom.xml" -DlocalRepositoryPath="$ROOT/lib"
TARGET="$ROOT/lib/com/interactivebrokers/tws-api/$VERSION"
rm -f "$TARGET/_remote.repositories" "$ROOT/lib/com/interactivebrokers/tws-api/maven-metadata-local.xml"
for f in "$TARGET"/*.jar "$TARGET"/*.pom; do sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"; done
cp "$WORK/twsapi/LICENSE" "$ROOT/lib/TWS-API-LICENSE.txt"
sha256sum "$TARGET/tws-api-$VERSION.jar"
echo "Installed to $TARGET"
