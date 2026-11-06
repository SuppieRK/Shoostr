#!/usr/bin/env bash
set -euo pipefail

destination=${1:?Supply the benchmark tools directory}
mkdir -p "$destination"
archive=zrk-3.0.0-x86_64-linux.tar.gz
curl --fail --location --retry 3 "https://github.com/zoxy-io/zrk/releases/download/v3.0.0/$archive" \
  --output "$destination/$archive"
printf '%s  %s\n' 33114a9ff44933767551c3fb71940d8861ce5f0502c04ffa1b734b98f98b1081 \
  "$destination/$archive" | sha256sum --check
tar -xzf "$destination/$archive" -C "$destination" zrk
"$destination/zrk" --version
