# Third-party notices

The companion implementation and adapted connection flow are licensed under AGPL-3.0. Source is supplied with the local release.

- midi-HL/SimpleFetch-Android-Bridge, commit `eff3143d5bd75d617b5e717617a62e43fd6d8708`, AGPL-3.0: https://github.com/midi-HL/SimpleFetch-Android-Bridge . The Wearable.getNodeApi/getAuthApi/getMessageApi discovery, permission and start/ready flow is adapted into WearBridge.kt. No Bluetooth protocol or HTTP proxy is reimplemented.
- Xiaomi xms-wearable-lib 1.4 is used by the optional band adapter. The [official demo](https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip) is linked from the [Xiaomi Vela documentation](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html). The SDK remains subject to Xiaomi's terms and is obtained separately for band builds.
- ZXing core 3.5.3, Apache-2.0: https://github.com/zxing/zxing . Used for QR encoding in the unified APK. See licenses/ZXing-LICENSE.
- Material Components for Android 1.14.0, Apache-2.0: https://github.com/material-components/material-components-android . Supplies Material 3 controls for the Android host.
- org.json JSON-java 20240303: https://github.com/stleary/JSON-java . Used only by JVM tests, not shipped in the Android APK; Android supplies org.json at runtime.

Pinned binary SHA-256 values are enforced by Gradle's `checkPaymentInputs` task. Private signing keys, local passwords, remembered account files, payment codes and tokens are excluded from release source archives.

For the 0.3.2 image-rendering candidate, the band follows Xiaomi's Apache-2.0 interconnect_image_demo at open-vela/packages_apps, commit `daf277e9e2d50416662512c8b753b16b3a3ec421`: https://github.com/open-vela/packages_apps/tree/daf277e9e2d50416662512c8b753b16b3a3ec421/wearable/interconnect_image_demo . Copyright (C) 2024 Xiaomi Corporation. Base64 conversion, file.writeArrayBuffer and cache-backed image display are adapted with small-image bounds, generation checks and scoped cleanup. See the band vendor notices and Apache-2.0 license. The original sample's unrestricted cache deletion and raw payload logging are not used.
