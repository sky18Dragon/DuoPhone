DuoPhone GTA 6 Dual-Screen Assets Directory
==========================================

Bundled assets live in:
  app/src/main/assets/gta/

Optional external runtime override directory (no recompile needed):
  /sdcard/Android/data/com.duophone.app/files/gta_assets/

Bundled files:
  - opening_light.mp4         Daylight opening video (asphalt POV -> handheld phone)
  - opening_dark.mp4          Night-mode opening video
  - idle_hand_phone_light.png Daylight flat-plane +7 deg right-hand idle backdrop
  - idle_hand_phone_dark.png  Night-mode flat-plane +7 deg right-hand idle backdrop
  - dialing_pose_light.png    Daylight active/dialing vignette backdrop
  - dialing_pose_dark.png     Night-mode active/dialing vignette backdrop
  - stage_config.json         Quad homography corners & virtual screen calibration

Override push example:
  adb push stage_config.json /sdcard/Android/data/com.duophone.app/files/gta_assets/
  adb push idle_hand_phone_light.png /sdcard/Android/data/com.duophone.app/files/gta_assets/
