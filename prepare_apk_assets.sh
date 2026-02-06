#!/bin/bash
set -e
set -o pipefail
# =============================================================================
# n8n Android Hybrid Runtime - Internal Pipeline (APK Assets)
# =============================================================================
# Purpose: Prepares the core runtime files for ANY n8n version to be packaged 
# within the APK. This script strips bloat and isolates the "Engine" and "Core".
#
# Inputs:
#   - scripts/assets/node (ARM64 Binary) - MUST BE PROVIDED MANUALLY
#   - scripts/assets/node_sqlite3-android-arm64.node (Termux Compiled)
#
# Outputs:
#   - app/src/main/jniLibs/arm64-v8a/libnode.so
#   - app/src/main/assets/runtime/ (Core n8n Files)
# =============================================================================

# 1. Configuration & Paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$SCRIPT_DIR/assets"
BUILD_WORK_DIR="$SCRIPT_DIR/build_work_internal"
CORE_OUTPUT_DIR="$ROOT_DIR/app/src/main/assets/runtime"
JNILIBS_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

# Structure Guard: Prevent accidental root src/ recreation
if [ -d "$ROOT_DIR/src/main/assets" ] || [ -d "$ROOT_DIR/src/main/jniLibs" ]; then
    echo "WARNING: Legacy ./src/main detected. This script operates on ./app/src/main only."
fi

CRITICAL_NODE_BIN="$ASSETS_DIR/node"
CRITICAL_SQLITE_NODE="$ASSETS_DIR/node_sqlite3-android-arm64.node"

DRY_RUN=false
if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=true
    echo ">>> DRY RUN MODE ACTIVATED"
fi

# 2. Gatekeeper: Mandatory Local Dependency Check
echo ">>> Checking Critical Local Dependencies..."

if [ ! -f "$CRITICAL_NODE_BIN" ]; then
    echo "CRITICAL ERROR: Manual build required for [node]. Cannot package APK without local native binaries."
    echo "Expected path: $CRITICAL_NODE_BIN"
    exit 1
fi

if [ ! -f "$CRITICAL_SQLITE_NODE" ]; then
    echo "CRITICAL ERROR: Manual build required for [node_sqlite3-android-arm64.node]. Cannot package APK without local native binaries."
    echo "Expected path: $CRITICAL_SQLITE_NODE"
    exit 1
fi

echo ">>> All critical dependencies found."




#f(){






# 3. Setup Internal Build Workspace
if [ "$DRY_RUN" = false ]; then
    rm -rf "$BUILD_WORK_DIR"
    mkdir -p "$BUILD_WORK_DIR"
    mkdir -p "$BUILD_WORK_DIR/bin"
    mkdir -p "$BUILD_WORK_DIR/lib/node_modules"
else
    # Mock for dry run to allow logic to proceed
    BUILD_WORK_DIR="/tmp/n8n_dry_run_internal"
    mkdir -p "$BUILD_WORK_DIR/lib/node_modules/n8n"
fi

# 4. Install n8n (Core JS)
# We use npm to fetch the package, similar to build_runtime.sh
cd "$BUILD_WORK_DIR" || exit
N8N_PATH="$BUILD_WORK_DIR/lib/node_modules/n8n"
mkdir -p "$N8N_PATH"

