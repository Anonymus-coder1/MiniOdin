# Mini Odin

A lightweight Odin-like flashing tool for Samsung phones, running on Android (OTG).
Pure Java + XML, built with AIDE / Apktool M (no Gradle). Package: `com.MiniOdin`.

## Features
- Lightweight flashing tool
- Reboot the other phone to Download Mode with one button (it must have ADB debugging enabled)
- Dark / light mode
- Log sharing as `.txt` or `.log`
- French / English language switch
- PIT is read automatically on connection

## How to use
1. **Reboot to Download Mode**: open the app, you land on the start screen. Plug the target phone (USB debugging enabled), accept the ADB prompt on it, then tap the reboot button.
2. When the target phone is in Download Mode, tap **Connect (OTG)** and accept the USB authorization to talk to Odin mode.
3. **Analyze boot**: shows kernel size, version, build time and clang version, plus ramdisk info, so you can tell which device an image belongs to. It shows a picture of the phone when possible (Wikipedia by default; paste your Google API key + `cx` for Google Images).
4. **Free flash**: flash a single file to the partition you choose (e.g. a recovery, `up_param.bin`...), on slot `_a`, `_b` or `-` (non A/B devices).
5. Flash `.img` / `.bin` directly (no archive needed) or `.tar` / `.tar.md5` (lz4 supported) in the BL / AP / CP / CSC / USERDATA slots. Remove or add files as you like.
6. Options (each one shows a warning explaining the risk): Auto Reboot, Skip MD5, Flash to Slot B, Erase userdata. TFlash and Repartition are listed but not implemented yet.

## Safety / status
- Critical partitions (preloader, nvram, persist, ...) are refused.
- The write protocol and the ADB reboot are experimental: test on a spare device first.

## Build
Open in AIDE (or any tool that handles `AndroidManifest.xml` + `src/` + `res/` + `libs/`). minSdk 21, targetSdk 28. Put `lz4-java-1.12.0.jar` in `libs/` (see `libs/LISEZMOI.txt`); it is required for `.lz4` files.
