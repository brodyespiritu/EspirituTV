#!/bin/sh
# Publishes an EspirituTV release that TVs running the launcher pick up over the air (README › Updates).
#   ./release.sh 1.1 "What changed"            bump to 1.1, build, upload the APK + latest.json to GitHub Releases
#   ./release.sh 1.1 "What changed" --local    the same, but stop after writing dist/ (no GitHub needed)
# versionCode goes up by one on every run; a TV updates when the feed's versionCode beats the one it runs.
set -e
cd "$(dirname "$0")"

VERSION=""; NOTES=""; LOCAL=""
for a in "$@"; do
  case "$a" in
    --local) LOCAL=1 ;;
    -h|--help) VERSION=""; break ;;
    *) if [ -z "$VERSION" ]; then VERSION="$a"; else NOTES="$a"; fi ;;
  esac
done
if [ -z "$VERSION" ]; then
  echo "Usage: $0 <versionName> [\"release notes\"] [--local]"
  echo "  e.g. $0 1.1 \"Over-the-air updates\""
  exit 1
fi
case "$VERSION" in *[!0-9.]*) echo "The version should look like 1.1 or 1.2.3"; exit 1 ;; esac

GRADLE=app/build.gradle.kts
UPDATER=app/src/main/java/com/espiritu/tv/Updater.kt
REPO="$(sed -n 's/.*const val REPO = "\([^"]*\)".*/\1/p' "$UPDATER")"
[ -n "$REPO" ] || { echo "Couldn't read REPO from $UPDATER"; exit 1; }
CODE="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE")"
[ -n "$CODE" ] || { echo "Couldn't read versionCode from $GRADLE"; exit 1; }
NEW=$((CODE + 1))

# Bump, and undo the bump if the build fails, so a broken build doesn't burn a version.
cp "$GRADLE" "$GRADLE.bak"
trap 'rc=$?; if [ $rc -ne 0 ] && [ -f "$GRADLE.bak" ]; then mv "$GRADLE.bak" "$GRADLE"; echo "Failed; version bump undone."; fi; exit $rc' EXIT
sed -i '' -e "s/^\([[:space:]]*\)versionCode = .*/\1versionCode = $NEW/" \
          -e "s/^\([[:space:]]*\)versionName = .*/\1versionName = \"$VERSION\"/" "$GRADLE"
echo "EspirituTV $VERSION (versionCode $NEW)"

echo "Building…"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew -q assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/EspirituTV.apk
cp dist/EspirituTV.apk EspirituTV.apk   # what install.sh pushes over adb
SHA="$(shasum -a 256 dist/EspirituTV.apk | cut -d' ' -f1)"
APK_URL="https://github.com/$REPO/releases/download/v$VERSION/EspirituTV.apk"
python3 - "$NEW" "$VERSION" "$APK_URL" "$SHA" "$NOTES" > dist/latest.json <<'PY'
import json, sys
code, name, url, sha, notes = sys.argv[1:6]
print(json.dumps({"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha, "notes": notes}, indent=2))
PY
rm -f "$GRADLE.bak"
echo "Wrote dist/EspirituTV.apk (sha256 $SHA) and dist/latest.json"
if [ -n "$LOCAL" ]; then echo "Not uploaded (--local)."; exit 0; fi

command -v gh >/dev/null || { echo "GitHub CLI missing: brew install gh && gh auth login"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Sign in to GitHub first: gh auth login"; exit 1; }
if ! gh repo view "$REPO" >/dev/null 2>&1; then
  echo "Repository $REPO doesn't exist (or isn't yours). Create it with the project pushed, since a release needs a commit:"
  echo "  git init && git add -A && git commit -m 'EspirituTV' && gh repo create $REPO --public --source=. --push"
  echo "Or change REPO in $UPDATER."
  exit 1
fi
if ! gh api "repos/$REPO/commits?per_page=1" >/dev/null 2>&1; then
  echo "Repository $REPO is empty; push the project first (a release needs a commit):"
  echo "  git init && git add -A && git commit -m 'EspirituTV' && git remote add origin https://github.com/$REPO.git && git push -u origin HEAD"
  exit 1
fi
TAG="v$VERSION"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" dist/EspirituTV.apk dist/latest.json -R "$REPO" --clobber
else
  gh release create "$TAG" dist/EspirituTV.apk dist/latest.json -R "$REPO" --title "EspirituTV $VERSION" --notes "${NOTES:-EspirituTV $VERSION}" --latest
fi
echo "Published https://github.com/$REPO/releases/tag/$TAG — TVs see it within a day, or right away from Settings → Check for updates."