echo ">>> Fetching n8n Core..."
if [ "$DRY_RUN" = false ]; then
    # Fetch latest n8n tarball from npm
    N8N_VERSION=$(npm view n8n version 2>/dev/null || echo "latest")
    echo "Latest n8n version: $N8N_VERSION"
    N8N_TARBALL=$(npm view n8n@$N8N_VERSION dist.tarball)
    
    wget -q "$N8N_TARBALL" -O n8n.tgz
    tar -xf n8n.tgz -C "$N8N_PATH" --strip-components=1
    
    echo ">>> Injecting Debug Logs into n8n binary..."
    if [ -f "$N8N_PATH/bin/n8n" ]; then
        sed -i "2i console.log('--- DEBUG STARTUP ---'); console.log('__dirname:', __dirname); console.log('Process CWD:', process.cwd()); console.log('Module Paths:', module.paths); console.log('--- END DEBUG ---');" "$N8N_PATH/bin/n8n"
    fi

    echo ">>> Installing n8n Core Dependencies..."
    cd "$N8N_PATH"
    # Install minimal production deps
    npm install --omit=dev --omit=optional --ignore-scripts --force
    
    # === FAT APK ADDITION: Install Heavy Modules Directly ===
    echo ">>> Installing Heavy Modules (Fat APK Strategy)..."
    # We use --save so they survive npm prune (which removes extraneous packages)
    npm install sqlite3 --save --ignore-scripts --force
    npm install @napi-rs/canvas --save --ignore-scripts --force
    # Explicitly install the Android binary package to ensure it exists
    npm install @napi-rs/canvas-android-arm64 --save --ignore-scripts --force
    # Explicitly install AI Workflow Builder (Pinned to 1.6.1 to match n8n and avoid ETARGET errors with aws-sdk)
    npm install @n8n/ai-workflow-builder@1.6.1 --save --ignore-scripts --force
    
    # Inject Pre-compiled SQLite3 (Crucial for Android)
    SQLITE_DIR="$N8N_PATH/node_modules/sqlite3"
    if [ -d "$SQLITE_DIR" ]; then
        echo ">>> Injecting Pre-compiled SQLite3..."
        mkdir -p "$SQLITE_DIR/lib/binding/napi-v6-android-arm64"
        mkdir -p "$SQLITE_DIR/lib/binding/node-v137-android-arm64"
        
        cp "$CRITICAL_SQLITE_NODE" "$SQLITE_DIR/lib/binding/napi-v6-android-arm64/node_sqlite3.node"
        cp "$CRITICAL_SQLITE_NODE" "$SQLITE_DIR/lib/binding/node-v137-android-arm64/node_sqlite3.node"
    else
         echo "WARNING: sqlite3 directory not found after install!"
    fi

    # Canvas Architecture Enforcement
    CANVAS_DIR="$N8N_PATH/node_modules/@napi-rs/canvas"
    if [ -d "$CANVAS_DIR" ]; then
        echo ">>> Cleaning up Canvas Architectures (Keeping only android-arm64)..."
        # Delete anything that isn't android-arm64. We add || true because grep returns 1 if no lines match (or if nothing found), causing pipefail crash.
        find "$CANVAS_DIR" -name "*.node" | grep -v "android-arm64" | xargs rm -f || true
    fi
    
    # Step 1: NPM Production Pruning - Remove devDependencies based on actual dependency tree
    echo ">>> Pruning development dependencies..."
    npm prune --production --omit=dev 2>/dev/null || true
else
    echo "[DRY RUN] Would download n8n and run npm install in $N8N_PATH"
fi

