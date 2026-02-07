#!/bin/bash
# =============================================================================
# n8n Android Runtime Builder
# =============================================================================
# Purpose: Builds a self-contained runtime archive for Android.
#
# Strategy:
#   1. Fetch Termux binaries (Node.js, dependencies)
#   2. Install n8n core via npm
#   3. FLATTEN & PATCH: Rename libs (libfoo.so.1 → libfoo.so) & fix DT_NEEDED
#   4. Package into 'core_runtime.n8n' inside 'app/src/main/assets'
#
# Usage: ./build_runtime.sh [--clean] [--skip-n8n]
#
# Options:
#   --clean     Force clean rebuild (delete build_work)
#   --skip-n8n  Skip npm install (use cached n8n)
# =============================================================================
set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
LIB_DIR="$SCRIPT_DIR/lib"

# Paths
ASSETS_DIR="$SCRIPT_DIR/assets"
BUILD_WORK_DIR="$SCRIPT_DIR/build_work"
OUTPUT_ASSETS_DIR="$ROOT_DIR/src/main/assets"
OUTPUT_RUNTIME_DIR="$BUILD_WORK_DIR/runtime"
TARGET_TARBALL="$OUTPUT_ASSETS_DIR/core_runtime.n8n"
MAPPING_FILE="$BUILD_WORK_DIR/lib_mapping.txt"

# Target architecture
ARCH="aarch64"

# Termux packages to fetch
PACKAGES=(
    "nodejs-lts"
    "libandroid-support"
    "libsqlite"
    "zlib"
    "c-ares"
    "libuv"
    "openssl"
    "libnghttp2"
    "libicu"
    "brotli"
    "libc++"
)

# -----------------------------------------------------------------------------
# Source Library Functions
# -----------------------------------------------------------------------------
# shellcheck source=lib/logging.sh
source "$LIB_DIR/logging.sh"
# shellcheck source=lib/termux_packages.sh
source "$LIB_DIR/termux_packages.sh"
# shellcheck source=lib/native_tooling.sh
source "$LIB_DIR/native_tooling.sh"

# -----------------------------------------------------------------------------
# Argument Parsing
# -----------------------------------------------------------------------------
DO_CLEAN=false
SKIP_N8N=false

for arg in "$@"; do
    case $arg in
        --clean) DO_CLEAN=true ;;
        --skip-n8n) SKIP_N8N=true ;;
    esac
done

# -----------------------------------------------------------------------------
# Main Build Process
# -----------------------------------------------------------------------------
log_header "n8n Android Runtime Builder"

# Step 1: Initialize Build Environment
log_step "Initializing Build Environment"

if [[ "$DO_CLEAN" == true ]] || [[ ! -d "$BUILD_WORK_DIR" ]]; then
    log_info "Creating fresh build directory..."
    rm -rf "$BUILD_WORK_DIR"
fi

mkdir -p "$BUILD_WORK_DIR"
mkdir -p "$OUTPUT_RUNTIME_DIR/bin"
mkdir -p "$OUTPUT_RUNTIME_DIR/lib"
mkdir -p "$OUTPUT_RUNTIME_DIR/lib/node_modules"
mkdir -p "$OUTPUT_RUNTIME_DIR/etc"
mkdir -p "$OUTPUT_ASSETS_DIR"

log_success "Build environment ready"

# Step 2: Fetch Termux Packages
init_termux_repo "$BUILD_WORK_DIR" "$ARCH"
download_packages "${PACKAGES[@]}"
extract_packages "$OUTPUT_RUNTIME_DIR" "${PACKAGES[@]}"

# Step 3: Install n8n Core
if [[ "$SKIP_N8N" == true ]] && [[ -d "$OUTPUT_RUNTIME_DIR/lib/node_modules/n8n" ]]; then
    log_step "Using Cached n8n Installation"
    log_info "Skipping npm install (--skip-n8n)"
else
    log_step "Installing n8n Core"
    
    N8N_PATH="$OUTPUT_RUNTIME_DIR/lib/node_modules/n8n"
    mkdir -p "$N8N_PATH"
    
    # Fetch latest n8n
    N8N_VERSION=$(npm view n8n version 2>/dev/null || echo "latest")
    log_info "n8n version: $N8N_VERSION"
    
    N8N_TARBALL=$(npm view "n8n@$N8N_VERSION" dist.tarball)
    wget -q "$N8N_TARBALL" -O "$BUILD_WORK_DIR/n8n.tgz"
    tar -xf "$BUILD_WORK_DIR/n8n.tgz" -C "$N8N_PATH" --strip-components=1
    rm "$BUILD_WORK_DIR/n8n.tgz"
    
    # Install production dependencies
    log_info "Installing npm dependencies (this may take a while)..."
    cd "$N8N_PATH"
    npm install --omit=dev --omit=optional --ignore-scripts --force 2>/dev/null
    npm install sqlite3 --save --ignore-scripts --force 2>/dev/null
    
    log_success "n8n installed"
