# Third-Party Notices

Duo Phone is written from scratch as a modular Android Kotlin/Compose project. The following open-source projects were consulted during technical research and protocol design:

## MIT-Licensed References
1. **CardDock (`Godles-lab/usb-modem-sms`)** — MIT License
   - Repository: <https://github.com/Godles-lab/usb-modem-sms>
   - Commit audited: `8fe736a1d95a7a2daaedbdc6b5d614b4679236d0`
   - Consulted for: Android USB Host API probing flow for `0x2CA3:0x4006` / `0x2C7C:0x0125`, CDC `SET_CONTROL_LINE_STATE` DTR/RTS handshake, IMEI-keyed USB config backup design, and OTP extraction heuristics.

2. **MaVo (`moluncn/mavo`)** — MIT License (host application & user-space PCM bridge)
   - Repository: <https://github.com/moluncn/mavo>
   - Commit audited: `0443dfdaf8aec086fd76ba2ee9152fd908114524`
   - Consulted for: QDC507 reversible module voice runtime design (`alsaucm_test`, `libql_lib_audio.so.1`, `/sys/class/android_usb/f_audio/audio_enable`, direct USB ADB wire protocol, SMS PDU encoding/decoding & call AT/URC state parsing).

3. **PocketJS (`pocket-nexus/pocketjs`, `v0.12.0`)** — MIT License ([`docs/pocketjs/licenses/PocketJS-MIT.txt`](docs/pocketjs/licenses/PocketJS-MIT.txt))
   - Repository: <https://github.com/pocket-nexus/pocketjs>
   - Commit pinned: `d3f0be0c7739ea704ca25d8b5158581bb176abc6`
   - Used for: Optional `-PpocketUi=top` native GLES2 top-screen UI runtime (`engine/quickjs-c` & Rust `engine/ui-cabi`).

4. **QuickJS-rs (`pocket-stack/quickjs-rs`, `2026-06-04`)** — MIT License ([`docs/pocketjs/licenses/QuickJS-MIT.txt`](docs/pocketjs/licenses/QuickJS-MIT.txt))
   - Repository: <https://github.com/pocket-stack/quickjs-rs>
   - Commit pinned: `ba5bdd0dc013518768e76cd9e05cd30ed53dd35b`
   - Used for: Embedded JavaScript engine inside the optional `-PpocketUi=top` PocketJS host.

## OFL-1.1 Font Assets
- **Inter / InterDisplay** — SIL Open Font License 1.1 ([`docs/pocketjs/licenses/Inter-OFL.txt`](docs/pocketjs/licenses/Inter-OFL.txt)), baked at build time into PocketJS bitmap font atlases via `pocket-ui/build.py`.

## GPL-2.0 Optional Runtime Note (Not Bundled in Phase 0/1)
- MaVo's optional QDC507 Linux 3.18.44 loadable kernel modules (`qdc507_aprv3.ko`, `qdc507_voice.ko`) are derived from `the-modem-distro/quectel_eg25_kernel` under **GPL-2.0**. They are **not** bundled in the Phase 1 APK. Any future Phase 4 integration must either load user-supplied SHA-256-verified artifacts or ship separately with full GPL-2.0 compliance and `COPYING-GPL-2.0`.

## PolyForm Noncommercial 1.0.0 References (Protocol Research Only — Zero Source Copied)
- **DJOneHubNative** (`cr-zhichen/DJOneHubNative`), **DJ4Hub** (`WongLoki/DJ4Hub`), and **DJOneHub-mac-enhanced** (`rogerbush007-a11y/DJOneHub-mac-enhanced`) are licensed under **PolyForm Noncommercial License 1.0.0** (upstream `iniwex5/vohive`). No source code from these repositories is copied into Duo Phone; they were consulted strictly for hardware behavior verification (USB ID recovery, `AT+QCFG` behavior, QDC507 audio test records).