# 5. Patching (Inherited Logic)
if [ "$DRY_RUN" = false ]; then
    echo ">>> Applying Android Compatibility Patches..."
    N8N_DIST="$N8N_PATH/dist"
    
    # Patch 1: Disable Task Broker
    BROKER_FILE="$N8N_DIST/task-runners/task-broker/task-broker-server.js"
    [ -f "$BROKER_FILE" ] && sed -i "s/async start() {/async start() { console.log('Task Broker DISABLED by patch'); return;/" "$BROKER_FILE"

    # Patch 2: Disable JS/Python Runners
    JS_RUNNER="$N8N_DIST/task-runners/task-runner-process-js.js"
    [ -f "$JS_RUNNER" ] && sed -i "s/return (0, node_child_process_1.spawn)('node'/return (0, node_child_process_1.spawn)('sleep', ['1000000']); return; (0, node_child_process_1.spawn)('node'/" "$JS_RUNNER"
    
    PY_RUNNER="$N8N_DIST/task-runners/task-runner-process-py.js"
    [ -f "$PY_RUNNER" ] && sed -i "s/return (0, node_child_process_1.spawn)(venvPath/return (0, node_child_process_1.spawn)('sleep', ['1000000']); return; (0, node_child_process_1.spawn)(venvPath/" "$PY_RUNNER"

    # Patch 3: Disable Compression
    ABSTRACT_SERVER="$N8N_DIST/abstract-server.js"
    [ -f "$ABSTRACT_SERVER" ] && sed -i "s/this.app.use((0, compression_1.default)());/\/\/ this.app.use((0, compression_1.default)());/" "$ABSTRACT_SERVER"

    # Patch 4: DataTable Tmp Dir
    CLEANUP_SERVICE="$N8N_DIST/modules/data-table/data-table-file-cleanup.service.js"
    [ -f "$CLEANUP_SERVICE" ] && sed -i -E "s/this\.uploadDir\s*=\s*this\.globalConfig\.dataTable\.uploadDir;/this.uploadDir = process.env.TMPDIR ? require('path').join(process.env.TMPDIR, 'n8nDataTableUploads') : this.globalConfig.dataTable.uploadDir;/g" "$CLEANUP_SERVICE"
    
    # Patch 5: Telemetry (Fix os.cpus crash)
    TELEMETRY_RELAY="$N8N_DIST/events/relays/telemetry.event-relay.js"
    [ -f "$TELEMETRY_RELAY" ] && {
        sed -i "s/model: cpus\[0\]\.model,/model: cpus.length > 0 ? cpus[0].model : 'unknown',/g" "$TELEMETRY_RELAY"
        sed -i "s/speed: cpus\[0\]\.speed,/speed: cpus.length > 0 ? cpus[0].speed : 0,/g" "$TELEMETRY_RELAY"
    }

    # Patch 6: Execute Command Node (Fix EACCES/Command not found on Android)
    # We patch the default shell to be /system/bin/sh because /bin/sh does not exist on Android
    EXECUTE_NODE="$N8N_PATH/node_modules/n8n-nodes-base/dist/nodes/ExecuteCommand/ExecuteCommand.node.js"
    if [ ! -f "$EXECUTE_NODE" ]; then
         # Fallback search
         EXECUTE_NODE=$(find "$N8N_PATH" -name "ExecuteCommand.node.js" | head -1)
    fi

    if [ -f "$EXECUTE_NODE" ]; then
        echo ">>> Patching ExecuteCommand Node to use /system/bin/sh..."
        # Replace cwd option with cwd + shell option
        sed -i "s/{ cwd: process.cwd() }/{ cwd: process.cwd(), shell: '\/system\/bin\/sh' }/g" "$EXECUTE_NODE"
    else
        echo "WARNING: ExecuteCommand.node.js not found! Shell execution might fail."
    fi

    echo ">>> Patches Applied."
fi

# 6. Asset Stripping (The "Slimming" Phase)

# Step 2: Safe Whitelisting - Critical n8n modules that must NEVER be touched
is_protected_path() {
    local path="$1"
    # Protect core n8n dist folder and enterprise modules
    if [[ "$path" == *"node_modules/n8n/dist"* ]] || \
       [[ "$path" == *"community-packages"* ]] || \
       [[ "$path" == *"n8n-core"* ]] || \
       [[ "$path" == *"n8n-workflow"* ]] || \
       [[ "$path" == *"n8n-nodes-base"* ]]; then
        return 0  # Protected
    fi
    return 1  # Not protected
}

if [ "$DRY_RUN" = false ]; then
    echo ">>> Stripping Bloat (with Safe Whitelisting)..."
    
    # Remove tests, docs, examples, maps - WITH PROTECTION for critical paths
    # DISABLED: Aggressive stripping was removing critical JSON/metadata files needed for n8n AI features
    # find "$N8N_PATH" -type d -name "test" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -not -path "*community-packages*" \
    #     -not -path "*n8n-core*" \
    #     -not -path "*n8n-workflow*" \
    #     -not -path "*n8n-nodes-base*" \
    #     -exec rm -rf {} + 2>/dev/null || true
    
    # find "$N8N_PATH" -type d -name "tests" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -not -path "*community-packages*" \
    #     -not -path "*n8n-core*" \
    #     -not -path "*n8n-workflow*" \
    #     -not -path "*n8n-nodes-base*" \
    #     -exec rm -rf {} + 2>/dev/null || true
    
    # find "$N8N_PATH" -type d -name "examples" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -exec rm -rf {} + 2>/dev/null || true
    
    # find "$N8N_PATH" -type d -name "docs" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -exec rm -rf {} + 2>/dev/null || true
    
    # find "$N8N_PATH" -type f -name "*.md" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -delete 2>/dev/null || true
    
    # find "$N8N_PATH" -type f -name "*.map" -delete 2>/dev/null || true
    # find "$N8N_PATH" -type f -name "*.gz" -delete 2>/dev/null || true
    # find "$N8N_PATH" -type f -name "LICENSE" -delete 2>/dev/null || true
    
    # Remove large dev dependencies if they snuck in
    rm -rf "$N8N_PATH/node_modules/typescript"
    
    # EXCLUDE HEAVY MODULES (They go to Remote)
    # We remove them from Internal to save space
    #rm -rf "$N8N_PATH/node_modules/sqlite3"
    #rm -rf "$N8N_PATH/node_modules/@napi-rs/canvas"
    #rm -rf "$N8N_PATH/node_modules/sharp"
    # better-sqlite3 if present
    #rm -rf "$N8N_PATH/node_modules/better-sqlite3"
