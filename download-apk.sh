#!/bin/bash
# JarvisMobile APK Download Script
# Downloads the latest debug APK from GitHub Actions
#
# Usage:
#   1. Make sure 'gh' CLI is installed: apt install gh
#   2. Authenticate: gh auth login
#   3. Run: bash download-apk.sh

set -e

REPOOWNER=$(git remote get-url origin 2>/dev/null | sed 's|.*github.com[:/]||;s|\.git$||;s|/.*||')
REPONAME=$(git remote get-url origin 2>/dev/null | sed 's|.*github.com[:/][^/]*/||;s|\.git$||')

if [ -z "$REPOOWNER" ] || [ -z "$REPONAME" ]; then
    echo "ERROR: Could not detect GitHub repo from git remote."
    echo "Usage: Set GIT remote first, or edit REPOOWNER/REPONAME in this script."
    exit 1
fi

echo "Repository: $REPOOWNER/$REPONAME"
echo "Fetching latest workflow run..."

# Get the latest successful workflow run
RUN_ID=$(gh run list --workflow=build.yml --status=success --limit=1 --json databaseId --jq '.[0].databaseId')

if [ -z "$RUN_ID" ]; then
    echo "ERROR: No successful build found."
    echo "Make sure you pushed code and the workflow completed."
    exit 1
fi

echo "Download APK from run $RUN_ID..."
mkdir -p /storage/emulated/0/FreebuffProject
gh run download "$RUN_ID" -n jarvismobile-debug -D /storage/emulated/0/FreebuffProject

APK_PATH="/storage/emulated/0/FreebuffProject/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "SUCCESS: APK downloaded to $APK_PATH"
    echo "Size: $(ls -lh "$APK_PATH" | awk '{print $5}')"
    echo ""
    echo "To install:"
    echo "  PATH=\$PATH:/data/data/com.termux/files/usr/bin termux-open $APK_PATH"
else
    echo "ERROR: APK file not found after download."
    exit 1
fi
