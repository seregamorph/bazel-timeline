#!/bin/sh
# Usage: bazel-timeline.sh <profile.json[.gz]> [out.html]
set -eu
dir="$(cd "$(dirname "$0")" && pwd)"
exec java "$dir/src/BazelTimelineReport.java" "$1" "$dir/src/report-template.html" "${2:-build-report.html}"