fi

# 7. Final Deployment (Internal)

# 7a. Deploy Binary to jniLibs
# 7a. Deploy Binary to jniLibs (Global Sanitizer)
if [ "$DRY_RUN" = false ]; then
    echo ">>> Deploying Native Libraries to jniLibs..."
    mkdir -p "$JNILIBS_DIR"
    
    # Clean old libs
    rm -f "$JNILIBS_DIR"/*.so
    
    # 1. Copy Critical Node Binary
    cp "$CRITICAL_NODE_BIN" "$JNILIBS_DIR/libnode.so"
    chmod +x "$JNILIBS_DIR/libnode.so"
    
    # 2. Phase 1: Discovery & Physical Sanitization
    # Scan assets dir for ANY .so file (versioned or not)
    # We ignore the 'node' binary itself as it's handled separately
    find "$ASSETS_DIR" -maxdepth 1 -name "*.so*" | while read -r lib; do
        BASENAME=$(basename "$lib")
        # Rename rule: Strip version suffix (e.g., libsqlite3.so.0 -> libsqlite3.so)
        TARGET_NAME=$(echo "$BASENAME" | sed 's/\.so\..*/.so/')
        
        echo ">>> Processing $BASENAME -> $TARGET_NAME"
        cp "$lib" "$JNILIBS_DIR/$TARGET_NAME"
        chmod +x "$JNILIBS_DIR/$TARGET_NAME"
        
        # Identity Update: Set internal SONAME to the clean name
        if command -v patchelf &> /dev/null; then
             patchelf --set-soname "$TARGET_NAME" "$JNILIBS_DIR/$TARGET_NAME"
        fi
    done
    
    # 3. Phase 2: Recursive Dependency Mapping (The "Global Sweep")
    if command -v patchelf &> /dev/null; then
        echo ">>> Performing Global Dependency Sweep..."
        
        # Iterate through EVERY library in jniLibs (targets)
        # Use find with process substitution to avoid subshell issues
        while read -r target_lib; do
            target_name=$(basename "$target_lib")
            echo "    [Analyzing] $target_name"
            
            # Explicit Fix: Always map libz.so.1 -> system libz.so
            if readelf -d "$target_lib" | grep -q "libz.so.1"; then
                 echo "        [Fixing System zlib] libz.so.1 -> libz.so"
                 patchelf --replace-needed libz.so.1 libz.so "$target_lib"
            fi

            # Explicit Fix: Map libc++.so -> libc++_shared.so (bundled)
            if readelf -d "$target_lib" | grep -q "libc++.so"; then
                 echo "        [Fixing C++] libc++.so -> libc++_shared.so"
                 patchelf --replace-needed libc++.so libc++_shared.so "$target_lib"
            fi

            # Dynamic Fix: Scan dependencies
            # We iterate over dependencies found by readelf.
            # We use process substitution or check if variable is set to handle cases with no dependencies cleanly.
            dependencies=$(readelf -d "$target_lib" 2>/dev/null | grep "NEEDED" | awk '{print $NF}' | tr -d '[]' || true)
            
            if [ -n "$dependencies" ]; then
                echo "$dependencies" | while read -r dep; do
                    
                    # Check if dependency is versioned (ends in .so.number)
                    if [[ "$dep" =~ \.so\.[0-9]+ ]]; then
                        # Calculate the "Clean Name" for this dependency
                        # e.g. "libssl.so.3" -> "libssl.so"
                        clean_dep_name=$(echo "$dep" | sed 's/\.so\..*/.so/')
                        
                        # Verify if we actually HAVE this clean library in our bundle
                        if [ -f "$JNILIBS_DIR/$clean_dep_name" ]; then
                            echo "        [Auto-Patching] Needs $dep -> Found $clean_dep_name (Fixing...)"
                            patchelf --replace-needed "$dep" "$clean_dep_name" "$target_lib"
                        else
                            echo "        [WARNING] Needs $dep but $clean_dep_name NOT found in jniLibs!"
                        fi
                    fi
                done
            fi
        done < <(find "$JNILIBS_DIR" -name "*.so")
        
        # 4. Phase 4: Final Verification Gatekeeper
        echo ">>> Verifying Linker Compliance..."
        failed=0
        while read -r binary; do
             # Check for any dependency ending in .so followed by a digit
             if readelf -d "$binary" | grep "NEEDED" | grep -E "\.so\.[0-9]+"; then
                 echo "CRITICAL ERROR: $(basename "$binary") still depends on versioned libraries!"
                 readelf -d "$binary" | grep "NEEDED" | grep -E "\.so\.[0-9]+"
                 failed=1
             fi
        done < <(find "$JNILIBS_DIR" -name "*.so")
        
        if [ "$failed" -eq 1 ]; then
             echo ">>> Verification Failed: Versioned dependencies detected."
             echo "    This will cause a crash on Android. Aborting build."
             exit 1
        fi
         echo ">>> Verification Passed: All libraries have clean dependencies."
        
    else
        echo "WARNING: patchelf not found! Linker errors expected on Android."
    fi

