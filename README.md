# Away

Spend less time on your phone, without missing what matters.

Away is an Android app that blocks the apps that pull you in, holds notifications while you focus, and gives you a calm, text-only home screen that counts your time away. Everything runs on your phone.

## Features

- **Focus.** One switch, an optional daily schedule, and two choices: hide and block your distracting apps, and hold notifications. Need an app? Wait 30 seconds and it unlocks for 10 minutes.
- **Quiet notifications.** During Focus, notifications wait in a digest and come back when Focus ends, even if your phone restarts. Urgent ones still come through: verification codes, people you mark as VIPs, calls to call back, emergencies, travel changes and security alerts.
- **Optional AI triage.** Add your own Anthropic or OpenRouter key to judge everything the rules do not catch.
- **A calm home screen.** Time since you last opened a distracting app, with a little encouragement, a few pinned apps as text, and instant search. Distracting apps disappear during Focus.
- **Lock screen timer.** How long since you last used your phone.
- **Weekly stats.** Distracting-app opens per day, blocked attempts, your longest stretch away and how often you pick up your phone.

## Install

1. Download `Away-1.0.0.apk` from [Releases](../../releases) on your Android phone (Android 10 or newer).
2. Open it and allow installing from your browser or file manager when asked.
3. Open Away and follow the setup. It explains each permission before asking.

Because Away is installed from a download, Android hides its Accessibility switch until you allow it: open **App info** for Away, tap **⋮**, choose **Allow restricted settings**, then turn Away on in **Accessibility**. The setup walks you through this.

## Permissions

| Permission | Why |
|---|---|
| Accessibility | See which app is in front, to block your chosen apps during Focus, count time away and lock the screen on double tap. It never reads your messages, typing or screen content. |
| Notification access (optional) | Hold notifications during Focus and bring them back afterwards. |
| Notifications | The lock screen timer and urgent alerts. |
| Battery unrestricted (optional) | Keeps blocking and timers running. |
| Home app (optional) | Use Away's home screen. |
| Internet | Only used if you add your own AI key. |

## Privacy

Nothing leaves your phone unless you add your own AI key. See [PRIVACY.md](PRIVACY.md).

## Build from source

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

Release builds are signed with a key from `keystore.properties` (not in the repository):

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=away
keyPassword=...
```
