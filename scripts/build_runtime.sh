#!/bin/bash
set -e

# Configuration
TERMUX_REPO_URL="https://packages.termux.dev/apt/termux-main/pool/main"
NODE_PKG="n/nodejs-lts"
# We need to find the specific version. For now, we'll try to find a recent one or accept it as an argument?
# To be robust, we might need to parse the Packages file, but for this v1.6 MVP, let's hardcode a known working version URL or use a directory listing if possible.
# Since I cannot browse the web easily to find the exact dynamic link, I will assume a standard name format and placeholders, 
# or use a "latest" determination logic if I had `apt-get` access to termux repo which I don't on standard linux runner.
# The user requirement says "Fetch the .deb files... from the Termux APT repository".
# Let's try to grab a specific known version or use a wildcard approach if we can list directory (usually not allowed on repo pools).
# Better approach for a robust script: Download the 'Packages' content for aarchitecture, parse the filename for 'nodejs-lts'.

ARCH="aarch64"

# Robust Directory Calculation
# Get the directory where the script is located (scripts/)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
# Project Root is one level up
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Always build inside scripts/build_work to correspond with existing logic
BUILD_WORK_DIR="$SCRIPT_DIR/build_work"
OUTPUT_DIR="$BUILD_WORK_DIR/runtime"

ARTIFACT_NAME="n8n-android-arm64.tar.gz"
METADATA_FILE="metadata.json"
N8N_PATH="$OUTPUT_DIR/lib/node_modules/n8n"
# Debugging suppressed for CI
# echo "DEBUG: ROOT_DIR=$ROOT_DIR"
# echo "DEBUG: OUTPUT_DIR=$OUTPUT_DIR"



# Dependency List (Package Names for lookup)
# nodejs-lts depends on: libuv, openssl, c-ares, libnghttp2, zlib, libicu, brotli, libc++
# Added build tools for native module compilation (sqlite3)
PACKAGES=("nodejs-lts" "libandroid-support" "libsqlite" "zlib" "c-ares" "libuv" "openssl" "libnghttp2" "libicu" "brotli" "libc++" "python" "clang" "make" "binutils")

# Clean up
rm -rf "$BUILD_WORK_DIR"
mkdir -p "$BUILD_WORK_DIR"
cd "$BUILD_WORK_DIR"
# Make OUTPUT_DIR absolute so it works from subdirectories
# OUTPUT_DIR is already absolute (defined at top)
echo "DEBUG: Working in $(pwd)"

echo ">>> Fetching Termux Packages index..."
# We need 'Packages' file to find the filenames
wget -q "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-$ARCH/Packages.gz" -O Packages.gz
gunzip Packages.gz