else
    echo "[DRY RUN] Would copy and patch native libraries in $JNILIBS_DIR"
fi





#}

#N8N_PATH="$BUILD_WORK_DIR/lib/node_modules/n8n"






# 7b. Deploy Assets to Runtime (Tarball Packaging)
if [ "$DRY_RUN" = false ]; then
    echo ">>> Preparing Core Assets (Staging)..."
    STAGING_DIR="$BUILD_WORK_DIR/staging"
    BACKUP_ROOT="$BUILD_WORK_DIR/removed_bundles"
    # הנתיב הרשמי שבו גרדל מחפש את הנכסים
    FINAL_ASSETS_DIR="$CORE_OUTPUT_DIR"

    # 1. ניקוי יסודי של הכל לפני שמתחילים
    echo ">>> Cleaning old staging and final assets..."
    rm -rf "$STAGING_DIR" "$BACKUP_ROOT"
    rm -rf "$FINAL_ASSETS_DIR" # מוחק את ה-906MB שראינו קודם
    
    mkdir -p "$STAGING_DIR/lib/node_modules"
    mkdir -p "$STAGING_DIR/bin"
    mkdir -p "$FINAL_ASSETS_DIR"
    mkdir -p "$BACKUP_ROOT/ai_nodes" "$BACKUP_ROOT/cloud_sdk" "$BACKUP_ROOT/dev_garbage"
    # 2. פונקציית עזר להעברת רכיבים לגיבוי
    move_to_backup() {
        local source_rel_path="$1" # נתיב בתוך n8n/
        local target_cat="$2"      # שם תיקיית היעד בגיבוי
        local full_source="$STAGING_DIR/lib/node_modules/n8n/$source_rel_path"
        
        if [ -d "$full_source" ]; then
            echo "Archiving: $source_rel_path -> $target_cat"
            mkdir -p "$BACKUP_ROOT/$target_cat/$(dirname "$source_rel_path")"
            mv "$full_source" "$BACKUP_ROOT/$target_cat/$source_rel_path"
        fi
    }




    

    # 3. העתקת הקבצים הראשונית (הבאת כל ה-800MB ל-Staging)
    echo ">>> Copying n8n files to staging..."
    cp -r "$N8N_PATH" "$STAGING_DIR/lib/node_modules/"

    echo ">>> Starting Professional Slimming & Archiving..."

    # 4. הוצאת "גושי שומן" גדולים לגיבוי (Cloud & AI Nodes - NOT Core Dependencies)
    # NOTE: @langchain/core and js-tiktoken are REQUIRED by n8n-core and must be kept!
    
    # move_to_backup "node_modules/@aws-sdk" "cloud_sdk"
    # move_to_backup "node_modules/@azure" "cloud_sdk"
    # move_to_backup "node_modules/@google-cloud" "cloud_sdk"
    # move_to_backup "node_modules/@n8n/n8n-nodes-langchain" "ai_nodes"
    # move_to_backup "node_modules/@n8n/ai-workflow-builder" "ai_nodes"
    # move_to_backup "node_modules/langchain" "ai_nodes"  # Full langchain package, not @langchain/core
   
    # KEEP: @langchain/core - required by n8n-core
    # KEEP: js-tiktoken - required by n8n-core
    #move_to_backup "node_modules/cohere-ai" "ai_nodes"

    # 5. הוצאת בסיסי נתונים ועיבוד מסמכים כבדים
    #move_to_backup "node_modules/tedious" "database_nodes"
    #move_to_backup "node_modules/mssql" "database_nodes"
    #move_to_backup "node_modules/mongodb" "database_nodes"
    #move_to_backup "node_modules/pdfjs-dist" "doc_processing"
    #move_to_backup "node_modules/xlsx" "doc_processing"
    #move_to_backup "node_modules/alasql" "heavy_utils"
    #move_to_backup "node_modules/libphonenumber-js" "heavy_utils"

