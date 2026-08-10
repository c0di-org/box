# Local Agent Workstation for Android

The first `stockDebug` foundation for an Android chat application with a private local computer.

## Current slice

- Kotlin + Jetpack Compose adaptive chat/computer shell.
- `ComputerRuntime` API boundary and explicit lifecycle state model.
- Dedicated `runtime-qemu` module, limited to `arm64-v8a`.
- `stock` and experimental `avf` build flavors.

The QEMU binary, reproducible Debian image, guest `agentd`, and display transport are intentionally **not yet implemented**. This APK does not emulate a Linux environment or execute guest commands.

## Build and install

```bash
./gradlew :app:assembleStockDebug
adb install -r app/build/outputs/apk/stock/debug/app-stock-debug.apk
```

## Next technical spike

Add an APK-packaged QEMU AArch64 executable to a separate runtime process, boot an ARM64 Linux guest, connect to guest `agentd`, and prove command/file persistence before adding desktop integration.
