# n8n Android Server

**Turn your spare Android phone into a powerful, dedicated home automation hub.**

This project enables you to run a full n8n instance natively on Android. It is a stable, robust solution designed for DIY enthusiasts who want to repurpose existing hardware for private, local automation without relying on the cloud or always-on PCs.

## Core Function
The **n8n Android Server** utilizes a custom-built environment to host n8n directly on your device. It includes a specialized bridge that allows your workflows to interact with the Android system—sending intents, controlling media, or managing connectivity—all from within n8n.

## The Development Journey & Challenges

Integrating isolated Android and Node.js components into a unified mobile environment necessitated a rigorous architectural approach. This project represents the systematic fusion of disparate technologies into a cohesive, high-performance ecosystem designed to function seamlessly on mobile hardware.

### Technical Hurdles
-   **The Integration Puzzle**: Bridging the gap between the high-level n8n automation layer and the low-level Android system required constant decision-making on which generic tools to adapt and which custom bridges to build.
-   **The Linker Breakthrough**: One of the most significant roadblocks was ensuring database stability. We had to manually intervene in the library linking process, using `LD_DEBUG` to trace and resolve complex dependency issues that prevented the native `sqlite3` driver from loading. By manually injecting the pre-compiled `node_sqlite3-android-arm64.node` binary and patching the runtime's internal paths, we bypassed the limitations of standard cross-compilation.
-   **System Constraints**: Managing Android’s aggressive background process policies meant implementing a custom Stability Wizard to ensure the server remains active on a spare device 24/7.

### The AI Advantage
This project is a clear example of the multiplier effect of AI-assisted development. LLMs didn't just help with syntax; they acted as a high-level consultant for:
-   **Architecture Discovery**: Rapidly evaluating which generic Android components could be repurposed for the System Dispatcher.
-   **Deep Diagnostics**: Interpreting obscure Linker logs and tracing library loading failures that typically require years of systems-level experience.
-   **Protocol Bridging**: Seamlessly connecting Ktor-based APIs with n8n's internal command structures.

It represents a new era where complex systems engineering is accessible to determined creators supported by AI.

---

## Collaboration & Contact

This project currently serves my personal home automation needs, but it is built to be shared. There is immense potential for expansion—supporting more system events, optimizing performance, or adding new bridge capabilities.

If you are interested in collaborating, forking, or just want to discuss the implementation, I'd love to hear from you.

**Contact**: `shaked.halachmi@gmail.com`

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

## Build & Deployment

### Build System (`scripts/build_runtime.sh`)
The custom build script handles the complexity of creating an Android-compatible runtime:
1.  Downloads packages from Termux APT.
2.  Patches n8n source for Android compatibility (Task Broker, Binary Paths).
3.  Injects the critical `node_sqlite3-android-arm64.node` asset.

### Requirements
-   **Architecture**: ARM64 (aarch64) only.
-   **OS**: Android 12 or newer recommended.
-   **Hardware**: 4GB+ RAM suggested for stable operation.

### CI/CD
Automated via `.github/workflows/build.yml`. Pushes to `main` generate the `n8n-android-arm64.tar.gz` runtime artifact.

---

## Stability Notes
To ensure the server runs 24/7:
-   Use the in-app **Stability Wizard** to grant Overlay and Battery exemptions.
-   The app runs as a Foreground Service to prevent the OS from killing it during sleep.
