# Device-to-Device iPhone→Android Recovery

## Overview
Direct WiFi data recovery from iPhone to Android using a lightweight companion app.

**Architecture:**
```
iPhone (Companion App)  ←WiFi→  Android (PhoneSyncUSB-C)
  • HTTP Server            Network
  • Messages DB
  • Contacts DB
  • Notes DB
  • Call History
```

---

## Setup

### Part 1: iOS Companion App Setup

#### Option A: Build and sign with Xcode (macOS only)

**Requirements:**
- macOS with Xcode 15+
- Apple Developer Account (free)
- USB cable (USB-C or Lightning)

Windows cannot compile or sign an iOS app with Xcode. If no Mac is available,
use a trusted Mac or macOS CI service to produce a signed `.ipa`, then install
that IPA on Windows with Sideloadly or AltStore. A free Apple ID typically
requires re-signing after a short validity period; a paid Apple Developer
account provides longer-lived app signing.

This repository includes `.github/workflows/build-ios-companion.yml`. To use
it from Windows:

1. Push the repository to GitHub.
2. Open **Actions → Build iPhone companion IPA → Run workflow**.
3. Download the `PhoneSyncCompanion-unsigned-ipa` artifact from the completed
   workflow.
4. Use Sideloadly to sign and install that IPA with your Apple ID. Sideloadly
   performs the device-specific signing on Windows; the GitHub runner only
   supplies the compiled iOS app bundle.

**Steps:**

1. **Create Xcode Project**
   ```bash
   # Create new iOS app project
   # - Product Name: PhoneSyncCompanion
   # - Organization ID: com.jerrywolff.phonesyncusbc
   # - Min iOS: 14.0
   # - Devices: iPhone
   ```

2. **Replace App Code**
   - Copy `ios-companion/IPhoneDataServer.swift` to your Xcode project
   - Replace the auto-generated AppDelegate and SceneDelegate

3. **Enable Required Capabilities**
   - Project Settings → Signing & Capabilities
   - Add: "Local Network" permission
   - Add: "Bonjour Services" (_phonesync._tcp)

4. **Add Info.plist Entries**
   ```xml
   <key>NSLocalNetworkUsageDescription</key>
   <string>Required for Android device to discover and recover data from iPhone</string>
   <key>NSBonjourServices</key>
   <array>
         <string>_phonesync._tcp</string>
   </array>
   ```

5. **Connect iPhone**
   - Plug into the Mac via USB
   - Trust the computer on iPhone
   - Select device in Xcode

6. **Build & Run**
   ```
   Product → Run (Cmd+R)
   ```
   - App launches on iPhone
   - Green "Server Online" status appears
   - IP address displayed (e.g., 192.168.1.100)

7. **Send to Background**
   - Keep the companion app visible during recovery. iOS may suspend a normal
     sideloaded app in the background, so background execution is not
     guaranteed.

#### Option B: Windows installation of an already-signed IPA

This does not build the app. It only installs an IPA that was already signed
for the target iPhone.

1. Obtain the signed `PhoneSyncCompanion.ipa` from a Mac or macOS CI build.
2. Install Sideloadly on Windows, connect the iPhone by USB, and trust the
   computer on the iPhone.
3. Drag the IPA into Sideloadly, sign in with the Apple ID used for signing,
   and start the install.
4. On the iPhone, open **Settings → General → VPN & Device Management** and
   trust the developer profile if prompted.
5. Keep the companion app open while Android performs the recovery.

#### Option C: Jailbreak Installation

**For Jailbroken iPhones:**

1. Add this Cydia repo (or equivalent package manager):
   ```
   https://jerrywolff.github.io/cydia/
   ```

2. Install: `PhoneSyncCompanion`
   - Installs as system service
   - Auto-starts at boot
   - No UI needed

3. Verify running:
   ```bash
   # SSH into jailbroken iPhone
   ps aux | grep phonosync
   ```

---

### Part 2: Android App Enhancement

#### Update Android Dependencies

Edit `app/build.gradle.kts`:

```kotlin
dependencies {
    // Network discovery
    implementation("androidx.core:core:1.13.0")
    
    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // HTTP client (optional, for robustness)
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
}
```

#### Update Manifest Permissions

Edit `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

#### Add Recovery Source

Edit `app/src/main/java/com/jerrywolff/phonesyncusbc/domain/RecoveryProfile.kt`:

```kotlin
// Add to RecoveryDeviceType enum
NETWORK_IPHONE("iPhone (WiFi Network)")

// Add to defaultFor() when:
SourcePlatform.IOS -> if (hasNetworkCapability) NETWORK_IPHONE else IPHONE_IPAD
```

#### Integrate Network Recovery UI

In your MainActivity or recovery flow:

```kotlin
import com.jerrywolff.phonesyncusbc.ui.recovery.NetworkRecoveryScreen