echo ">>> Final Fat Trimming..."
    #move_to_backup "node_modules/moment" "heavy_utils"
    #move_to_backup "node_modules/moment-timezone" "heavy_utils"
    


    # 6. Use npm prune to remove dev dependencies safely
    echo ">>> Running npm prune --production to remove dev dependencies..."
    cd "$STAGING_DIR/lib/node_modules/n8n" || exit 1
    npm prune --production --ignore-scripts 2>/dev/null || echo "npm prune skipped (may not be needed)"

    # 7. ניקוי כירורגי בתוך ה-node_modules שנשארו

    # ניקוי שפות ב-date-fns (חוסך המון!)
    if [ -d "node_modules/date-fns/locale" ]; then
        echo "Slicing date-fns locales (keeping en-US)..."
        find node_modules/date-fns/locale -mindepth 1 -maxdepth 1 ! -name "en-US" -exec rm -rf {} +
    fi

    # ניקוי Frontend (n8n-editor-ui)
    if [ -d "node_modules/n8n-editor-ui" ]; then
        find node_modules/n8n-editor-ui -type f \( -name "*.map" -o -name "*.md" \) -delete
    fi

    # 7. העברת קבצי פיתוח וזבל לתיקיית הגיבוי (שימוש ב-prune למניעת שגיאות)
    echo ">>> Moving development garbage to backup (with Safe Whitelisting)..."
    
    # העברת קבצי טקסט/קוד מקור - WITH PROTECTION for critical paths
    # DISABLED: Aggressive stripping was moving critical files needed for n8n AI features
    # find . -type f \( -name "*.map" -o -name "*.ts" -o -name "*.tsx" -o -name "*.md" \) \
    #     -not -name "*.so" \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -not -path "*community-packages*" \
    #     -not -path "*n8n-core*" \
    #     -not -path "*n8n-workflow*" \
    #     -not -path "*n8n-nodes-base*" \
    #     -exec sh -c 'mkdir -p "$1/$(dirname "$2")" && mv -n "$2" "$1/$2" 2>/dev/null' _ "$BACKUP_ROOT/dev_garbage" {} \;

    # העברת תיקיות פיתוח (Docs/Tests) - WITH PROTECTION
    # find . -type d \( -name "test" -o -name "tests" -o -name "docs" -o -name "examples" -o -name ".github" \) \
    #     -not -path "*/node_modules/n8n/dist/*" \
    #     -not -path "*community-packages*" \
    #     -not -path "*n8n-core*" \
    #     -not -path "*n8n-workflow*" \
    #     -not -path "*n8n-nodes-base*" \
    #     -prune -exec sh -c 'mkdir -p "$1/$(dirname "$2")" && mv -n "$2" "$1/$2" 2>/dev/null' _ "$BACKUP_ROOT/dev_garbage" {} \;

    rm -rf node_modules/swagger-ui-dist
    # ניקוי קבצי מפה מכל ה-node_modules שנשארו (לפעמים find מפספס חלק)
    # find node_modules -name "*.map" -delete 2>/dev/null || true




    # 8. ניקוי סופי: קבצי README וסימלינקים שבורים
    find node_modules -type f \( -name "README*" -o -name "CHANGELOG*" -o -name "HISTORY.md" \) -delete
    find "$STAGING_DIR" -xtype l -delete


   

