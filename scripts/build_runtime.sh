#!/bin/bash
set -e
set -o pipefail

# =============================================================================
# n8n Android Runtime Builder (Asset-Based Strategy)
# =============================================================================
# Purpose: Builds a self-contained runtime archive for Android.
# Strategy: 
#   1. Fetch Termux binaries (Node.js, Dependencies).
#   2. Install n8n core via npm.
#   3. FLATTEN & PATCH: Rename libs (libfoo.so.1 -> libfoo.so) & fix DT_NEEDED.
#   4. Package into 'core_runtime.tar.gz' inside 'app/src/main/assets'.
# =============================================================================

# 1. Configuration & Paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$SCRIPT_DIR/assets"
BUILD_WORK_DIR="$SCRIPT_DIR/build_work"
OUTPUT_ASSETS_DIR="$ROOT_DIR/src/main/assets"
OUTPUT_RUNTIME_DIR="$BUILD_WORK_DIR/runtime"
TARGET_TARBALL="$OUTPUT_ASSETS_DIR/core_runtime.n8n"

ARCH="aarch64"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# Core Dependencies (Termux Package Names)
# Explicitly listing all needed libs to avoid missing transitive deps if possible
PACKAGES=("nodejs-lts" "libandroid-support" "libsqlite" "zlib" "c-ares" "libuv" "openssl" "libnghttp2" "libicu" "brotli" "libc++")

# 2. Cleanup & Setup
echo ">>> Initializing Build Environment..."
rm -rf "$BUILD_WORK_DIR"
mkdir -p "$BUILD_WORK_DIR"
mkdir -p "$OUTPUT_RUNTIME_DIR/bin"
mkdir -p "$OUTPUT_RUNTIME_DIR/lib"
mkdir -p "$OUTPUT_RUNTIME_DIR/lib/node_modules"
mkdir -p "$OUTPUT_ASSETS_DIR"

cd "$BUILD_WORK_DIR" || exit 1

