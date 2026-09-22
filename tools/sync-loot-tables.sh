#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${1:-${RPP_SERVER_DIR:-C:/Users/NotNullBeyond/Desktop/Minecraft Server Testing}}"
SEVENZIP="${SEVENZIP:-7z.exe}"
command -v "$SEVENZIP" >/dev/null 2>&1 || SEVENZIP="C:/Program Files/7-Zip/7z.exe"

MC_VERSION="$(grep -o '<mc.version>[^<]*' "$REPO/pom.xml" | head -1 | cut -d'>' -f2)"
[ -n "$MC_VERSION" ] || { echo "FAIL: mc.version not found in pom.xml"; exit 1; }

BUNDLER="$(ls "$SERVER_DIR/cache/"mojang_*.jar 2>/dev/null | head -1 || true)"
[ -n "$BUNDLER" ] || { echo "FAIL: no cache/mojang_*.jar under '$SERVER_DIR', pass the server dir as argument 1"; exit 1; }
echo "Bundler: $BUNDLER"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

LIST_PATH="$("$SEVENZIP" e "$BUNDLER" 'META-INF\versions.list' -so | tail -1 | cut -f3)"
[ -n "$LIST_PATH" ] || { echo "FAIL: versions.list in the bundler is empty"; exit 1; }
echo "Nested server jar: $LIST_PATH"

"$SEVENZIP" e "$BUNDLER" -y -o"$WORK" "META-INF\\versions\\${LIST_PATH//\//\\}" >/dev/null
VANILLA="$WORK/$(basename "$LIST_PATH")"

EXTRACTED_NAME="$("$SEVENZIP" e "$VANILLA" version.json -so | grep '"name"' | head -1 | cut -d'"' -f4)"
[ "$EXTRACTED_NAME" = "$MC_VERSION" ] || { echo "FAIL: vanilla jar is $EXTRACTED_NAME but pom mc.version is $MC_VERSION, bump the pom or point the script at the matching server"; exit 1; }
echo "Version check: $EXTRACTED_NAME == pom $MC_VERSION"

FILTERS=""
for CROP in wheat carrots potatoes beetroots nether_wart cocoa; do
  FILTERS="$FILTERS data\\minecraft\\loot_table\\blocks\\$CROP.json"
done
"$SEVENZIP" e "$VANILLA" -y -o"$REPO/loot_tables" $FILTERS 'version.json' >/dev/null

for CROP in wheat carrots potatoes beetroots nether_wart cocoa; do
  [ -s "$REPO/loot_tables/$CROP.json" ] || { echo "FAIL: $CROP.json missing or empty after extraction"; exit 1; }
done
grep -q 'apply_bonus' "$REPO/loot_tables/wheat.json" || { echo "FAIL: wheat.json lost its apply_bonus modifier, the table format changed, a human must look"; exit 1; }
echo "Sanity: 6 tables + version.json extracted, wheat.json carries apply_bonus"

cd "$REPO"
if git diff --quiet -- loot_tables/; then
  echo "OK: loot_tables/ already matches MC $MC_VERSION, nothing to do"
else
  echo "DIFF DETECTED in loot_tables/:"
  git diff --stat -- loot_tables/
  echo "NEXT: re-read the changed JSONs, update VanillaCropDrops AND VanillaCropDropsTest in the same change, run mvn package, commit all together"
fi
