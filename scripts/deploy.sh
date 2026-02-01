#!/bin/bash
set -e

# Configuration
DEFAULT_DEVICE="10.0.0.11:33731"
WORK_DIR="$(pwd)"
RUNTIME_ARTIFACT="n8n-android-arm64.tar.gz"
TARGET_CACHE_DIR="/storage/emulated/0/Android/data/com.n8nAndroidServer/cache"
PACKAGE_NAME="com.n8nAndroidServer"

# WSL Support: Detect ADB
ADB_CMD="adb"
IS_WSL=false
if grep -q "microsoft" /proc/version 2>/dev/null; then
    IS_WSL=true
    # Try to find Windows ADB efficiently
    if WIN_ADB_RAW=$(cmd.exe /c where adb 2>/dev/null | head -n1 | tr -d '\r'); then
        if [ -n "$WIN_ADB_RAW" ]; then
            # Convert Windows path to WSL path
            WIN_ADB_PATH=$(wslpath "$WIN_ADB_RAW")
            if [ -x "$WIN_ADB_PATH" ]; then
                ADB_CMD="$WIN_ADB_PATH"
                echo "WSL Detected. Using Windows ADB: $ADB_CMD"
            fi
        fi
    else
         echo "WSL Detected but 'adb' not found in Windows PATH."
    fi
fi

# Flags
DO_APK=false
DO_RUNTIME=false
DO_PUSH=false
MODE_SPECIFIED=false
DEVICE=""

# Argument Parsing
for arg in "$@"; do
    case $arg in
        --apk)
            DO_APK=true
            MODE_SPECIFIED=true
            shift
            ;;
        --runtime)
            DO_RUNTIME=true
            MODE_SPECIFIED=true
            shift
            ;;
        --push|-p)
            DO_PUSH=true
            shift
            ;;
        *)
            if [[ "$arg" != -* ]] && [[ -z "$DEVICE" ]]; then
                DEVICE="$arg"
            fi
            ;;
    esac
done

# Default behavior
if [ "$MODE_SPECIFIED" = false ]; then
    DO_APK=true
    DO_RUNTIME=true
fi

# Device Detection Logic
if [ -z "$DEVICE" ]; then
    # Check if any device is already connected
    # Normalize output to handle Windows CRLF
    CONNECTED_DEVICES=$("$ADB_CMD" devices | tr -d '\r' | grep -v "List of devices" | grep "device$" | cut -f1)
    
    if [ -n "$CONNECTED_DEVICES" ]; then
        # Pick the first one
        DEVICE=$(echo "$CONNECTED_DEVICES" | head -n1)
        echo "Found connected device: $DEVICE"
    else
        # Fallback to configured default
        DEVICE="$DEFAULT_DEVICE"
        echo "No device found. Defaulting to: $DEVICE"
    fi
fi

echo "=============================================="
echo "      n8n Android Server Deployment Script"
echo "      Device: $DEVICE"
echo "      Config: WSL=$IS_WSL"
echo "      Tasks: "
echo "        - Runtime Update: $DO_RUNTIME"
echo "        - APK Update: $DO_APK"
echo "        - Git Push: $DO_PUSH"
echo "=============================================="

# 1. ADB Connection (Only try to connect if it's an IP and not in the list)
if [[ "$DEVICE" == *.*.*.*:* ]]; then
    if ! "$ADB_CMD" devices | grep -q "$DEVICE"; then
        echo "--- [1/5] Connecting to Device ---"
        "$ADB_CMD" disconnect "$DEVICE" || true
        "$ADB_CMD" connect "$DEVICE"
    fi
fi

if ! "$ADB_CMD" -s "$DEVICE" get-state | grep -q "device"; then
    echo "Error: Failed to connect to $DEVICE"
    exit 1
fi
echo "Connected."

# 2. Stop the App (CRITICAL: Do this BEFORE pushing runtime to avoid locking files)
echo "--- [2/6] Stopping Application ---"
"$ADB_CMD" -s "$DEVICE" shell am force-stop "$PACKAGE_NAME" || true
echo "App stopped."



if [ "$DO_RUNTIME" = true ]; then
    echo "--- [3/6] Building Runtime Artifact ---"
    if [ -f "scripts/build_runtime.sh" ]; then
        ./scripts/build_runtime.sh
    else
        echo "Warning: scripts/build_runtime.sh not found. Skipping runtime build."
    fi

    echo "--- [4/6] Pushing Runtime to Device ---"
    if [ -f "$RUNTIME_ARTIFACT" ]; then
        echo "Pushing $RUNTIME_ARTIFACT to $TARGET_CACHE_DIR..."
        
        # Prepare path for push
        LOCAL_FILE="$RUNTIME_ARTIFACT"
        if [ "$IS_WSL" = true ] && [[ "$ADB_CMD" == *.exe ]]; then
            # Convert linux path to windows path for adb.exe
            LOCAL_FILE=$(wslpath -w "$RUNTIME_ARTIFACT")
        fi

        "$ADB_CMD" -s "$DEVICE" shell "mkdir -p $TARGET_CACHE_DIR" || true
        "$ADB_CMD" -s "$DEVICE" push "$LOCAL_FILE" "$TARGET_CACHE_DIR/"
        echo "Runtime pushed to cache. Use 'Smart Update' flag in App to install."
    else
        echo "Error: $RUNTIME_ARTIFACT not found. Build failed?"
        exit 1
    fi
else
    echo "--- Skipping Runtime Update ---"
fi

# 4 & 5. APK Ops
if [ "$DO_APK" = true ]; then
    echo "--- [5/6] Building and Installing APK ---"
    #./gradlew assembleDebug || { echo "Gradle build failed"; exit 1; }
    ./gradlew assembleRelease || { echo "Gradle build failed"; exit 1; }

    echo "Installing APK..."
    APK_PATH="build/outputs/apk/debug/n8n-android-server-debug.apk"
    if [ ! -f "$APK_PATH" ]; then
        echo "Error: APK not found at $APK_PATH"
        exit 1
    fi

    LOCAL_APK="$APK_PATH"
    if [ "$IS_WSL" = true ] && [[ "$ADB_CMD" == *.exe ]]; then
        LOCAL_APK=$(wslpath -w "$APK_PATH")
    fi

    "$ADB_CMD" -s "$DEVICE" install -r -t "$LOCAL_APK" || { echo "Install failed"; exit 1; }
    echo "APK Installed."

    echo "--- [6/6] Launching Application ---"
    if "$ADB_CMD" -s "$DEVICE" shell am start -n "$PACKAGE_NAME/.MainActivity"; then
        echo "App launched."
    else
        echo "Warning: Failed to launch app."
    fi
else
    echo "--- Skipping APK Update ---"
fi

# 6. Git Push
if [ "$DO_PUSH" = true ]; then
    echo "--- [6/6] Pushing to GitHub ---"
    git add .
    echo "Enter commit message:"
    read -r COMMIT_MSG
    git commit -m "$COMMIT_MSG"
    git push
    echo "Pushed to GitHub."
fi

echo "=============================================="
echo "          Deployment Complete"
echo "=============================================="
