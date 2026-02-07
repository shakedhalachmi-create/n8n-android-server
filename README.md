# n8n Android Server

**A self-contained, n8n instance running natively on Android.**

Turn your spare Android device into a powerful, privacy-focused home automation hub. The server runs entirely on-device, bridging n8n workflows with Android system capabilities like intents, notifications, and hardware control.

---

## Architecture: Bundled Assets

Unlike last version that download runtimes on first launch, this project uses a **Bundled Asset Architecture**.

-   **Self-Contained**: The Node.js runtime, n8n core, and native dependencies are pre-built and packaged directly into the APK as a compressed archive (`core_runtime.n8n`).
-   **Security & Stability**: No runtime downloads means no external dependency failures, no checksum mismatches, and guaranteed consistent environments across installs.
-   **Offline First**: The application can be installed and started without an internet connection.

### Core Components
1.  **The Runtime Archive**: A custom `.tar.gz` containing a patched Node.js binary, n8n `node_modules`, and Android-compatible shared libraries (`.so` files patched with `patchelf`).
2.  **Runtime Installer**: On first launch, the app extracts this archive from `assets/` to the app's internal private storage (`/data/user/0/.../files/runtime`).
3.  **Process Supervisor**: Manages the Node.js process, converting Android lifecycle events into graceful start/stop commands.

---

## Security Model

The system prioritizes security while allowing necessary access:
-   **Web UI (`0.0.0.0:5678`)**: Open to your local network, so you can edit workflows from your laptop or tablet.
-   **System Bridge (`127.0.0.1:5680`)**: The API that controls the device is restricted to `localhost`. External devices cannot trigger phone actions; only n8n workflows running *on* the phone can access this bridge.

## System Dispatcher API

The **Command Bridge** allows n8n to execute Android system actions.

**Endpoint:** `POST http://127.0.0.1:5680/api/v1/system/execute`

### JSON Structure
The API expects a JSON payload defining the target category, action, and optional parameters.

```json
{
  "category": "system|media|device",
  "action": "<specific_action>",
  "params": {
    "param1": "value1"
  }
}
```

### Supported Actions

#### 1. Hardware Control
**Flashlight:**
```json
{ "category": "flashlight", "action": "on" }
```
*(Actions: `on`, `off`)*

**Volume:**
Set volume for `music`, `ring`, `alarm`, or `notification`.
```json
{
  "category": "volume", 
  "action": "set", 
  "params": { "stream": "music", "level": "10" } 
}
```

**Screen:**
Control screen state (Wake/Lock).
```json
{ "category": "screen", "action": "on" }
```
*(Actions: `on`, `off`, `status`)*
*(Note: `off` requires **Accessibility Service** enabled)*

**Brightness:**
Control screen brightness.
```json
{ "category": "brightness", "action": "200" }
```
*(Actions: `[0-255]`, `auto`, `status`)*
*(Legacy support: `set` with params)*

**Wi-Fi Control:**
```json
{ "category": "wifi", "action": "on" }
```
*(Actions: `on`, `off`, `scan`, `connect`, `status`)*

**Connect Example:**
```json
{
  "category": "wifi",
  "action": "connect",
  "params": { "ssid": "MyNetwork", "password": "secretpassword" }
}
```

**Battery:**
Get level, health, and charging status.
```json
{ "category": "battery", "action": "get_status" }
```

#### 2. UI & System Automation
**Global Actions:**
(Requires Accessibility Service)
```json
{ "category": "ui", "action": "home" }
```
*(Actions: `home`, `back`, `recents`, `notifications`, `lock`)*

**Clipboard:**
```json
{ "category": "clipboard", "action": "set", "params": { "text": "Copied from n8n" } }
```
```json
{ "category": "clipboard", "action": "get" }
```

**Feedback:**
Show a toast message.
```json
{ "category": "feedback", "action": "toast", "params": { "text": "Workflow Completed" } }
```

#### 3. Bluetooth
**Control:**
```json
{ "category": "bluetooth", "action": "on" }
```
*(Actions: `on`, `off`, `scan`, `connect`)*

**Connect Example:**
```json
{
  "category": "bluetooth",
  "action": "connect",
  "params": { "target": "Device Name" }
}
```

#### 4. Application Launching
Launch any installed app using its package name.
```json
{
  "category": "app",
  "action": "launch",
  "params": { "package_name": "com.twitter.android" }
}
```

---

## Direct Dispatcher API

The Direct Dispatcher provides low-level access to hardware commands and raw Intent launching.

**Endpoint:** `POST http://127.0.0.1:5680/api/dispatch`

### Supported Commands

#### 1. Vibrate
Trigger device vibration.
```json
{
  "command": "vibrate",
  "duration": 500
}
```

#### 2. Screen Tap
Simulate a touch interaction (Requires Accessibility Service enabled).
Coordinates can be absolute pixels or percentages of screen size (e.g., `"50%"`).

**Pixel Example:**
```json
{
  "command": "tap",
  "x": 500,
  "y": 1200
}
```

**Percentage Example:**
```json
{
  "command": "tap",
  "x": "50%",
  "y": "80%"
}
```

#### 3. Raw Intent
Launch an Android Intent directly. Supports detailed parameters including `class`, `type`, and `extras`.
```json
{
  "action": "android.intent.action.VIEW",
  "data": "https://n8n.io",
  "package": "com.android.chrome",
  "type": "text/html",
  "extras": {
    "some_key": "some_value",
    "is_premium": true
  }
}
```

---

## Internal Workflow: First Launch

1.  **Validation**: The app checks if `/files/runtime/bin/node` exists and is executable.
2.  **Extraction**: If missing or outdated, `RuntimeInstaller` streams the `core_runtime.n8n` asset from the APK to a temporary directory.
3.  **Atomic Swap**: The temporary directory is renamed to `runtime`, ensuring a complete installation or none at all.
4.  **Bootstrap**: The app executes `scripts/bin/n8n-start.sh`, which sets up `LD_LIBRARY_PATH` and launches Node.js.

---

## Troubleshooting

### OpenSSL & SSL Failures
**Symptom**: `error:2500006F:DSO support routines:dso_new:unknown error`
**Cause**: Android's OpenSSL tries to load host configuration files (like `/system/etc/ssl/openssl.cnf`) which are incompatible or inaccessible.
**Fix**: The app automatically sets the environment variable `OPENSSL_CONF=/dev/null` in `EnvironmentBuilder.kt` to disable config loading.

### Port Conflicts
**Symptom**: `EADDRINUSE`
**Cause**: n8n tries to launch subprocesses for task execution, which conflict on Android's single-user ports.
**Fix**:
-   `N8N_PORT` is set to `5681` (internal).
-   `N8N_BLOCK_JS_EXECUTION_PROCESS=true` forces n8n to run tasks in the main process.

### Permission Denied (Exec)
**Symptom**: `EACCES` when running node.
**Cause**: Android restricts execution from `/sdcard` or external storage.
**Fix**: The runtime must be extracted to `context.filesDir` (internal storage), which has execute permissions. The `RuntimeInstaller` handles this automatically.

## 📜 Credits & License
* **n8n Core:** [n8n.io](https://n8n.io) - Distributed under the Fair-code License.
* **Node.js Runtime:** Built using resources from the [Termux](https://termux.dev/) project.
* **Project Creator:** [shaked halachmi]
