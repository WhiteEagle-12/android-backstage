# Screen policy

The user keeps using the phone while an agent works on it. Every tool that
touches the UI takes an optional `display`; the agent creates a headless
virtual display and does its work there.

1. **Background shell first.** `android.shell`, `pm`, `am`, `settings`,
   `dumpsys`, `cmd` finish most tasks without any display.
2. **UI on the virtual display.** `android.vd_open {query}` starts the display
   if needed, launches the app there, and returns `display=N`. Pass that `N`
   to `tap`, `tap_text`, `type`, `swipe`, `key`, `ui_tree`, `screenshot`.
   `android.vd_screenshot` reads that display's own frame buffer. Audio still
   plays from the phone.
3. **Real screen only when the task needs the user to see it there.** Prepare
   everything on the virtual display, then `android.vd_handoff {package}` moves
   the task to the phone screen as the last step.
4. Never `HOME`, wake, or screenshot the real screen as a side effect. If an
   app refuses the virtual display (some banking/DRM apps render black), say
   so and ask before using the real screen.

The display is trusted, public, and keeps its own focus, so taps there never
steal focus from what the user is doing. It is released automatically after
five idle minutes so the phone can doze.

## Act, do not check

- No status, help, dumpsys, screenshot, or focus check before the action.
- When the action succeeds, stop. No confirmation screenshot.
- Retry a failed action once, then report what failed.
- Never switch to a different device than the one named.
