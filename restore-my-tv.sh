#!/usr/bin/env bash
# Undo everything done to TV PAUL (Xiaomi MiTV-MOOQ1): re-enable every disabled app, bring back the
# stock Google TV home screen and Mi screensaver, reset animation speed, remove the custom launcher.
# Picture settings go back to their old values (applied by the TV at the next reboot, or check
# Réglages > Image).
# Usage: ./restore-my-tv.sh            (TV on, same Wi-Fi, USB debugging on)
set -euo pipefail
cd "$(dirname "$0")"
STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh examples/xiaomi-mitv-mooq1/debloat.txt
printf '%s\n' \
  "settings put global window_animation_scale 1.0" \
  "settings put global transition_animation_scale 1.0" \
  "settings put global animator_duration_scale 1.0" \
  "settings put secure screensaver_components com.mitv.dream/com.mitv.dream.MiDream" \
  "settings put global picture_backlight 69" \
  "settings put global picture_brightness 46" \
  "settings put global picture_contrast 50" \
  "settings put global picture_saturation 55" \
  "settings put global picture_sharpness 18" \
  "settings put global picture_color_temperature 3" \
  "settings put global tv_picture_advance_video_dnr 2" \
  "settings put global tv_picture_advance_video_mjc_effect 2" \
  "settings delete secure enabled_accessibility_services" \
  "settings put secure accessibility_enabled 0" \
  "pm uninstall com.mihir.tvhome" \
  "input keyevent KEYCODE_HOME" \
  | tools/venv/bin/python -I tools/tv.py --batch
echo "TV restored to how it was before."