check_and_download() {
    local pkg_name=$1
    echo ">>> resolving $pkg_name..."
    # Use awk state machine to find the specific package's filename
    # Logic: Find exact "Package: <name>" line, set flag, print Filename when found, stop at next Package or EOF.
    local filename=$(awk -v pkg="$pkg_name" '
        /^Package: / { if ($2 == pkg) { inside=1 } else { inside=0 } }
        inside && /^Filename: / { print $2; exit }
    ' Packages)
    
    if [ -z "$filename" ]; then
        echo "Error: Could not find package $pkg_name in index"
        # Debug: Dump grep if failed
        grep "Package: $pkg_name" Packages || echo "Package not found in grep either"
        exit 1
    fi
    
    local url="https://packages.termux.dev/apt/termux-main/$filename"
    echo ">>> Downloading $url..."
    wget -q "$url" -O "${pkg_name}.deb"
}

for pkg in "${PACKAGES[@]}"; do
    check_and_download "$pkg"
done

echo ">>> Extracting packages..."
mkdir -p "$OUTPUT_DIR"

for pkg in "${PACKAGES[@]}"; do
    echo "Extracting ${pkg}.deb..."
    ar x "${pkg}.deb" data.tar.xz
    tar -xf data.tar.xz -C "$OUTPUT_DIR"
    rm data.tar.xz
done

echo ">>> Organizing Runtime..."
# Termux packages extract to ./data/data/com.termux/files/usr/...
# We need to move this content to our root $OUTPUT_DIR/bin, $OUTPUT_DIR/lib, etc.
# The relative path inside extracted content is usually ./data/data/com.termux/files/usr

TERMUX_PREFIX="$OUTPUT_DIR/data/data/com.termux/files/usr"

if [ -d "$TERMUX_PREFIX" ]; then
    echo "Found Termux prefix, moving files..."
    cp -r "$TERMUX_PREFIX/"* "$OUTPUT_DIR/"
    rm -rf "$OUTPUT_DIR/data"
else
    echo "WARNING: Unexpected directory structure. Checking..."
    find "$OUTPUT_DIR" -maxdepth 3
    # Fallback/Fail
fi

# Clean up unused folders (share, include, etc to save space?)
# For now, keep it simple. User wants n8n.
# We need to install n8n itself! 
# Termux nodejs doesn't include n8n. We use npm to install n8n?
# But we can't run the arm64 node on the x86 CI runner.
# Solution: We verify node exists, then we might need to bundle n8n differently.
# User Request says: "Download Node.js (ARM64) and the latest n8n package."
# Usually we run `npm install -g n8n` but we are cross-compiling.
# We can download the n8n package from registry (tgz) and extract it to lib/node_modules/n8n.
# And create the link.

mkdir -p "$N8N_PATH"

echo ">>> Downloading and installing n8n..."
# Fetch latest n8n tarball from npm
N8N_VERSION=$(npm view n8n version 2>/dev/null || echo "latest")
echo "Latest n8n version: $N8N_VERSION"
N8N_TARBALL=$(npm view n8n@$N8N_VERSION dist.tarball)

wget -q "$N8N_TARBALL" -O n8n.tgz
tar -xf n8n.tgz -C "$N8N_PATH" --strip-components=1

# Install n8n dependencies (runs on host node, which works for pure JS deps)
echo ">>> Installing n8n npm dependencies..."
cd "$N8N_PATH"

# use --force to override ERESOLVE and EBADENGINE issues
npm install --omit=dev --omit=optional --ignore-scripts --force
if [ $? -ne 0 ]; then
    echo "ERROR: npm install failed!"
    exit 1
fi
# Explicitly install sqlite3 to ensure dependencies (node-pre-gyp) are present
echo ">>> Installing sqlite3 explicitly to fetch dependencies..."
npm install sqlite3 --ignore-scripts --no-save --force
# Return to build_work directory explicitly
cd "$ROOT_DIR/scripts/build_work"

# -----------------------------------------------------------------------------
# 7. Patch n8n Source (Disable Task Broker & Runners)
# -----------------------------------------------------------------------------
echo "Patching n8n source code..."

N8N_DIST="$OUTPUT_DIR/lib/node_modules/n8n/dist"

# 1. Disable Task Broker start()
# This prevents the "Port 5679 already in use" crash loop.
BROKER_FILE="$N8N_DIST/task-runners/task-broker/task-broker-server.js"
if [ -f "$BROKER_FILE" ]; then
    if ! grep -q "Task Broker DISABLED" "$BROKER_FILE"; then
        echo "  Patching Task Broker: $BROKER_FILE"
        sed -i "s/async start() {/async start() { console.log('Task Broker DISABLED by patch'); return;/" "$BROKER_FILE"
    else
        echo "  Task Broker already patched."
    fi
else
    echo "  WARNING: Task Broker file not found!"
fi

# 2. Disable JS Runner (Spawn sleep instead of node)
JS_RUNNER_FILE="$N8N_DIST/task-runners/task-runner-process-js.js"
if [ -f "$JS_RUNNER_FILE" ]; then
    if ! grep -q "spawn)('sleep'" "$JS_RUNNER_FILE"; then
        echo "  Patching JS Runner: $JS_RUNNER_FILE"
        sed -i "s/return (0, node_child_process_1.spawn)('node'/return (0, node_child_process_1.spawn)('sleep', ['1000000']); return; (0, node_child_process_1.spawn)('node'/" "$JS_RUNNER_FILE"
    else
        echo "  JS Runner already patched."
    fi
else
    echo "  WARNING: JS Runner file not found!"
fi

# 3. Disable Python Runner (Spawn sleep instead of python)
PY_RUNNER_FILE="$N8N_DIST/task-runners/task-runner-process-py.js"
if [ -f "$PY_RUNNER_FILE" ]; then
    if ! grep -q "spawn)('sleep'" "$PY_RUNNER_FILE"; then
        echo "  Patching Python Runner: $PY_RUNNER_FILE"
        sed -i "s/return (0, node_child_process_1.spawn)(venvPath/return (0, node_child_process_1.spawn)('sleep', ['1000000']); return; (0, node_child_process_1.spawn)(venvPath/" "$PY_RUNNER_FILE"
    else
         echo "  Python Runner already patched."
    fi
else
    echo "  WARNING: Python Runner file not found!"
fi

# 4. Disable Compression (Fixes "Garbage" text via Proxy)
ABSTRACT_SERVER_FILE="$N8N_DIST/abstract-server.js"
if [ -f "$ABSTRACT_SERVER_FILE" ]; then
    if ! grep -q "// this.app.use((0, compression_1.default)());" "$ABSTRACT_SERVER_FILE"; then
        echo "  Patching Abstract Server (Disable Compression): $ABSTRACT_SERVER_FILE"
        sed -i "s/this.app.use((0, compression_1.default)());/\/\/ this.app.use((0, compression_1.default)());/" "$ABSTRACT_SERVER_FILE"
    else
        echo "  Abstract Server already patched."
    fi
else
    echo "  WARNING: Abstract Server file not found!"
fi

# 5. Fix Permissions (Ensure all files are readable/executable)
echo "Fixing permissions..."
chmod -R 755 "$OUTPUT_DIR"

# 6. Debug: Patch TypeORM to log require() errors (CRITICAL for "SQLite package not found" debug)
TYPEORM_DRIVER="$OUTPUT_DIR/lib/node_modules/n8n/node_modules/typeorm/driver/sqlite/SqliteDriver.js"
if [ -f "$TYPEORM_DRIVER" ]; then
    echo "  Patching TypeORM (Enable Debug Logs): $TYPEORM_DRIVER"
    # Find the catch block that throws the generic error and inject logging before it
    # Pattern: catch (e) {
    # We replace it with: catch (e) { console.error('SQLITE LOAD ERROR:', e); console.error('MODULE PATHS:', module.paths);
    sed -i "s/catch (e) {/catch (e) { console.error('SQLITE LOAD ERROR:', e); console.error('MODULE PATHS:', module.paths);/" "$TYPEORM_DRIVER"
else
    echo "  WARNING: TypeORM SqliteDriver not found at $TYPEORM_DRIVER. Searching..."
    # Fallback search if path structure is slightly different (e.g. nested deps)
    TYPEORM_DRIVER=$(find "$OUTPUT_DIR/lib/node_modules" -name "SqliteDriver.js" | grep "typeorm/driver/sqlite" | head -1)
    if [ -n "$TYPEORM_DRIVER" ]; then
         echo "  Found TypeORM driver at: $TYPEORM_DRIVER"
         sed -i "s/catch (e) {/catch (e) { console.error('SQLITE LOAD ERROR:', e); console.error('MODULE PATHS:', module.paths);/" "$TYPEORM_DRIVER"
    else
         echo "  ERROR: Could not find SqliteDriver.js to patch!"
    fi
fi

# -----------------------------------------------------------------------------
# 7. Patch n8n Source (Persistent Fixes)
# -----------------------------------------------------------------------------
echo "Patching n8n source code..."

# 1. Disable Compression (Fixes "Garbage" text via Proxy)
# ABSTRACT_SERVER_FILE="$N8N_DIST/abstract-server.js"
# if [ -f "$ABSTRACT_SERVER_FILE" ]; then
#     echo "  Patching Abstract Server (Disable Compression): $ABSTRACT_SERVER_FILE"
#     sed -i "s/this.app.use((0, compression_1.default)());/\/\/ this.app.use((0, compression_1.default)());/" "$ABSTRACT_SERVER_FILE"
# else
#     echo "  WARNING: Abstract Server file not found!"
# fi

# 2. Fix EACCES permissions in DataTableFileCleanupService
# Background: n8n configuration defaults to Termux path `/data/data/com.termux/...` on Android.
# We must override this to use the `TMPDIR` environment variable which points to our sandbox.
CLEANUP_SERVICE_FILE="$N8N_DIST/modules/data-table/data-table-file-cleanup.service.js"
if [ -f "$CLEANUP_SERVICE_FILE" ]; then
    if ! grep -q "process.env.TMPDIR" "$CLEANUP_SERVICE_FILE"; then
        echo "  Patching DataTableFileCleanupService: $CLEANUP_SERVICE_FILE"
        # Robust regex-based replacement to handle variable whitespace
        # Matches "this.uploadDir = this.globalConfig.dataTable.uploadDir;" with any spacing
        sed -i -E "s/this\.uploadDir\s*=\s*this\.globalConfig\.dataTable\.uploadDir;/this.uploadDir = process.env.TMPDIR ? require('path').join(process.env.TMPDIR, 'n8nDataTableUploads') : this.globalConfig.dataTable.uploadDir;/g" "$CLEANUP_SERVICE_FILE"
    else
        echo "  DataTableFileCleanupService already patched."
    fi
else
    echo "  WARNING: DataTableFileCleanupService file not found! EACCES error might persist."
fi

# 3. Inject Debugging for "Cannot read properties of undefined (reading 'model')"
# We suspect this happens during startup. We will check start.js and potentially Server.js
START_CMD_FILE="$N8N_DIST/commands/start.js"
if [ -f "$START_CMD_FILE" ]; then
    echo "  Patching start.js to improve error logging..."
    # Force log stack trace on generic errors if not already doing so (n8n usually does, but maybe this one is swallowed)
    # Finding: "async catch(error)" in start.js
    # We will inject a console.error because this.logger might be masking it or uninitialized
    sed -i "s/async catch(error) {/async catch(error) { console.error('CRITICAL STARTUP ERROR:', error);/g" "$START_CMD_FILE"
fi

# 4. Fix Crash in TelemetryEventRelay (os.cpus() empty on Android)
TELEMETRY_RELAY_FILE="$N8N_DIST/events/relays/telemetry.event-relay.js"
if [ -f "$TELEMETRY_RELAY_FILE" ]; then
    echo "  Patching TelemetryEventRelay: $TELEMETRY_RELAY_FILE"
    sed -i "s/model: cpus\[0\]\.model,/model: cpus.length > 0 ? cpus[0].model : 'unknown',/g" "$TELEMETRY_RELAY_FILE"
    sed -i "s/speed: cpus\[0\]\.speed,/speed: cpus.length > 0 ? cpus[0].speed : 0,/g" "$TELEMETRY_RELAY_FILE"
fi


# 4. Patch Execute Command Node to use /system/bin/sh (Fix EACCES on Android)
EXECUTE_NODE_FILE="$OUTPUT_DIR/lib/node_modules/n8n/node_modules/n8n-nodes-base/dist/nodes/ExecuteCommand/ExecuteCommand.node.js"
if [ ! -f "$EXECUTE_NODE_FILE" ]; then
    # Fallback search
    EXECUTE_NODE_FILE=$(find "$OUTPUT_DIR/lib/node_modules" -name "ExecuteCommand.node.js" | head -1)
fi

if [ -f "$EXECUTE_NODE_FILE" ]; then
    echo "  Patching ExecuteCommand Node: $EXECUTE_NODE_FILE"
    # Replace cwd option with cwd + shell option
    # Original: { cwd: process.cwd() }
    # Patched: { cwd: process.cwd(), shell: '/system/bin/sh' }
    sed -i "s/{ cwd: process.cwd() }/{ cwd: process.cwd(), shell: '\/system\/bin\/sh' }/g" "$EXECUTE_NODE_FILE"
else
    echo "  WARNING: ExecuteCommand.node.js not found! EACCES error may persist."
fi

# 5. Fix Permissions (Ensure all files are readable/executable)
# We do this AFTER all patches to ensure everything is correct
echo "Fixing permissions..."
chmod -R 755 "$OUTPUT_DIR"


# ============================================================================
# CRITICAL: Install prebuilt sqlite3 native module for Android ARM64
# ============================================================================
# The npm install above uses --ignore-scripts, so native modules are NOT compiled.
# We explicitly inject the binary we compiled on Termux (now present in project root).
echo ">>> Installing sqlite3 native module (Termux compiled)..."

SQLITE3_NAPI="napi-v6"
# Explicitly look in n8n/node_modules first
SQLITE3_MODULE_DIR="$OUTPUT_DIR/lib/node_modules/n8n/node_modules/sqlite3"

if [ ! -d "$SQLITE3_MODULE_DIR" ]; then
    echo "WARNING: sqlite3 not found in standard location ($SQLITE3_MODULE_DIR). Searching..."
    SQLITE3_MODULE_DIR=$(find "$OUTPUT_DIR/lib/node_modules" -type d -name "sqlite3" -path "*/node_modules/sqlite3" | head -1)
fi

if [ -n "$SQLITE3_MODULE_DIR" ] && [ -d "$SQLITE3_MODULE_DIR" ]; then
    echo "Found sqlite3 module at: $SQLITE3_MODULE_DIR"
    
    # Create the binding directory structure
    # Detected ABI 137 (Node 24?) on device. 
    # n8n uses sqlite3 v5.1.7 which defaults to NAPI, but for some reason on this Android build it wants node-v137?
    # We will create BOTH napi-v6 and node-v137 paths to be safe.
    BINDING_DIR_NAPI="$SQLITE3_MODULE_DIR/lib/binding/${SQLITE3_NAPI}-android-arm64"
    BINDING_DIR_ABI="$SQLITE3_MODULE_DIR/lib/binding/node-v137-android-arm64"
    
    mkdir -p "$BINDING_DIR_NAPI"
    mkdir -p "$BINDING_DIR_ABI"
    
    # Use the local file we pulled from the device
    LOCAL_BINARY="$ROOT_DIR/scripts/assets/node_sqlite3-android-arm64.node"
    
    # Debug: Check local binary
    ls -l "$LOCAL_BINARY" || echo "Listing failed for $LOCAL_BINARY"

    if [ -f "$LOCAL_BINARY" ]; then
        cp "$LOCAL_BINARY" "$BINDING_DIR_NAPI/node_sqlite3.node"
        cp "$LOCAL_BINARY" "$BINDING_DIR_ABI/node_sqlite3.node"
        if [ -f "$BINDING_DIR_ABI/node_sqlite3.node" ]; then
             echo "Successfully installed sqlite3 binary to: $BINDING_DIR_ABI/node_sqlite3.node"
        else
             echo "ERROR: Copy failed! File missing at destination."
             exit 1
        fi
    else
        echo "ERROR: Local binary $LOCAL_BINARY not found!"
        echo "Please ensure node_sqlite3-android-arm64.node is in local scripts/assets/."
        exit 1
    fi
else
    echo "ERROR: sqlite3 module not found in node_modules! n8n will fail to start."
    # List node_modules to help debug
    ls -F "$OUTPUT_DIR/lib/node_modules/n8n/node_modules/" || echo "Cannot list n8n node_modules"
    exit 1
fi

# Create bin symlink
mkdir -p "$OUTPUT_DIR/bin"
# Check if n8n binary exists in the module
if [ -f "$OUTPUT_DIR/lib/node_modules/n8n/bin/n8n" ]; then
    echo "Linking n8n binary..."
    cd "$OUTPUT_DIR/bin"
    ln -sf "../lib/node_modules/n8n/bin/n8n" n8n
    cd - > /dev/null
else
    echo "ERROR: n8n binary not found in extracted package"
    exit 1
fi

# Check for patchelf
if ! command -v patchelf &> /dev/null; then
    echo "WARNING: patchelf not found."
    # Attempt to install if we have privileges or are in CI
    if [ "$(id -u)" -eq 0 ] || [ -n "$CI" ]; then
        echo "Attempting to install patchelf..."
        if command -v apt-get &> /dev/null; then
            export DEBIAN_FRONTEND=noninteractive
            apt-get update && apt-get install -y patchelf
        elif command -v apk &> /dev/null; then
            apk add patchelf
        elif command -v yum &> /dev/null; then
            yum install -y patchelf
        else
             echo "ERROR: Could not detect package manager to install patchelf."
             exit 1
        fi
    else
        echo "ERROR: patchelf is required but not found. Please install it (sudo apt install patchelf)."
        exit 1
    fi
fi

if command -v patchelf &> /dev/null; then

        echo ">>> Patching binaries for Android..."
        if [ -f "$OUTPUT_DIR/bin/node" ]; then
            patchelf --set-interpreter /system/bin/linker64 "$OUTPUT_DIR/bin/node"
            echo "Patched node interpreter."
        fi

        # Patch all shared libraries to look in $ORIGIN (same directory)
        echo ">>> Patching shared libraries RPATH..."
        find "$OUTPUT_DIR/lib" -name "*.so*" -type f | while read -r lib; do
            # Only patch files that are actual ELF binaries (skip text files or scripts if any)
            if head -c 4 "$lib" | grep -q "ELF"; then
                patchelf --set-rpath "\$ORIGIN" "$lib" || echo "Warning: Failed to patch $lib"
            fi
        done

        # Patch all .node modules (CRITICAL for sqlite3 and others)
        echo ">>> Patching native modules (.node) RPATH..."
        find "$OUTPUT_DIR/lib/node_modules" -name "*.node" -type f | while read -r module; do
             echo "  Patching module: $module"
             # Use a very generic relative RPATH that bubbles up enough times.
             # sqlite3 is 7 levels deep. We use \$ORIGIN/../../../../../../../lib which targets runtime/lib
             # But simpler: use \$ORIGIN to find the binary, then extensive ../..
             # Or rely on LD_LIBRARY_PATH which we will set in n8n-start.sh
             # However, let's try to set a robust RPATH.
             # We can just set a long chain to cover most depths.
             patchelf --set-rpath "\$ORIGIN/../../../../lib:\$ORIGIN/../../../../../lib:\$ORIGIN/../../../../../../lib:\$ORIGIN/../../../../../../../lib" "$module" || echo "Warning: Failed to patch module $module"
        done
fi

# Verification Step
echo ">>> Verifying critical libraries..."
REQUIRED_LIBS=("libz.so.1" "libcares.so" "libnghttp2.so" "libssl.so" "libcrypto.so" "libsqlite3.so")
MISSING_LIBS=0
for lib in "${REQUIRED_LIBS[@]}"; do
    # Check regular file or symlink
    # We check for .so or .so.0 or .so.1 etc
    if ls "$OUTPUT_DIR/lib/$lib"* 1> /dev/null 2>&1; then
        echo "Verified: $lib"
    else 
        echo "ERROR: Critical library missing: $lib"
        MISSING_LIBS=1
    fi
done

if [ $MISSING_LIBS -eq 1 ]; then
    echo "FATAL: Build incomplete. Missing libraries."
    exit 1
fi

echo ">>> Creating bootstrap script..."
mkdir -p "$OUTPUT_DIR/bin"

cat <<EOS > "$OUTPUT_DIR/bin/n8n-start.sh"
#!/system/bin/sh
# Dynamic Path Calculation (Production)
# \$0 is .../bin/n8n-start.sh, so root is ../
SCRIPT_DIR=\$(dirname "\$0")
RUNTIME_ROOT=\$(cd "\$SCRIPT_DIR/.." && pwd)

export HOME=\$N8N_USER_FOLDER
export PWD=\$N8N_USER_FOLDER
# Ensure N8N_USER_FOLDER is set
export N8N_USER_FOLDER=\$N8N_USER_FOLDER
export N8N_ENCRYPTION_KEY=\$N8N_ENCRYPTION_KEY
export N8N_PORT=\$N8N_PORT
export N8N_HOST=\$N8N_HOST
export N8N_LISTEN_ADDRESS=127.0.0.1
export N8N_EXECUTE_COMMAND_ENABLED=true
export N8N_BLOCK_NODES_EXECUTION=false
export NODES_EXCLUDE=""
export SHELL=/system/bin/sh

# Sanitize PATH
export PATH="/system/bin:/system/xbin:\$PATH"
# Add Runtime bin and n8n bin
export PATH=\$RUNTIME_ROOT/bin:\$RUNTIME_ROOT/lib/node_modules/n8n/bin:\$PATH

export NODE_OPTIONS="--max-old-space-size=512"
export OPENSSL_CONF=\$RUNTIME_ROOT/etc/tls/openssl.cnf
export N8N_RUNNERS_DISABLED=true
# Fallback TMPDIR if not set by app
export TMPDIR=\${TMPDIR:-\$N8N_USER_FOLDER/../../cache}
export NODE_ENV=\${NODE_ENV:-production}

# CRITICAL: Robust LD_LIBRARY_PATH relative to runtime root
export LD_LIBRARY_PATH=\$RUNTIME_ROOT/lib:\$LD_LIBRARY_PATH

# Ensure working directory exists
if [ ! -d "\$N8N_USER_FOLDER" ]; then
    mkdir -p "\$N8N_USER_FOLDER"
fi

# Execute n8n using absolute path from runtime root
exec node \$RUNTIME_ROOT/lib/node_modules/n8n/bin/n8n start
EOS
chmod +x "$OUTPUT_DIR/bin/n8n-start.sh"

echo ">>> Generating Metadata manifest..."
# Calculate verify
# Generate timestamp version or use n8n version
# Generate unique version tag using GITHUB_RUN_ID if available
BUILD_ID="${GITHUB_RUN_ID:-$(date +%s)}"
VERSION_TAG="${N8N_VERSION}-b${BUILD_ID}"
echo "Using Version Tag: $VERSION_TAG"

# Create tarball first to get hash
echo ">>> Packaging artifact (preserving symlinks)..."
# We want the content of $OUTPUT_DIR to be at the root of the tar
tar -czf "$ARTIFACT_NAME" -C "$OUTPUT_DIR" .

SHA256=$(sha256sum "$ARTIFACT_NAME" | awk '{print $1}')
echo "SHA256: $SHA256"

cat <<EOF > "$METADATA_FILE"
{
  "version": "$VERSION_TAG",
  "n8n_version": "$N8N_VERSION",
  "sha256": "$SHA256",
  "download_url": "https://github.com/$GITHUB_REPOSITORY/releases/download/$GITHUB_REF_NAME/$ARTIFACT_NAME",
  "built_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

# Move artifacts to root of project
mv "$ARTIFACT_NAME" "$ROOT_DIR/"
mv "$METADATA_FILE" "$ROOT_DIR/"

echo ">>> Done. Created $ROOT_DIR/$ARTIFACT_NAME and $ROOT_DIR/$METADATA_FILE"