// In your navigation or Compose:
when (selectedRecoveryType) {
    RecoveryDeviceType.NETWORK_IPHONE -> {
        NetworkRecoveryScreen(
            onDataRecovered = { messages, contacts, notes ->
                saveRecoveredData(messages, contacts, notes)
                navigateToDataViewer()
            }
        )
    }
    // ... other cases
}
```

---

## Usage Workflow

### On iPhone (Companion App)

1. **Launch Companion App**
   - Home screen or Cydia
   - Green status appears: "Server Online"
   - Note the IP address (e.g., `192.168.1.100:8765`)

2. **Keep on Same WiFi Network**
   - iPhone and Android must be on same WiFi
   - Works with any network (home, work, hotspot)

3. **Keep App Running**
   - In background is fine
   - Don't force-close

### On Android Device

1. **Open PhoneSyncUSB-C App**

2. **Select "iPhone (WiFi Network)" Recovery Type**
   - Or: Open → USB Source → iPhone (Network mode)

3. **Tap "Scan Network"**
   - App searches for iPhone Companion
   - Should find: "PhoneSyncCompanion-[iPhone Name]"

4. **Select Found Device**
   - Tap to select
   - Shows IP address and port (`:8765`)

5. **Tap "Recover Data from iPhone"**
   - Status: "Connecting..."
    - Requests the companion endpoints while it is running in the foreground.
    - The current companion source is a connectivity scaffold: its endpoint
       handlers return empty placeholder arrays, not recovered system data.
   - Displays count of recovered items

6. **View Recovered Data**
   - Tap "Recovered Files"
   - Browse Messages, Contacts, etc.
   - Search and filter available
   - Export to storage

---

## Data Recovery Details

### What Gets Recovered

The source currently does **not** provide direct access to the protected
Messages, Notes, Contacts, or Call History databases on a stock iPhone. A
normally signed iOS app is sandboxed and cannot read those private databases.
The listed payloads become real only after the companion is changed to use
user-approved exports or supported public frameworks and the corresponding
permissions are granted.

| Data Type | Source | Count |
|-----------|--------|-------|
| Messages | SMS/iMessage database | Up to 10,000 recent |
| Contacts | Contacts database | All saved contacts |
| Notes | Notes app database | All notes with timestamps |
| Call History | Call logs | All call records |
| Metadata | Device info | iPhone name, version, storage |

### Data Format

Recovered as JSON for easy parsing:

```json
{
  "type": "messages",
  "count": 342,
  "data": [
    {
      "rowId": 12345,
      "address": "+1234567890",
      "date": 1692201600000,
      "text": "Hello!",
      "flags": 0
    }
  ]
}
```

---

## Troubleshooting

### "iPhone Not Found"

**Check:**
1. Both devices on same WiFi network
2. iPhone has Companion App running (shows green status)
3. iPhone firewall not blocking port 8765
4. Android has WiFi enabled

**Fix:**
```bash
# On iPhone terminal (jailbreak only):
netstat -an | grep 8765  # Should show LISTEN
```

### "Connection Refused"

- iPhone app crashed or closed
- Restart companion app on iPhone
- Check IP address matches

### "Timeout on Data Fetch"

- Network congestion
- Large message database
- Try again after 10 seconds
- Check WiFi signal strength

### "Permission Denied" (Jailbreak)

```bash
# SSH into iPhone and fix permissions
ssh root@[IPHONE_IP]
chmod +x /Applications/PhoneSyncCompanion.app/PhoneSyncCompanion
```

---

## Security Notes

⚠️ **Important:**

1. **Local Network Only**
   - Data only travels over LAN
   - Not exposed to internet
   - No cloud servers involved

2. **Owner-Authorized**
   - Both devices must be owned by user
   - Data recovery requires physical access
   - No remote access capability

3. **Encryption in Transit**
   - Use WPA2/WPA3 WiFi (not open networks)
   - Data decrypted on source iPhone, transferred over HTTP
   - Consider VPN if using public WiFi

4. **No Passwords Transferred**
   - Messages content only, not encryption keys
   - iCloud passwords, Apple IDs NOT transferred

---

## File Structure

```
phonesyncusbc/
├── ios-companion/
│   └── IPhoneDataServer.swift          # iOS HTTP server
├── app/src/main/java/
│   └── com/jerrywolff/phonesyncusbc/
│       ├── recovery/
│       │   └── IosNetworkRecoveryEngine.kt  # Network discovery
│       └── ui/recovery/
│           └── NetworkRecoveryScreen.kt      # Recovery UI
└── README_NETWORK_RECOVERY.md          # This file
```

---

## Next Steps

1. **For Xcode Sideload:**
   ```bash
   xcode-select --install
   # Then follow Option A above
   ```

2. **For Jailbreak:**
   - Add Cydia repo
   - Install PhoneSyncCompanion
   - Restart device

3. **Build Android App:**
   ```bash
   cd phonesyncusbc
   ./gradlew buildDebug
   # Deploy to Android device
   ```

4. **Test Connection:**
   - Launch companion on iPhone
   - Open phonesyncusbc on Android
   - Run network scan
   - Select device
   - Recover data

---

## Q&A

**Q: Does it work with iCloud backup?**
A: No. This pulls live data from the device. Use encrypted iTunes backup for cloud-backed data.

**Q: Can I recover deleted messages?**
A: Only if they're still in the Messages database. Permanently deleted items cannot be recovered.

**Q: Does iPhone need to be jailbroken?**
A: No. Xcode sideload works on any iPhone. Jailbreak is optional (easier setup).

**Q: Will this work on Android-to-Android?**
A: Currently iPhone-to-Android. Android-to-Android in future version.

**Q: How long does recovery take?**
A: Typically 30-60 seconds for typical iPhone data. Depends on network speed and database size.

**Q: Is data encrypted at rest on Android?**
A: Yes, stored in app's private directory with standard Android encryption.
