# Implementation Plan - Debug Module C (Gatekeeper Connectivity)

## Problem
n8n process is running (confirmed by logs) but is inaccessible from other devices on the LAN. 
Expected flow: External request -> Gatekeeper (0.0.0.0:5678) -> Proxy -> n8n (127.0.0.1:5679).

## Investigation Areas

### 1. Gatekeeper Configuration
- **File**: `GatekeeperProxy.kt` / [N8nForegroundService.kt](file:///home/shaked/n8ntry/src/main/kotlin/com/n8ntry/core/N8nForegroundService.kt)
- **Verify**: 
    - Is Ktor binding to `0.0.0.0`? (Confirmed in Service code, but verifying runtime behavior).
    - Is `GatekeeperProxy` rejecting requests based on IP?
    - Is the proxy logic correctly forwarding to `127.0.0.1:5679`?

### 2. n8n Process Configuration
- **File**: [com/n8ntry/core/ServerManager.kt](file:///home/shaked/n8ntry/src/main/kotlin/com/n8ntry/core/ServerManager.kt)
- **Verify**:
    - `N8N_HOST` should be `127.0.0.1`.
    - `N8N_PORT` should be `5679`.
    - checking `N8N_LISTEN_ADDRESS` if it overrides `N8N_HOST`.

### 3. Runtime Network Status
- **Action**: Run `netstat -nlp` (or `ss`) via ADB to confirm listening ports.
- **Expected**:
    - `node` listening on `127.0.0.1:5679`.
    - `com.n8ntry` (Java/Ktor) listening on `0.0.0.0:5678` (or `:::5678`).

## Proposed Changes

### Add Debug Logging
- **Target**: `GatekeeperProxy.kt`
- **Change**: Add logging for *every* incoming request to [Gatekeeper](file:///home/shaked/n8ntry/src/main/kotlin/com/n8ntry/core/N8nForegroundService.kt#169-187).
    - Log remote IP.
    - Log destination path.
    - Log decision (Allowed/Blocked/Forwarding).

### Network Hardening
- **Target**: [ServerManager.kt](file:///home/shaked/n8ntry/src/main/kotlin/com/n8ntry/core/ServerManager.kt) method [buildEnvironment](file:///home/shaked/n8ntry/src/main/kotlin/com/n8ntry/core/ServerManager.kt#254-270)
- **Change**: Ensure `N8N_HOST` is strictly `127.0.0.1` and `N8N_PORT` matches the proxy target.

## Verification Plan
1. Open App. Wait for n8n start.
2. Run `adb shell netstat -lpn`.
3. Attempt curl from computer: `curl -v http://<PHONE_IP>:5678`.
4. Check `adb logcat` for "Gatekeeper" logs.