cat <<'EOS' > "$STAGING_DIR/bin/n8n-start.sh"
#!/system/bin/sh
# Dynamic Path Calculation
SCRIPT_DIR=$(dirname "$0")
RUNTIME_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
N8N_DIR="$RUNTIME_ROOT/lib/node_modules/n8n"

# סביבת עבודה פרטית (חשוב מאוד ל-SQLite)
export HOME=$N8N_USER_FOLDER
export PWD=$N8N_USER_FOLDER
export N8N_USER_FOLDER=$N8N_USER_FOLDER

# הגדרות שרת
export N8N_PORT=${N8N_PORT:-5678}
export N8N_LISTEN_ADDRESS=0.0.0.0
export N8N_ENCRYPTION_KEY=$N8N_ENCRYPTION_KEY
export N8N_HOST=$N8N_HOST

# אופטימיזציה ל-Android
export N8N_RUNNERS_DISABLED=true
export N8N_DISABLE_VERSION_CHECK=true
export OCLIF_STRICT_METADATA=false # מתעלם משגיאות מניפסט קטנות
export N8N_EXECUTE_COMMAND_ENABLED=true
export SHELL=/system/bin/sh
export NODE_OPTIONS="--max-old-space-size=512"

# Enable Oclif Debugging
export DEBUG="oclif:*,@oclif/*"

# Fallback TMPDIR if not set by app
export TMPDIR=${TMPDIR:-$N8N_USER_FOLDER/../../cache}
export NODE_ENV=${NODE_ENV:-production}

# ניקוי המניפסט המזוהם מה-PC (הסיבה ל-Command not found)
echo ">>> Wiping stale manifests..."
find "$N8N_DIR" -name "oclif.manifest.json" -delete

# הגדרת ספריות Native (כדי ש-Node ימצא את SSL ו-SQLite)
export LD_LIBRARY_PATH="$NODE_LIB_DIR:$LD_LIBRARY_PATH"

# הגדרת נתיב חבילת ה-n8n
NODE_EXECUTABLE="$NODE_LIB_DIR/libnode.so"

# וידוא שהקובץ קיים לפני שמנסים להריץ
if [ ! -f "$NODE_EXECUTABLE" ]; then
    echo "ERROR: Node binary not found at $NODE_EXECUTABLE"
    exit 1
fi

# כניסה לתיקיית ה-n8n (הכרחי ל-oclif)
cd "$RUNTIME_ROOT/lib/node_modules/n8n"

echo ">>> Final Launch check:"
echo "    Node: $NODE_EXECUTABLE"
echo "    CWD:  $(pwd)"

