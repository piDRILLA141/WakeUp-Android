#!/usr/bin/env bash
# Read-only diagnostics. Does not install, force-stop, reboot or alter phone settings.
set -euo pipefail
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell dumpsys package ru.podyom
adb shell dumpsys alarm | rg -C 4 'ru.podyom' || true
adb shell dumpsys activity services ru.podyom
adb shell dumpsys notification | rg -C 3 'ru.podyom' || true
