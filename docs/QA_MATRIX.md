# Warpy Manual QA Verification Matrix

This document defines the manual test cases required to verify the stability, correctness, and reliability of the Warpy Android VPN client across various network transitions, protocol imports, and app lifecycle states.

## 1. Lifecycle & OS Integration

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **LF-01** | Force stop & restart | 1. Connect to any profile.<br>2. Force-stop the app via System Settings.<br>3. Reopen the app. | The UI shows the correct status (Disconnected), and the core process starts fresh without retaining old uptime. |
| **LF-02** | Swipe out from Recents | 1. Connect to any profile.<br>2. Swipe Warpy out from the Android Recents screen. | The VPN foreground service remains active; the key icon stays in the status bar; connection is not dropped. |
| **LF-03** | Device reboot with Always-on | 1. Enable "Always-on VPN" for Warpy in Android System Settings.<br>2. Reboot the device. | The Android OS automatically starts Warpy and reconnects to the last active profile during boot. |
| **LF-04** | APK update over active VPN | 1. Connect to any profile.<br>2. Install a new version of the APK over the existing one via ADB. | The old service terminates cleanly, the new service launches, and the connection restarts with refreshed uptime. |

## 2. Sleep & Standby (Doze Mode)

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **SL-01** | Short standby (10 min) | 1. Connect to a profile.<br>2. Turn off the screen for 10 minutes. <br>3. Turn screen on and verify internet. | Internet remains immediately available. No delay or stalled connections. |
| **SL-02** | Long standby (8 hours) | 1. Connect to a profile.<br>2. Lock screen and leave overnight (8+ hours).<br>3. Unlock and immediately test a message. | The VPN is still active; background notifications (e.g., Telegram) arrive; no need to restart VPN. |

## 3. Network Handoff & Connectivity Changes

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **NW-01** | Wi-Fi to LTE transition | 1. Connect to VPN over Wi-Fi.<br>2. Walk away or turn off Wi-Fi to force LTE switch. | The connection transitions automatically. The VPN status recovers without manual intervention. |
| **NW-02** | LTE to Wi-Fi transition | 1. Connect to VPN over mobile data (LTE).<br>2. Turn on Wi-Fi and connect to a hot Wi-Fi network. | VPN shifts to Wi-Fi. The TUN interface remains intact, and active TCP connections recover. |
| **NW-03** | Airplane mode toggle | 1. Connect to VPN.<br>2. Turn on Airplane mode for 30 seconds.<br>3. Turn off Airplane mode. | Status shifts: Connected -> Recovering (or Disconnected) -> automatically Connected once network is restored. |
| **NW-04** | Wi-Fi without Internet | 1. Connect to a Wi-Fi network with no internet access. | The app stays in `Recovering` or shows `No Internet` instead of pretending to be `Connected` indefinitely. |

## 4. Protocol Verification

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **PR-01** | Hysteria 2 UDP Throttling | 1. Connect to Hysteria 2 on a network that blocks/throttles UDP. | The app automatically fails back to the secondary TCP-based profile (VLESS/Trojan) after a short timeout. |
| **PR-02** | Invalid SNI or Auth | 1. Import a profile with invalid credentials or incorrect SNI. | The connection is rejected immediately with a specific authentication or handshake error, not a generic timeout. |
| **PR-03** | Trojan Import | 1. Import a `trojan://` link via clipboard or QR code.<br>2. Click connect. | The profile imports successfully with the correct password and SNI, and connects with Chrome uTLS. |

## 5. App Tunneling (Per-App Routing)

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **AP-01** | Include Mode | 1. Set App Tunneling to `Include`. Select "Telegram".<br>2. Connect.<br>3. Check IP in browser and Telegram. | Telegram traffic routes through the VPN (checks VPS IP); browser traffic routes directly (checks home ISP IP). |
| **AP-02** | Exclude Mode | 1. Set App Tunneling to `Exclude`. Select "Chrome".<br>2. Connect.<br>3. Check IP in browser and other apps. | Chrome traffic routes directly; all other apps (e.g., YouTube, Telegram) route through the VPN. |

## 6. Speedtest & Performance

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **ST-01** | Multi-run stability | 1. Connect to VPN.<br>2. Run the speedtest 3 times in a row. | Each run completes successfully. No port collision errors (`address already in use`) or locks. |
| **ST-02** | Cancel speedtest | 1. Start the speedtest.<br>2. Close the speedtest dialog mid-way. | The speedtest socket/server closes immediately. Memory is freed, and the next run starts clean. |

## 7. Accessibility & UI Adaptation

| Case ID | Scenario | Verification Steps | Expected Result |
|---|---|---|---|
| **UI-01** | Text Scaling | 1. Set Android Font Scale to `1.5` and `2.0`.<br>2. Launch Warpy. | Text wraps cleanly; no label clipping; button tap targets remain easily clickable. |
| **UI-02** | TalkBack support | 1. Enable TalkBack.<br>2. Navigate the main screen and settings. | Screen readers properly read the connection state, buttons, and settings labels. |
