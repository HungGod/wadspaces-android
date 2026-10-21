#!/usr/bin/env bash
# What the app takes from the wadspaces repo beside it (WADSPACES_DIR,
# default ../wadspaces): the catalog and starters, the package graph
# (packages.json: each app's package and what packages need, for naming a
# version 4 wadspace's set), the goldens its core is checked against (the
# board's, design B's board's, wad-core's, the phone's planNew cases), the
# Builder's wallpapers and textures, and the account's Firebase settings
# (into the gitignored wadspaces.properties). SOURCE says which commit
# they're from.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
src=${WADSPACES_DIR:-$here/../wadspaces}
[ -d "$src/crates/wad-core" ] || { echo "no wadspaces repo at $src" >&2; exit 1; }
assets=$here/app/src/main/assets
fixtures=$here/core/src/test/resources/fixtures
mkdir -p "$assets/wallpapers" "$fixtures"
cp "$src/crates/wad-core/data/catalog.json" "$src/crates/wad-core/data/starters.json" "$assets/"
cp "$src/fixtures/board/goldens.json" "$fixtures/board-goldens.json"
cp "$src/fixtures/board/phone.json" "$fixtures/phone.json"
cp "$src/fixtures/core/goldens.json" "$fixtures/core-goldens.json"
cp "$src/fixtures/board/b.json" "$fixtures/b-goldens.json"
(cd "$src" && cargo xtask packages graph) > "$assets/packages.json"
cp "$assets/packages.json" "$fixtures/packages.json"
(cd "$src" && cargo run -q -p wad-wallpaper --example presets -- "$assets/wallpapers")
commit=$(git -C "$src" rev-parse --short HEAD)
dirty=$(git -C "$src" status --porcelain -- crates/wad-core crates/wad-board fixtures crates/wad-wallpaper xtask | head -1)
echo "${commit}${dirty:+ (with changes)}" > "$here/core/src/test/resources/fixtures/SOURCE"
cp "$here/core/src/test/resources/fixtures/SOURCE" "$assets/SOURCE"
toml=$src/host/secrets/firebase.toml
if [ -f "$toml" ]; then
  python3 -I - "$toml" "$here/wadspaces.properties" <<'PY'
import sys, tomllib
c = tomllib.load(open(sys.argv[1], "rb"))
c = c.get("firebase", c)
with open(sys.argv[2], "w") as f:
    f.write("# From wadspaces' host/secrets/firebase.toml (scripts/sync-wadspaces.sh)\n")
    for k, v in (("apiKey", "api_key"), ("projectId", "project_id"), ("authDomain", "auth_domain")):
        f.write(f"{k}={c.get(v, '')}\n")
PY
  echo "wadspaces.properties from $toml"
else
  echo "no $toml: wadspaces.properties not written" >&2
fi
echo "synced from wadspaces $(cat "$here/core/src/test/resources/fixtures/SOURCE")"