echo "DIAGNOSTIC: Executing start.js directly to check for Module Missing errors..."
# ההרצה המנצחת - עוקף CLI
exec "$NODE_EXECUTABLE" "./dist/commands/start.js"
EOS
    # bin/n8n-start.sh
    # lib/node_modules/n8n/...
    
    # NOTE: We use --transform or -C to handle paths if needed, but since BUILD_WORK_DIR has the correct structure (bin/, lib/), we just pack ".".
    # -h flag dereferences symlinks, ensuring actual files are packaged
    chmod +x "$STAGING_DIR/bin/n8n-start.sh"

    echo ">>> Ensuring $FINAL_ASSETS_DIR directory exists..."
    mkdir -p "$FINAL_ASSETS_DIR"
    


    # === Step 3: VERIFICATION AUDIT - Fail-Safe Check ===
    echo ">>> Running Verification Audit..."
    
    AUDIT_FAILED=0
    
    # Check critical files
    CRITICAL_FILES=(
        "$STAGING_DIR/lib/node_modules/n8n/package.json"
        "$STAGING_DIR/bin/n8n-start.sh"
    )
    
    # Check for start.js (might be in dist/commands/ or bin/)
    if [ ! -f "$STAGING_DIR/lib/node_modules/n8n/dist/commands/start.js" ] && \
       [ ! -f "$STAGING_DIR/lib/node_modules/n8n/bin/n8n" ]; then
        echo "FATAL: Cleanup deleted vital entry point (start.js or bin/n8n)!"
        AUDIT_FAILED=1
    fi
    
    for file in "${CRITICAL_FILES[@]}"; do
        if [ ! -f "$file" ]; then
            echo "FATAL: Cleanup deleted vital file: $file"
            AUDIT_FAILED=1
        fi
    done
    
    # Check for Native Modules (SQLite3)
    if [ ! -f "$STAGING_DIR/lib/node_modules/n8n/node_modules/sqlite3/lib/binding/napi-v6-android-arm64/node_sqlite3.node" ]; then
        echo "FATAL: node_sqlite3.node is missing from the package!"
        AUDIT_FAILED=1
    fi

    # Check for AI Workflow Builder Assets
    # Verified: examples.json does NOT exist in @n8n/ai-workflow-builder@1.6.1 and is NOT referenced in n8n source code.
    # Removed check to prevent false positive failures.

    
    # Check critical directories
    if [ ! -d "$STAGING_DIR/lib/node_modules/n8n/dist" ]; then
        echo "FATAL: Cleanup deleted vital directory: n8n/dist"
        AUDIT_FAILED=1
    fi
    
    if [ ! -d "$STAGING_DIR/lib/node_modules/n8n/node_modules" ]; then
        echo "FATAL: Cleanup deleted vital directory: n8n/node_modules"
        AUDIT_FAILED=1
    fi
    
    # Check for community-packages.ee module (enterprise feature) - warning only
    if ! find "$STAGING_DIR" -name "*community-packages*" -type d 2>/dev/null | grep -q .; then
        echo "WARNING: community-packages module not found. This may affect enterprise features."
    fi
    
    # Step 4: JNI Protection Check
    echo ">>> Verifying JNI files are intact..."
    SO_COUNT=$(find "$STAGING_DIR" -name "*.so" 2>/dev/null | wc -l)
    if [ "$SO_COUNT" -gt 0 ]; then
        echo "INFO: Found $SO_COUNT .so files in staging (native libs should be in jniLibs)"
    fi
    
    if [ "$AUDIT_FAILED" -eq 1 ]; then
        echo ">>> VERIFICATION AUDIT FAILED: Aborting build to prevent broken APK!"
        exit 1
    fi
    
    echo ">>> Verification Audit PASSED: All critical modules present."

    # --- Compatibility Patch for n8n v2.x Registry Bug ---
    echo ">>> Applying .ee module compatibility patch..."
    N8N_MODULES_DIR="$STAGING_DIR/lib/node_modules/n8n/dist/modules"

    if [ -d "$N8N_MODULES_DIR/community-packages" ]; then
        cp -r "$N8N_MODULES_DIR/community-packages" "$N8N_MODULES_DIR/community-packages.ee"
        echo "✓ Created community-packages.ee mirror."
    else
        echo "WARNING: community-packages source not found. Patch skipped."
    fi
    # ----------------------------------------------------

    echo ">>> Packaging into core_runtime.tar..."
    tar -chf "$FINAL_ASSETS_DIR/core_runtime.tar" \
        --exclude="*/node_modules/.bin" \
        --exclude="*/node_modules/.cache" \
        -C "$STAGING_DIR" .

    echo ">>> DONE! Final Assets Location: $FINAL_ASSETS_DIR"
    echo ">>> Tarball Size: $(du -sh "$FINAL_ASSETS_DIR/core_runtime.tar")"
fi

echo ">>> Internal Pipeline Complete."
