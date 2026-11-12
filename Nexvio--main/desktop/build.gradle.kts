plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

compose.desktop {
    application {
        mainClass = "com.nexvio.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "Nexvio"
            packageVersion = "1.0.0"
            description = "Nexvio entertainment app"
            vendor = "Nexvio"
            windows {
                menuGroup = "Nexvio"
                shortcut = true
                upgradeUuid = "6f8f4c8e-0f43-4f93-9e10-0a2e6f6d4a21"
            }
        }
    }
}
