# "Away": Android app blocker (v1)

**Platform:** Android only, native Kotlin + Jetpack Compose, sideloaded APK. Soft enforcement (easy to disable).

## 1. Instant block, no preview, quit
- AccessibilityService detects a blocked app opening, immediately goes Home, and covers the screen with an opaque overlay so no app content is visible.
- Then kills the app's background process (`killBackgroundProcesses`) so it cold-starts next time. True force-stop is not possible on Android.
- Blocked list is user-editable (Instagram, TikTok, X, YouTube, Reddit by default). Phone, Messages, Maps, Settings are never blocked.
- Blocking is on or off via a manual toggle plus one daily schedule (start/end time).
- Unlock: open this app, wait a 30 second countdown, then blocking pauses for 10 minutes.

## 2. Time-away indicator
- Floating overlay pill showing time since you last used a blocked app (e.g. `3h 12m`).
- Resets the moment any app on the distracting list is opened and not blocked (Focus off, paused, or app hiding unticked), and stays at 0 while you use it. Blocked attempts do not reset it.
- Also shown in an ongoing notification on the lock screen, but only once the phone has sat untouched for a chosen time (default 1 hour, adjustable in Focus; 0 = always). Hidden while the phone is in use.

## 3. Notification triage
- During focus, suppress all notifications and store them.
- Classify each as urgent or not with a cheap LLM (Claude Haiku 5.5, `claude-haiku-5-5`):
  1. Skip the LLM for obvious cases: calls, alarms, OTP/2FA = urgent; known promo/social-like categories = not urgent.
  2. Everything else goes to Haiku with a short fixed prompt. Input: app name, sender, title, text (truncated to ~300 chars), plus your VIP contact names. Output: strict JSON `{"urgent": bool, "reason": "<8 words"}`, `max_tokens` ~40, temperature 0.
  3. Prompt defines urgent as: needs a human response or action within about an hour, or time-sensitive money/safety/travel/health. Marketing, social activity, and news are never urgent.
  4. Fail-safe: on timeout (3s), no network, or bad JSON, treat as urgent if sender is a VIP, otherwise queue to digest and retry once.
- Urgent: re-post as a distinct high-priority alert showing the reason. Everything else goes to a digest screen.
- Cost/latency control: classify at most once per notification, dedupe identical text, batch bursts from the same chat into one call, cap daily calls.
- Privacy: notification text is sent to the API. Redact numbers 4+ digits, emails, and links before sending; never send notifications from a user-chosen "private apps" list (banking, health by default); no logging of content. Feature is opt-in with a clear disclosure.
- API key: entered by you in settings, stored in Android Keystore-backed encrypted storage. Never hardcoded in the APK. (A small proxy server is the upgrade if this is ever distributed.)
- No on-device model and no learning loop in v1.

## 4. Launcher (Olauncher-style, same APK)
- `LauncherActivity` is a HOME activity; user sets Away as default home app. `MainActivity` stays as settings (long-press home to open).
- Home: clock, date, large time-away counter, digest line (`N urgent · M held`, taps to Digest), pinned apps as plain text. Pure black, text only.
- Focus mode: while blocking is active, home shows only the first 4 pinned apps and blocked apps are hidden from home and drawer until the existing unlock pause.
- Swipe up: search-first drawer (prefix, word prefix, substring, initials, subsequence; a lone match auto-opens after 400 ms unless you keep typing). Swipe left: camera (or chosen app). Swipe right: phone (or chosen app). Double tap: lock screen (accessibility action).
- Long-press an app: pin, rename, per-app launch delay (off/5/10/15 s with cancel), set as swipe app, hide. Hidden apps are restored in the Launcher tab of settings.
- Cut: intent prompt, daily wallpaper.

## 5. Focus mode
- One Focus switch and one optional daily schedule replace the separate Blocking and Hold settings.
- Two checkboxes choose what Focus does: hide distracting apps (blocker + launcher hiding) and hold notifications.
- During a hold, new urgent notifications break through once as a high-priority alert; everything else returns when Focus ends.
- The 30-second unlock pauses only app hiding for 10 minutes; notifications stay held.
- Existing installs migrate once: old Blocking and Hold settings map onto Focus and its two checkboxes.

## 6. Screen time
- Usage tab (Usage access permission): today's total, pickups, 7-day bars, per-app time today.
- Tap an app to set a daily limit; reaching it blocks the app until midnight. Distracting-apps list sits behind a toggle on the same tab.

## 7. Digest quick actions
- Each digest item has chips: do not hold that app for 30 min, 1 hour, 4 hours or always. Applies at once (held items for that app are released) and lists under "Not held" with a Hold again button.

## Screens
Home (counter, on/off), Blocked apps + schedule, Digest, Permissions setup.

## Permissions
Accessibility, Notification access, Display over other apps, Usage access, Contacts, Battery optimization exemption.

## Milestones
1. Spike: detect, go Home, overlay, measure flash on your phone.
2. Block list, toggle, schedule, unlock.
3. Indicator.
4. Notification triage: capture/suppress/digest, then Haiku classifier with rule shortcuts.

## Cut from v1
Usage limits, stats and streaks, second counter, on-device model, feedback learning, tiered strictness, location/NFC triggers, multiple sessions.

## Main risks
Android 14+ ignores `killBackgroundProcesses` for other apps, so "quit" is not possible there; the overlay plus Home action is the whole mechanism. YouTube may enter picture-in-picture. Recents may show a thumbnail of the blocked app. Notifications are visible briefly before being cancelled (DND filter not implemented).
Brief flash before Home on some devices; manufacturer battery killers stopping services; process kill is partial.
