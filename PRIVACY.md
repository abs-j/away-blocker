# Away privacy policy

Last updated: 14 November 2026

Away is built to work entirely on your phone. There are no accounts, no analytics, no ads and no servers run by Away.

## What Away reads

- **Which app is in front**, through Android's Accessibility service, so it can block the apps you chose, count your time away from them and lock the screen on double tap. It also reads app names you tap on a home screen and the Recents screen, to close a blocked app. It does not read your messages, what you type, or other screen content.
- **Notifications**, only if you turn on notification access, so Focus can hold them and bring them back later.
- **The list of installed apps**, to let you pick distracting apps and to power the home screen.

## What Away stores

All of this is stored only on your phone, in Away's private storage:

- Your settings, distracting apps, pinned apps and schedule.
- Held notifications and the digest (title, text, app, time). Digest entries are deleted after 24 hours, and you can clear them at any time.
- Daily counts for the stats card (opens, blocked attempts, phone pickups), kept for three weeks.
- Your AI key, if you add one, encrypted with the Android Keystore.

Uninstalling Away deletes all of it. Away turns off Android backup, so none of it is copied to the cloud.

## What leaves your phone

Nothing, unless you add your own AI key. If you do, then for notifications that the on-device rules do not already decide, Away sends the app name, the notification title, up to 300 characters of its text and your VIP names to the provider you chose (Anthropic or OpenRouter) to ask whether it is urgent. Before sending, it removes numbers of four or more digits, email addresses and links. Notifications from apps you mark as private are never sent. The provider's own privacy policy applies to that request. Away limits this to 200 requests a day.

## Contact

Questions or concerns: open an issue at https://github.com/abs-j/away-blocker.