# 3. Helper Functions
check_and_download() {
    local pkg_name=$1
    echo ">>> Resolving $pkg_name..."
    local filename=$(awk -v pkg="$pkg_name" '
        /^Package: / { if ($2 == pkg) { inside=1 } else { inside=0 } }
        inside && /^Filename: / { print $2; exit }
    ' Packages)
    
    if [ -z "$filename" ]; then
        echo "Error: Could not find package $pkg_name"
        exit 1
    fi
    
    wget -q "$TERMUX_REPO/$filename" -O "${pkg_name}.deb"
}

# 4. Fetch Packages
echo ">>> Fetching Termux Packages Index..."
wget -q "$TERMUX_REPO/dists/stable/main/binary-$ARCH/Packages.gz" -O Packages.gz
gunzip Packages.gz

echo ">>> Downloading Dependencies..."
for pkg in "${PACKAGES[@]}"; do
    check_and_download "$pkg"
done

# 5. Extract Binaries
echo ">>> Extracting Termux Binaries..."
for pkg in "${PACKAGES[@]}"; do
    ar x "${pkg}.deb" data.tar.xz
    # Extract only to temp first to sort files
    mkdir -p temp_extract
    tar -xf data.tar.xz -C temp_extract
    
    # Move relevant files to Runtime Structure
    # Termux layout: data/data/com.termux/files/usr/bin -> runtime/bin
    cp -r temp_extract/data/data/com.termux/files/usr/bin/* "$OUTPUT_RUNTIME_DIR/bin/" 2>/dev/null || true
    # Termux layout: data/data/com.termux/files/usr/lib -> runtime/lib
    cp -r temp_extract/data/data/com.termux/files/usr/lib/* "$OUTPUT_RUNTIME_DIR/lib/" 2>/dev/null || true
    
    rm -rf temp_extract data.tar.xz
done

# 6. Install n8n Core
echo ">>> Installing n8n Core..."
N8N_PATH="$OUTPUT_RUNTIME_DIR/lib/node_modules/n8n"
mkdir -p "$N8N_PATH"

# Fetch latest n8n
N8N_VERSION=$(npm view n8n version 2>/dev/null || echo "latest")
N8N_TARBALL=$(npm view n8n@$N8N_VERSION dist.tarball)
wget -q "$N8N_TARBALL" -O n8n.tgz
tar -xf n8n.tgz -C "$N8N_PATH" --strip-components=1
rm n8n.tgz

# Install Production Dependencies
cd "$N8N_PATH"
# Explicitly adding sqlite3 to ensure we can patch its native binding later
npm install --omit=dev --omit=optional --ignore-scripts --force
npm install sqlite3 --save --ignore-scripts --force

# Inject Pre-compiled SQLite if available
# (The user earlier mentioned node_sqlite3-android-arm64.node is in scripts/assets)
LOCAL_SQLITE="$ASSETS_DIR/node_sqlite3-android-arm64.node"
if [ -f "$LOCAL_SQLITE" ]; then
    echo ">>> Injecting Custom SQLite Binary..."
    SQLITE_DIR="$N8N_PATH/node_modules/sqlite3/lib/binding"
    mkdir -p "$SQLITE_DIR/napi-v6-android-arm64"
    mkdir -p "$SQLITE_DIR/node-v137-android-arm64" # For safety
    cp "$LOCAL_SQLITE" "$SQLITE_DIR/napi-v6-android-arm64/node_sqlite3.node"
    cp "$LOCAL_SQLITE" "$SQLITE_DIR/node-v137-android-arm64/node_sqlite3.node"
else
    echo "WARNING: Pre-compiled SQLite binary not found at $LOCAL_SQLITE"
fi

# 7. TWO-PHASE RECURSIVE PATCHING STRATEGY
echo ">>> Starting Two-Phase Library Patching..."

if ! command -v patchelf &> /dev/null; then
    echo "CRITICAL ERROR: patchelf is required but not installed."
    exit 1
fi

# Clean up symlinks first? No, we might rename real files.
# Let's find all shared objects in lib/
cd "$OUTPUT_RUNTIME_DIR/lib" || exit 1

# --- Phase 1: Mapping & Flattening ---
declare -A LIB_MAP

echo "Phase 1: Flattening Libraries..."
# Find all files (not symlinks) that look like shared libs
# Note: In Termux packages, sometimes .so is a symlink to .so.1. 
# We want to keep the ACTUAL file and name it .so.

# Standardize: Find all .so* files.
# If it's a symlink, resolve it. If it's a file, rename it.
# We iterate over all files starting with lib and containing .so
for f in lib*.so*; do
    if [ ! -e "$f" ]; then continue; fi # Skip if glob failed
    
    # If it's a symlink, check target.
    if [ -L "$f" ]; then
        TARGET=$(readlink -f "$f")
        rm "$f" # Remove the link/file
        # If we pushed the real file previously, it might be gone? 
        # Strategy: Copy everything to a 'staging' naming convention.
        continue
    fi
done

# Re-extract to ensure we have raw files? No, let's just process what we have.
# Proper Flattening Strategy:
# 1. Identify "Real" files (ELF binaries).
# 2. Rename them to clean name (libssl.so.3 -> libssl.so).
# 3. Record the mapping.

# Let's simple-case this: iterating files.
find . -maxdepth 1 -type f -name "lib*.so*" | while read -r file; do
    BASENAME=$(basename "$file")
    
    # Check if header indicates ELF
    if ! readelf -h "$file" >/dev/null 2>&1; then
        continue # Skip text files/scripts
    fi
    
    # Determine Clean Name: strip all .digits suffixes
    # libssl.so.3 -> libssl.so
    # libssl.so.3.0 -> libssl.so
    CLEAN_NAME=$(echo "$BASENAME" | sed -E 's/(\.so)(\.[0-9]+)+$/\1/')
    
    if [ "$BASENAME" != "$CLEAN_NAME" ]; then
        echo "  [Rename] $BASENAME -> $CLEAN_NAME"
        mv "$BASENAME" "$CLEAN_NAME"
        # Record mapping (append to a list file, using pipe to avoid subshell var loss)
        # Record mapping (append to a list file, using pipe to avoid subshell var loss)
        echo "$BASENAME|$CLEAN_NAME" >> "$BUILD_WORK_DIR/lib_mapping.txt"

        # EXTRA: Map Major Version (SONAME) as well
        # Example: libz.so.1.3.1 -> libz.so.1
        # If node depends on libz.so.1, we need to map libz.so.1 -> libz.so
        MAJOR_NAME=$(echo "$BASENAME" | grep -oE '^lib.*\.so\.[0-9]+' | head -n1)
        if [ -n "$MAJOR_NAME" ] && [ "$MAJOR_NAME" != "$BASENAME" ] && [ "$MAJOR_NAME" != "$CLEAN_NAME" ]; then
             echo "  [Map Extra] $MAJOR_NAME -> $CLEAN_NAME"
             echo "$MAJOR_NAME|$CLEAN_NAME" >> "$BUILD_WORK_DIR/lib_mapping.txt"
        fi
    else
        # Even if already clean, we may need to patch SONAME
        echo "$BASENAME|$BASENAME" >> "$BUILD_WORK_DIR/lib_mapping.txt"
    fi
    
    # Update SONAME
    patchelf --set-soname "$CLEAN_NAME" "$CLEAN_NAME"
done

# --- Phase 2: Global Recursive Patching ---
echo "Phase 2: Global Recursive Patching..."

# Build the patchelf arguments for replacing needed libraries
PATCH_ARGS=""
if [ -f "$BUILD_WORK_DIR/lib_mapping.txt" ]; then
    while IFS='|' read -r old_name clean_name; do
        if [ "$old_name" != "$clean_name" ]; then
            # Add --replace-needed argument
            PATCH_ARGS="$PATCH_ARGS --replace-needed $old_name $clean_name"
        fi
    done < "$BUILD_WORK_DIR/lib_mapping.txt"
fi

echo "  Applying mappings: $PATCH_ARGS"

# Function to patch an ELF file
patch_elf_file() {
    local target="$1"
    # echo "  Patching: $target"
    patchelf $PATCH_ARGS "$target" 2>/dev/null || echo "    Warning: patch failed for $target"
    
    # Also set RPATH to $ORIGIN for safety (though we use LD_LIBRARY_PATH mainly)
    # For .node modules deep in the tree, we need them to find libs in runtime/lib.
    # We can use a relative RPATH or absolute. 
    # Since we extract to a fixed location relative to filesDir, relative RPATH is best.
    # Using $ORIGIN allows it to find neighbor libs.
    # For node_modules, we rely on LD_LIBRARY_PATH injected by Kotlin/Shell.
    # So clearing RPATH or setting to $ORIGIN is fine.
    # patchelf --set-rpath '$ORIGIN' "$target"
}

# 1. Patch Shared Libraries in lib/
find "$OUTPUT_RUNTIME_DIR/lib" -maxdepth 1 -type f -name "*.so" | while read -r lib; do
    patch_elf_file "$lib"
done

# 2. Patch Node Binary
NODE_BIN="$OUTPUT_RUNTIME_DIR/bin/node"
if [ -f "$NODE_BIN" ]; then
    echo "  Patching Node Executable..."
    # Set Interpreter to Android System Linker
    patchelf --set-interpreter /system/bin/linker64 "$NODE_BIN"
    # Apply dependency replacements
    patch_elf_file "$NODE_BIN"
else
    echo "ERROR: Node binary missing!"
    exit 1
fi

# 3. Patch Native Modules (.node) recursively
echo "  Patching Native Modules (.node)..."
find "$OUTPUT_RUNTIME_DIR" -name "*.node" | while read -r node_mod; do
    echo "    Scanning: $(basename "$node_mod")"
    patch_elf_file "$node_mod"
done

# 8. Bootstrap Script
echo ">>> Creating Bootstrap Script..."
cat <<'EOS' > "$OUTPUT_RUNTIME_DIR/bin/n8n-start.sh"
#!/system/bin/sh
# Path Agnostic Launcher
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Environment Setup
export HOME=$N8N_USER_FOLDER
export PWD=$N8N_USER_FOLDER
export TMPDIR=${TMPDIR:-$N8N_USER_FOLDER/cache}

# Critical: Library Path (Points to internal lib/)
export LD_LIBRARY_PATH="$RUNTIME_ROOT/lib:$LD_LIBRARY_PATH"

# Node Config
export NODE_OPTIONS="--max-old-space-size=512"
export N8N_LOG_LEVEL=info

# Launch n8n
# Note: We use the patched node binary from our runtime
exec "$RUNTIME_ROOT/bin/node" "$RUNTIME_ROOT/lib/node_modules/n8n/bin/n8n" start
EOS
chmod +x "$OUTPUT_RUNTIME_DIR/bin/n8n-start.sh"
chmod +x "$NODE_BIN"

# 9. Create Archive
echo ">>> Archiving core_runtime.n8n..."
cd "$OUTPUT_RUNTIME_DIR"
# Archive everything in current dir (bin, lib, node_modules)
tar -czf "$TARGET_TARBALL" .

echo ">>> Build Complete!"
echo "    Output: $TARGET_TARBALL"
echo "    Size: $(du -sh "$TARGET_TARBALL" | awk '{print $1}')"

# Verification
echo ">>> Verifying Archive Content..."
tar -tf "$TARGET_TARBALL" | head -n 5

