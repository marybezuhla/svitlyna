#!/usr/bin/env bash
# Every permission in the APK must be on this list; anything else (internet above all) fails.
set -euo pipefail
APK="$1"
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
"$AAPT" dump permissions "$APK" > permissions.txt
cat permissions.txt
grep -q '^package:' permissions.txt || { echo "::error::Could not read permissions"; exit 1; }
# Photos and videos only (Android 13+), the older single storage read permission (Android 12 and lower),
# the photos the person picked (Android 14+), and the optional "media management" special access
# (Android 12+: edits and moves without a confirmation window each time; switched on only by the person),
# the location inside photos (so copies keep it), and the optional screenshot hints (a notification,
# re-armed after a restart).
ALLOWED=" android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO \
android.permission.READ_MEDIA_VISUAL_USER_SELECTED android.permission.READ_EXTERNAL_STORAGE \
android.permission.MANAGE_MEDIA android.permission.ACCESS_MEDIA_LOCATION \
android.permission.POST_NOTIFICATIONS android.permission.RECEIVE_BOOT_COMPLETED \
android.permission.USE_BIOMETRIC android.permission.USE_FINGERPRINT \
ua.svitlyna.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION "
for p in $(grep -oE "name='[^']+'" permissions.txt | sed "s/name='//;s/'//"); do
  case "$ALLOWED" in
    *" $p "*) ;;
    *) echo "::error::Unexpected permission: $p"; exit 1 ;;
  esac
done
echo "Permissions OK"
