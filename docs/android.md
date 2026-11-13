# The Android app

A native app for your phone that talks to your MiBot hub: the bot list, chat with live steps, approval cards, voice, and notifications when a bot finishes or is waiting for you. It is in `android/`.

## Put it on a phone

You need a computer with the Android SDK (Android Studio installs it) and JDK 17 or newer.

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # where your SDK is; this is the usual place on a Mac
./gradlew :app:installDebug                                    # with the phone plugged in and USB debugging on
```

Without a cable, build the file and send it to the phone: `./gradlew :app:assembleDebug` writes `app/build/outputs/apk/debug/app-debug.apk`.

## Add the phone to your hub

Open MiBot on a computer, go to **Settings › Machines › Add a device** and press **Add**. In the app, press **Scan the code** and point the phone at the QR code. Or choose **Type the code instead** and enter the hub's address and the short code, both shown next to the QR. A code works once and lasts ten minutes.

The phone has to be able to reach the hub. For a hub at home that means the same network, or Tailscale on the phone.

## What is where

| On the phone | What it does |
|---|---|
| Bot list | Your primary bot at the top, the others under it with what they last said. A green dot means working, an amber one means waiting for you. |
| Chat | The thinking block sits under the top bar: what the bot is doing, steps so far, time, and Stop. Tap it for the steps of this turn. Finished runs of steps fold into one line. |
| Cards | Approve or deny an action, answer a question, connect an app, send or discard a draft. |
| Message box | Type, dictate with the microphone, or (with the box empty) start a voice call. |
| Bot profile | Routines with on/off switches, the Library (a file saves to Downloads), a look at its computer, its apps, and whether this phone notifies you about it. |
| Settings | Connect apps, notifications, sign out. Models, Machines, the bot computer and permissions open the web app inside the app. |

## Notifications

A hub on your own machines has no push service, so the app keeps one connection to the hub open in the background. Android shows a quiet "Connected to your hub" notice while it does; you can hide that channel in the phone's settings. Turn notifications off in the app's Settings and the background connection stops.

## For developers

```text
android/core/   plain Kotlin: the hub's API, live events, the state they fold into, voice call logic. No Android in it.
android/app/    the app: Jetpack Compose screens, microphone and speaker, notifications.
```

`core` builds and tests without the Android SDK:

```bash
cd android
./gradlew -p core test                                              # unit tests
./gradlew -p core test -PhubUrl=http://127.0.0.1:8787 -PhubCode=…   # also runs against a real hub, with a fresh pairing code
```

To try the app without your real hub or a real agent, start a practice hub. It prints an address and a one-time code for the app's "Type the code instead" screen, its bots answer through a stand-in agent, and everything it stores is removed when you stop it:

```bash
node scripts/dev-hub.mjs --url http://10.0.2.2:18891    # 10.0.2.2 is how the Android emulator reaches this computer
```

The characters and icons are MiBot's own, written as arithmetic in `scripts/gen-avatars.mjs` and `scripts/gen-icons.mjs`. Running them writes the tables both the web app and the Android app draw from, plus the app icon and the favicon.
