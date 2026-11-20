# Xiaomi Mi TV debloat + Apple TV style launcher

TV: **Xiaomi MiTV-MOOQ0** (`croods`), Android TV 11 (API 30), 1.7 GB RAM, 1920×1080,
firmware `RTM4.220307.164/8835`. Network ADB on `192.168.224.196:5555`.

Everything here is reversible. Nothing was uninstalled: apps were only disabled
(`pm disable-user --user 0`), and two were also suspended.

| File | What it does |
|---|---|
| `restore.sh` | Undoes everything: re-enables and unsuspends every app, puts the stock launcher back as home, resets DNS |
| `debloat.sh` | Re-applies the debloat from the lists in `backup/` (after `restore.sh`, an OTA or a factory reset) |
| `install.sh` | Builds the launcher and installs it as the default home screen |
| `backup/` | State before any change: package lists, `getprop`, all `settings` tables, HOME activities |
| `backup/disabled_by_debloat.txt` | Every package the debloat disabled (read by both scripts) |
| `backup/suspended_by_debloat.txt` | Packages also suspended (see "Netflix and Prime" below) |
| `launcher/` | Source of the launcher (Java, AndroidX RecyclerView) |
| `tools/shot.sh` | Screenshot from the TV (strips the vendor log lines this firmware prints before the PNG) |
| `tools/disable.sh` | Disables packages one `pm` call at a time and logs them for `restore.sh` |
| `screenshots/` | Every screenshot taken while verifying |

All scripts take the TV address as the first argument and default to `192.168.224.196:5555`.
They are bash scripts (Git Bash on Windows works). Network ADB must be on for any of them
to work; see "Turning ADB back on".

## What was disabled

**Xiaomi telemetry:** `com.miui.tv.analytics`, `com.xiaomi.statistic`,
`com.xiaomi.tvqs.overseas` (log/dropbox uploader), `com.google.android.feedback`

**Xiaomi content and ads:** `com.mitv.tvhome.atv` (PatchWall), `com.mitv.tvhome.michannel`,
`com.mitv.tvhome.mitvplus` (Mi TV+), `com.google.android.tvrecommendations`,
`com.xm.webcontent`, `fusion.android.tv.demo` (store demo),
`android.autoinstalls.config.xiaomi.amelie` (Play auto-installs)

