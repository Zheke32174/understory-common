# Setting up the BlackArch tools to run these runbooks

The BlackArch runbooks in this directory each need 1-3 specific
offensive tools. You don't need a full BlackArch install for any of
them — adding BlackArch's unofficial repo to your existing Arch
system is sufficient. This works inside the project's arch container
without disturbing the host.

## Prerequisite: the arch container

If you're on a fresh GitHub Codespace (or any host that doesn't have
the project's arch container yet), follow [docs/deploy-arch-container.md](../../../docs/deploy-arch-container.md)
first. It's three scripts:

```bash
sudo bash scripts/bootstrap-arch-chroot.sh        # ~10 min, pulls Arch packages
sudo bash scripts/promote-chroot-to-pid1.sh       # ~30 sec, makes systemd PID 1
sudo bash scripts/setup-skillstack.sh             # ~2 min, optional but standard
```

After step 2 you can enter the arch container with:

```bash
/home/user/understory/scripts/arch-session /bin/bash
```

If the arch container already exists at `/opt/arch`, skip ahead.

## Add the BlackArch repo to pacman

Inside the arch container (or any Arch system):

```bash
# 1. Download and run BlackArch's official strap.sh
curl -O https://blackarch.org/strap.sh
echo "5ea40d49ecd14b2e9683815ed1a7e163b1a3ee4c  strap.sh" | sha1sum -c -
chmod +x strap.sh
sudo ./strap.sh
sudo pacman -Syyu
```

The strap.sh adds:
- BlackArch's signing key to pacman's keyring
- An entry in `/etc/pacman.conf` for the BlackArch repo
- A pinned mirror list

After this, normal `pacman -S` works for any BlackArch package without
needing to switch to a BlackArch ISO or chroot. Your existing Arch
packages remain unchanged.

The SHA1 above pins the strap.sh as of mid-2024; check
https://blackarch.org/downloads.html for the current hash if it's
stale.

## Tool → runbook map

Install only what you need:

| Runbook | Tool | Install command |
|---|---|---|
| 01 frida | frida-tools (laptop) + frida-server (rooted phone) | `sudo pacman -S frida-tools` (and download frida-server APK from frida releases) |
| 02 repackaging | apktool, apksigner | `sudo pacman -S apktool android-sdk-build-tools` |
| 03 xposed | LSPosed (Magisk module on rooted phone) | No package — install via Magisk on the test phone |
| 04 mitm | mitmproxy | `sudo pacman -S mitmproxy` |
| 05 screencap | scrcpy + adb (you already have adb) | `sudo pacman -S scrcpy` |
| 06 tapjack | (build a small overlay APK yourself, no external tool) | n/a |
| 07 a11y | (build a small a11y service APK yourself, no external tool) | n/a |
| 08 clipboard | (uses Gboard / Samsung keyboard directly, no external tool) | n/a |
| 09 adb-backup | adb only | (already have it) |
| 10 static analysis | jadx, apktool, strings | `sudo pacman -S jadx apktool` (strings is in coreutils) |
| 11 permissions | aapt2 | (in android-sdk-build-tools, already installed for the project) |
| 12 debugger | adb | (already have it) |
| 13 signature | apksigner | (in android-sdk-build-tools) |
| 14 strings | strings, unzip | (coreutils + base) |
| 15 memdump | gcore (gdb) on rooted phone | `sudo pacman -S gdb` (laptop side); root + gdbserver on phone |

## Single-shot install for everything you'd want

If you want to set up the whole test workbench at once after running
strap.sh:

```bash
sudo pacman -S \
    frida-tools \
    apktool \
    android-sdk-build-tools \
    mitmproxy \
    scrcpy \
    jadx \
    gdb
```

That's about 800 MB total (frida-tools is the heavy one because of its
Python deps).

## Phone-side setup for the rooted-tests

Tests 01 (Frida late-attach), 03 (Xposed), and 15 (memdump) require a
rooted Android device. **Do not use your daily-driver phone.** A cheap
secondary device or an Android emulator instance is the right test bed.

For the emulator path:

```bash
# Use Android Studio's AVD or just run avdmanager from the project's
# Android SDK install (we already have one at /opt/android-sdk):
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'system-images;android-35;google_apis;x86_64'
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n test35 \
    -k 'system-images;android-35;google_apis;x86_64'
$ANDROID_HOME/emulator/emulator -avd test35 -writable-system
```

The `-writable-system` flag is the rooted-emulator equivalent. Plus
`adb root` works on emulator builds out of the box.

## What this setup gives you

After this, you can walk through the ⏳ runbooks in the matrix and
convert them to ✅ on real device sessions. The interactive ones each
have a "Setup" section that links back to the install commands above
so you don't have to flip back here.

## What this setup does NOT give you

- A safe environment for running these tools against arbitrary apps.
  These are real offensive-security tools. Run them only against
  passgen and against test apps you've built yourself.
- Legal cover for redistribution. Some BlackArch packages have
  licensing constraints; setting up locally for personal testing is
  fine, redistributing modified APKs of third-party software is not.
- A replacement for OS-layer hardening. BlackArch tools run in your
  Arch userspace; they're testing passgen's userspace defenses against
  userspace attacks. Kernel-level threats are out of scope for this
  matrix and for these tools.

## If you don't want a full BlackArch repo on your system

Every tool above is also available standalone:

- frida-tools: `pip install frida-tools`
- apktool: jar from https://apktool.org
- mitmproxy: `pip install mitmproxy`
- scrcpy: GitHub releases page, or `sudo pacman -S scrcpy` (in regular Arch repo)
- jadx: GitHub releases page

Skipping strap.sh and pip-installing piecewise is the more conservative
path. The downside is you don't get pacman-tracked updates.