fi

# Step 4: Inject Pre-compiled SQLite
log_step "Injecting Native Binaries"

LOCAL_SQLITE="$ASSETS_DIR/node_sqlite3-android-arm64.node"
if [[ -f "$LOCAL_SQLITE" ]]; then
    N8N_PATH="$OUTPUT_RUNTIME_DIR/lib/node_modules/n8n"
    SQLITE_DIR="$N8N_PATH/node_modules/sqlite3/lib/binding"
    mkdir -p "$SQLITE_DIR/napi-v6-android-arm64"
    mkdir -p "$SQLITE_DIR/node-v137-android-arm64"
    cp "$LOCAL_SQLITE" "$SQLITE_DIR/napi-v6-android-arm64/node_sqlite3.node"
    cp "$LOCAL_SQLITE" "$SQLITE_DIR/node-v137-android-arm64/node_sqlite3.node"
    log_success "SQLite binary injected"
else
    log_warn "Pre-compiled SQLite not found at $LOCAL_SQLITE"
fi

# Step 5: Library Flattening & Patching
flatten_libraries "$OUTPUT_RUNTIME_DIR/lib" "$MAPPING_FILE"
patch_all_elfs "$OUTPUT_RUNTIME_DIR" "$MAPPING_FILE"

# Step 6: Create Node Wrapper Shim
log_step "Creating Node Wrapper Shim"

NODE_BIN="$OUTPUT_RUNTIME_DIR/bin/node"
if [[ ! -f "${NODE_BIN}_bin" ]]; then
    mv "$NODE_BIN" "${NODE_BIN}_bin"
fi

cat <<'EOS' > "$NODE_BIN"
#!/system/bin/sh
# Node Shim: Injects LD_LIBRARY_PATH for all processes (including child runners)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

unset LD_PRELOAD
export LD_LIBRARY_PATH="$RUNTIME_ROOT/lib:$LD_LIBRARY_PATH"
export OPENSSL_CONF="/dev/null"
export NODE_OPTIONS="--max-old-space-size=512"

# Disable problematic n8n features
export N8N_BLOCK_JS_EXECUTION_PROCESS=true
export N8N_DISABLE_PYTHON_NODE=true

exec "$SCRIPT_DIR/node_bin" "$@"
EOS
chmod +x "$NODE_BIN"
log_success "Node wrapper created"

# Step 7: Create Bootstrap Script
log_step "Creating Bootstrap Script"

cat <<'EOS' > "$OUTPUT_RUNTIME_DIR/bin/n8n-start.sh"
#!/system/bin/sh
# n8n Bootstrap Script - Generated by build_runtime.sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Environment from Kotlin (N8N_USER_FOLDER must be set)
export HOME=$N8N_USER_FOLDER
export PWD=$N8N_USER_FOLDER
export TMPDIR=${TMPDIR:-$N8N_USER_FOLDER/cache}

# Library path (critical)
unset LD_PRELOAD
export LD_LIBRARY_PATH="$RUNTIME_ROOT/lib:$LD_LIBRARY_PATH"
export OPENSSL_CONF="/dev/null"

# Node configuration
export NODE_OPTIONS="--max-old-space-size=512"
export N8N_LOG_LEVEL=info

# Disable problematic features
export N8N_BLOCK_JS_EXECUTION_PROCESS=true
export N8N_DISABLE_PYTHON_NODE=true

# Launch n8n
exec "$RUNTIME_ROOT/bin/node" "$RUNTIME_ROOT/lib/node_modules/n8n/bin/n8n" start
EOS
chmod +x "$OUTPUT_RUNTIME_DIR/bin/n8n-start.sh"
chmod +x "$OUTPUT_RUNTIME_DIR/bin/node_bin" 2>/dev/null || true
log_success "Bootstrap script created"

# Step 8: Create Archive
log_step "Creating Runtime Archive"

cd "$OUTPUT_RUNTIME_DIR"
tar -czf "$TARGET_TARBALL" .

log_success "Archive created: $(du -sh "$TARGET_TARBALL" | awk '{print $1}')"

# Verification
log_step "Verifying Archive"
log_detail "Contents preview:"
# pipefail can cause the script to fail if head closes the pipe early
set +o pipefail
tar -tf "$TARGET_TARBALL" | head -n 10 || true
set -o pipefail

log_footer
echo "    Output: $TARGET_TARBALL"