**Background services:** `com.xiaomi.mitv.tvmanager` (cleaner/booster),
`com.mitv.dream` (Mi screensaver; Google's Ambient mode stays), `com.xiaomi.mitv.manualhelp`,
`com.android.printspooler`, `com.mitv.milinkservice` (MiLink casting, a duplicate of Chromecast)

**Apps you don't use:** Netflix, Prime Video, RaiPlay, Mediaset Infinity, discovery+, TikTok,
YouTube Music, SoundCloud, Play Movies, Play Games, Amazon Alexa

**Stock launcher:** `com.google.android.tvlauncher` (replaced by the new launcher)

**Kept on purpose:** LiveTV and the Mediatek TV input/tuner services (HDMI, AV, antenna and the
remote's Input menu), `com.mi.bluetooth` (remote), `partnercustomizer` (remote's shortcut keys),
`com.mitv.overseaservice`, Gboard, Play Store, Play Services, Google Assistant, Chromecast,
Miracast, the Xiaomi media apps and firmware updates (`com.xiaomi.mitv.updateservice`).

### Netflix and Prime

The firmware turns Netflix and Prime Video back on at every boot, so they are also
**suspended** (`pm suspend`). A suspended app can't run and the launcher hides it.

## Re-enabling one app

With ADB on:

```sh
adb connect 192.168.224.196:5555
adb shell pm enable com.netflix.ninja
adb shell pm unsuspend com.netflix.ninja   # only Netflix and Prime need this
```

Then delete its line from `backup/disabled_by_debloat.txt`, and from
`backup/suspended_by_debloat.txt` if it's there, so `debloat.sh` doesn't disable it again.

Without a computer: **Settings → Apps → See all apps → Show system apps**, pick the app,
then **Enable**. A suspended app (Netflix, Prime) still needs the `pm unsuspend` command.

To undo everything at once: `./restore.sh`

## The launcher

Black screen with a greeting, a thin clock and the date (Outfit typeface, SIL Open Font
License, `launcher/app/src/main/assets/fonts/OFL.txt`); Ingressi / Wi-Fi / Impostazioni
pills top right; three HDMI source cards under them (see "HDMI source cards"); your four dock apps in a rounded band at the bottom, with the focused app's
name above them in a glow coloured from its banner; all other apps in a 4-column grid below
(press Down).

Remote:
- **Up / Down** move exactly one row. Up from the dock goes to the pills. Down from the dock
  goes to the first grid row, same column.
- **Left / Right** stop at the end of a row.
- **Back** from the grid jumps to the dock. **Home** on the home screen also goes to the dock.
- **Right** from the last dock app moves to the source cards (Up/Down between them, Left back
  to the dock, Up from the first one to the pills). **OK** switches the TV to that input.
- **Ingressi** opens a list of HDMI 1–3, AV and Live TV. A green dot means a device is
  connected. It switches through Android's TV input framework, the same as the remote's
  Input button.
- The last focused tile is remembered, including across reboots.
- **Hold OK** (about half a second) on an app for its menu:
  - in the dock: **Sposta** (Left/Right moves it, OK or Back to finish), **Rimuovi dalla
    barra**, **Info app**, **Disinstalla**;
  - in the grid: **Aggiungi alla barra** (greyed out when the dock already has 4 apps),
    **Info app**, **Disinstalla**.

  Preinstalled apps have no Disinstalla (Android only lets you disable them; use Info app →
  Disattiva). Uninstalling always asks you to confirm. Dock changes are saved to the same
  `dock.txt` described below.

The Wi-Fi pill says "Wi-Fi" rather than the network name. Android 11 only shows the name to
apps while Location is on. On this TV, turning Location on brings up Google's "location
accuracy" consent screen, and saying no switches Location off again. If you ever accept it,
the pill shows the network name automatically.

### HDMI source cards

Three cards on the right, top to bottom: **Nintendo Switch (HDMI 1)**, **PS5 (HDMI 2)**,
**Windows 11 PC (HDMI 3)**. OK on a card switches the TV to that input, the same way the
Ingressi list and the remote's Input button do. A card is in colour with a green dot while
the device on that port is on, and grey and dimmed with a grey dot when it is off or
unplugged. It updates on its own through Android's TV input callback (no polling): "on" is
`TvInputManager.INPUT_STATE_CONNECTED`, "off" is standby or disconnected.

The card to port mapping is the `SOURCES` array at the top of `HomeActivity.java` (label,
HDMI port number, image). The port number is the position in the TV's HDMI list, so if you
move a cable, change that number. The images are in `launcher/app/src/main/assets/sources/`
(`ps5.jpg`, `switch.webp`, `pc.jpg`); replace a file to change a card's picture. They are
the manufacturers' logos, used here only to label the inputs on a private home screen.

### Changing the dock apps or their order

Easiest: hold OK on an app and use the menu (see above). From a computer: put one package
name per line, left to right, in a text file and push it. The launcher picks it up the next
time you go home.

```sh
printf 'com.google.android.youtube.tv\ntv.pluto.android\ncom.ir0.iptv\ncom.spotify.tv.android\n' > dock.txt
adb push dock.txt /sdcard/Android/data/com.sev.atvhome/files/dock.txt
```

Delete that file to go back to the built-in order. To change the built-in order, edit
`launcher/app/src/main/res/values/config.xml` and run `./install.sh`.

To find an app's package name:
`adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER`

### Wallpaper

Default is pitch black. To use a picture (JPEG or PNG, about 1920×1080):

```sh
adb push my-picture.jpg /sdcard/Android/data/com.sev.atvhome/files/wallpaper.jpg
```

It is dimmed so text stays readable. Remove it with
`adb shell rm /sdcard/Android/data/com.sev.atvhome/files/wallpaper.jpg`.

### Rebuilding / reinstalling

`./install.sh` (needs the Android SDK at `%LOCALAPPDATA%\Android\Sdk` and JDK 17+).
It pushes the APK and runs `pm install -r` over the shell, then sets it as home.
`./install.sh <ip:5555> --no-build` reinstalls the last build.

Without the Windows toolchain (Linux, no JDK or SDK installed) the build also works inside a
`eclipse-temurin:17-jdk` container: install the command-line tools, `platforms;android-34`
and `build-tools;34.0.0` into a cache volume, point `sdk.dir` at it in a copy of `launcher/`
and run `./gradlew assembleDebug`. Keep the debug keystore (`ANDROID_USER_HOME`) in that
volume: an APK signed with a different debug key can't be installed over the one on the TV
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only way round is `adb uninstall`, which also
deletes `dock.txt` and `wallpaper.jpg` from `/sdcard/Android/data/com.sev.atvhome/files/`
(back them up first).

To go back to the stock launcher: `adb shell pm enable com.google.android.tvlauncher`.
The stock launcher declares a higher HOME priority, so it takes over again as soon as
it's enabled. Then `adb uninstall com.sev.atvhome` if you like.

## Ad-blocking DNS

Private DNS is set to AdGuard (`dns.adguard-dns.com`). It blocks ad and tracker domains for
the whole TV, but **not YouTube ads** (they come from the same servers as the videos). Undo:

```sh
adb shell settings put global private_dns_mode opportunistic
```

or Settings → Network → Private DNS → Automatic.

## Notes about this TV

- Screenshots of Live TV and HDMI come back empty (0 bytes); that's the TV protecting
  video, not a fault. Check what's on screen with
  `adb shell dumpsys activity activities | grep mResumedActivity`.
- `screencap` prints vendor log lines before the PNG data; `tools/shot.sh` strips them.
- The setup wizard (`com.google.android.tungsten.setupwraith`) also declares HOME, but its
  home activities are disabled after first setup, so it didn't need touching. Your saved
  Wi-Fi networks belong to the system, not to the wizard.
- A firmware update may re-enable some apps. Run `./debloat.sh` again afterwards.

## Turning ADB back on

ADB was switched off at the end for security (port 5555 has no encryption). To use any of
the scripts again: **Impostazioni → Preferenze del dispositivo → Opzioni sviluppatore →
Debug USB** on. On this TV that also opens network ADB on port 5555. If Opzioni
sviluppatore is missing, go to Preferenze del dispositivo → Info and press **Build** seven
times. When you connect, accept the "Consentire il debug USB?" prompt on the TV.

When you're done: in Opzioni sviluppatore, use **Revoca autorizzazioni debug USB**, switch
**Debug USB** off, and restart the TV. From the computer, `nc -vz 192.168.224.196 5555`
(or `Test-NetConnection 192.168.224.196 -Port 5555` in PowerShell) should then fail.
