#!/usr/bin/env bash
# Downloads the game files for the Android CI build from a public URL
# (Google Drive, HuggingFace or any direct link) and extracts them.
#
# The zip only needs to contain the game files (in any folder layout - they are
# found recursively):
#   default.xex
#   shader.arc
#   shader_lt.arc
#
# Everything else is fetched/built automatically by the CI workflow: submodules
# are initialized by git, ffmpeg for Android is built from source (XMA audio),
# and bundled Turnip drivers are optional (the app falls back to the system
# Vulkan driver without them).
#
# Usage: fetch_build_files.sh <url> <output-dir>
set -euo pipefail

url="${1:?usage: fetch_build_files.sh <url> <output-dir>}"
outdir="${2:?usage: fetch_build_files.sh <url> <output-dir>}"

mkdir -p "$outdir"
archive="$outdir/build_files.zip"

echo "::group::Downloading game files"
tmp="$outdir/.download.part"
rm -f "$tmp" "$archive"

if [[ "$url" == *"drive.google.com"* ]]; then
    # Google Drive: extract the file id, then do the confirm-token dance that
    # large files (>100MB) require instead of serving a virus-scan warning page.
    if [[ "$url" =~ [\?\&]id=([^&\ ]+) ]]; then
        id="${BASH_REMATCH[1]}"
    elif [[ "$url" =~ /file/d/([^/]+) ]]; then
        id="${BASH_REMATCH[1]}"
    else
        echo "ERROR: could not extract a file id from the Google Drive URL: $url" >&2
        exit 1
    fi

    cookie="$outdir/.gdrive_cookie"
    curl -fsSL -c "$cookie" "https://drive.google.com/uc?export=download&id=${id}" -o /dev/null || true
    confirm="$(grep -o 'confirm=[0-9A-Za-z_-]*' "$cookie" 2>/dev/null | head -1 | cut -d= -f2 || true)"

    if [ -n "$confirm" ]; then
        curl -fL -b "$cookie" -o "$tmp" \
            "https://drive.google.com/uc?export=download&confirm=${confirm}&id=${id}"
    else
        curl -fL -o "$tmp" "https://drive.google.com/uc?export=download&id=${id}"
    fi
else
    # Direct link (HuggingFace resolve/main, any static host, ...).
    curl -fL --retry 3 -o "$tmp" "$url"
fi

# A Google Drive virus-scan page can still come back as HTML; refuse it.
if head -c 512 "$tmp" | grep -qi '<html'; then
    echo "ERROR: the download returned an HTML page instead of the file. Check the URL." >&2
    rm -f "$tmp"
    exit 1
fi

mv "$tmp" "$archive"
echo "Downloaded $(du -h "$archive" | cut -f1): $archive"
echo "::endgroup::"

echo "::group::Extracting game files"
rm -rf "$outdir/extracted"
unzip -q -o "$archive" -d "$outdir/extracted"

game_dir="$outdir/game"
mkdir -p "$game_dir"
for f in default.xex shader.arc shader_lt.arc; do
    found="$(find "$outdir/extracted" -name "$f" -type f | head -1 || true)"
    if [ -z "$found" ]; then
        echo "ERROR: '$f' not found in the archive. The zip must contain the game's default.xex, shader.arc and shader_lt.arc." >&2
        exit 1
    fi
    cp -f "$found" "$game_dir/$f"
done
echo "Game files -> $game_dir"
echo "::endgroup::"

# Optional payloads: bundled Turnip Vulkan drivers and a DirectX 12 (vkd3d) runtime.
# They are looked up as folders anywhere in the archive (a zip usually nests the whole
# upload one level deep), then flattened into <outdir>/drivers and <outdir>/vkd3d, which
# is what the workflow copies into the APK.
echo "::group::Optional bundled libraries"
for folder in drivers vkd3d; do
    src="$(find "$outdir/extracted" -type d -name "$folder" | head -1 || true)"
    if [ -z "$src" ]; then
        echo "no $folder/ folder in the archive; skipping"
        continue
    fi

    mkdir -p "$outdir/$folder"
    find "$src" -maxdepth 1 -type f -name '*.so' -exec cp -f {} "$outdir/$folder/" \;
    count="$(ls -1 "$outdir/$folder" 2>/dev/null | wc -l | tr -d ' ')"
    echo "$folder: $count .so file(s) -> $outdir/$folder"
done
echo "::endgroup::"
