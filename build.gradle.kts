plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("pushDebugToDevice") {
    dependsOn(":app:assembleDebug")
    workingDir(rootDir)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        file("scripts/push_debug_to_device.ps1").absolutePath,
        "-ApkPath",
        file("app/build/outputs/apk/debug/app-debug.apk").absolutePath,
        "-TrustManifestPath",
        file("releases/PhoneSyncUSB-C-debug.apk.trust.json").absolutePath,
    )
}