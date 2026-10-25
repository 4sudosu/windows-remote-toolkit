# Mimo Pro - Changes Plan

## App Changes

1. **LauncherActivity** - Only "Connect to Server" option, run on all interfaces (0.0.0.0)
2. **MainActivity** - Remove local server hosting, tabs, settings panel, developer links
3. **DeviceActionsActivity** - Keep only:
   - Screenshot (both Normal and AI mode)
   - Live screen
   - Input Paragraph
   - Control Cursor (ScreenControlActivity - already exists!)
4. **menu_main.xml** - Remove launcher, stop_server, web actions
5. **activity_main.xml** - Remove settings tab, simplify to just devices
4. **SettingsActivity** - Remove theme, app icon, local server, notification icon, developer links
5. **activity_settings.xml** - Simplify settings to minimal
6. **Strings** - Rename app to "Mimo Pro", update all strings
7. **Mimo Pro variant** - All features + extract password from browser feature

## Server Changes
- Update server.js - Brand to Mimo Pro
- Update dashboard/app.js - Brand to Mimo Pro

## Setup Changes
- Update RuntimeBroker.wxs - Change name to Mimo Pro
- Update installer.iss - Change name to Mimo Pro

## Settings to Remove:
- Owner details / "Created by" fingerprints
- App icons custom
- Theme custom
- Local server
- Notification icon
- "Listening to server" notification