// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies { api(project(":cpu")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// ROMs and disks remain local inputs, never resources packaged into the library.
tasks.withType<Test>().configureEach {
    systemProperty("appleiislop.romDirectory", rootProject.file("roms").absolutePath)
    systemProperty("appleiislop.diskDirectory", rootProject.file("disks").absolutePath)
    inputs.files(
        rootProject.file("roms/apple2plus.rom"),
        rootProject.file("roms/video.rom"),
        rootProject.file("roms/disk2.rom"),
    ).withPropertyName("appleRoms")
    inputs.dir(rootProject.file("disks")).withPropertyName("disks")
    testLogging { events("passed", "skipped", "failed") }
}
