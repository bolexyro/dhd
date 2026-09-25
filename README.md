# DHD

DHD is an Android phone assistant that uses Codex on your computer to help with apps you approve. Set it up once, connect the phone to the desktop companion, then send requests from DHD.

## TL;DR

On a Windows computer, install Git, Node.js 22.13 or newer, and Codex CLI. Install pnpm if it is not already available, then run these commands in PowerShell:

```powershell
git clone https://github.com/bolexyro/dhd.git
cd dhd
npm install --global pnpm # only if pnpm is not installed
pnpm install
pnpm dhd:setup
```

Build and install DHD on your Android phone using [step 4](#4-install-dhd-on-the-phone). If a prebuilt APK is listed under [GitHub Releases](https://github.com/bolexyro/dhd/releases/latest), you can install that instead. Then, from the repository folder, start the companion:

```powershell
pnpm companion:dashboard
```

Open <http://127.0.0.1:8766> on the computer. Keep DHD open, make sure the phone and computer can reach each other over Wi-Fi, find the phone in the dashboard, and approve the desktop connection in DHD.

## What you need

- A Windows computer with [Git](https://git-scm.com/download/win) and [Node.js](https://nodejs.org/en/download) 22.13 or newer.
- pnpm 11. The install command above adds it if needed.
- [Codex CLI](https://learn.chatgpt.com/docs/codex/cli), signed in through the DHD setup step below.
- An Android 11 or newer phone with DHD installed.
- The phone and computer on the same reachable Wi-Fi network. A phone hotspot works if the computer joins it.

If you build the APK from source, you also need JDK 17, Android SDK API 35, and Android platform-tools (`adb`).

## Setup steps

### 1. Clone the repository

Open PowerShell and run:

```powershell
git clone https://github.com/bolexyro/dhd.git
cd dhd
```

If you already cloned DHD, open PowerShell in that repository folder instead. Run the remaining commands there.

### 2. Install pnpm and project packages

Check whether pnpm is installed:

```powershell
pnpm --version
```

If PowerShell says it cannot find `pnpm`, install it with npm:

```powershell
npm install --global pnpm
```

Install the repository packages:

```powershell
pnpm install
```

### 3. Sign in to Codex for DHD

Run:

```powershell
pnpm dhd:setup
```

This installs DHD's assistant guidance at `%USERPROFILE%\.dhd\codex-home\AGENTS.md` and signs in to that isolated Codex home. It creates `%USERPROFILE%\.dhd\codex-runtime` as the companion's working directory. If needed, setup opens a browser for you to sign in. Finish the browser sign-in, return to PowerShell, and wait for `DHD Codex login completed.` If the command says `DHD is already authenticated`, continue to the next step. Setup keeps an existing `AGENTS.md` so your edits are not overwritten.

If browser sign-in is unavailable, run `pnpm dhd:setup -- --device-auth` and follow the link and one-time code it prints. You can rerun `pnpm dhd:setup` later to check the DHD login.

### 4. Install DHD on the phone

Check [GitHub Releases](https://github.com/bolexyro/dhd/releases/latest) for a DHD APK. If one is available, download it on the phone, open it, and follow Android's install prompts. If Android asks, allow your browser or file manager to install apps from this source.

If no APK is listed, build one from this repository: connect the phone over USB, enable USB debugging, then run:

```powershell
cd apps/dhd-android
.\gradlew.bat :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ../..
```

The command `adb devices` should show the phone as `device`. If it shows `unauthorized`, approve the USB debugging prompt on the phone and try again. The built APK is at `apps/dhd-android/app/build/outputs/apk/debug/app-debug.apk`.

Open DHD on the phone and complete its permission prompts. In **Settings → Approved apps**, enable only the apps you want DHD to use; apps are disabled by default.

### 5. Pair DHD with Android Wireless debugging

DHD uses Android Wireless debugging to carry out phone actions. This is a one-time phone setup, separate from connecting the phone to your computer:

1. In Android **Developer options**, turn on **Wireless debugging**.
2. In DHD, open **Settings → DHD phone access → Pair DHD once**.
3. In Android Wireless debugging, choose **Pair device with pairing code**.
4. Enter Android's six-digit code in DHD.

DHD reconnects to phone access automatically while Wireless debugging is available.

### 6. Start the desktop companion and connect your phone

From the repository folder, run:

```powershell
pnpm companion:dashboard
```

Leave this PowerShell window open while using DHD. Open <http://127.0.0.1:8766> in a browser on the same computer. The dashboard starts the companion worker for you.
The command builds the companion and its workspace dependencies before starting, so it also works after a fresh clone.

1. Keep DHD open and connect the phone and computer to the same Wi-Fi network. A phone hotspot is fine if the computer joins it.
2. In the dashboard, select **Refresh phones** and choose your phone.
3. On the phone, open **Settings → Connect desktop companion** and approve the request.
4. Wait for the dashboard to show **Phone connected**.

The phone saves this desktop pairing after approval. When reconnecting later, keep DHD open; the companion will look for the saved phone automatically.

### 7. Send a request

Type a request in DHD on the phone. Keep the desktop companion window running while you use it. The phone controls which apps are allowed and asks you to confirm sensitive actions. The dashboard's **Tools** and **Console** tabs show activity if you need to troubleshoot.

## If the phone does not appear

- Make sure DHD is open on the phone.
- Make sure both devices are on the same Wi-Fi, or connect the computer to the phone's hotspot.
- Check that the network allows devices to communicate with each other. Guest Wi-Fi may block this.
- Use **Refresh phones** again. If a saved pairing is stale, choose the phone and approve a new request in DHD.


## Developer notes

- The Android app lives in [`apps/dhd-android/`](apps/dhd-android/); see its [README](apps/dhd-android/README.md) for Android development details.
- The desktop companion lives in [`apps/dhd-companion/`](apps/dhd-companion/).
- To change the isolated Codex home or companion working directory, set `PHONE_ASSISTANT_CODEX_HOME` or `PHONE_ASSISTANT_CODEX_CWD` before running setup and the dashboard.
- The [`legacy/`](legacy/) folder holds the older USB adb phone-control tooling in its own pnpm workspace. DHD does not use it, and a normal install skips it. See its [README](legacy/README.md).
- If Wi-Fi discovery is blocked, `adb forward tcp:8765 tcp:8765` is available as a loopback development fallback.
