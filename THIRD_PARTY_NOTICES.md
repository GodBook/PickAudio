# Third-party notices

PickAudio includes the following third-party components. This notice covers those components, not a new license grant for the application itself. Exact resolved Maven versions and upstream notices are generated into each APK at build time.

| Component | Source | License |
|---|---|---|
| AndroidX Activity, Compose, Material, Navigation, Lifecycle, Room, DataStore, DocumentFile, Media3 and supporting AndroidX libraries | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | https://github.com/JetBrains/kotlin ; https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| OkHttp and Okio | https://github.com/square/okhttp ; https://github.com/square/okio | Apache-2.0 |
| Gson | https://github.com/google/gson | Apache-2.0 |
| Coil | https://github.com/coil-kt/coil | Apache-2.0 |
| JetBrains annotations | https://github.com/JetBrains/java-annotations | Apache-2.0 |
| QuickJS 2026-06-04, bundled native source | https://bellard.org/quickjs/ | MIT |
| StellarWave 星澜聚合音源 v3.2.0, user-provided LX script, author: 星澜团队 | Bundled unchanged at app/src/main/assets/sources/stellarwave-v3.2.0.js; its header supplies the author, version and MIT declaration | MIT (as declared in the supplied script) |

QuickJS source notices are retained in app/src/main/cpp/quickjs. Its Android host bridge is maintained in this project; the bundled engine is built using its own VERSION value.

Copyright notices in the bundled QuickJS files:

    Copyright (c) 2017-2025 Fabrice Bellard
    Copyright (c) 2017-2025 Charlie Gordon
    Copyright (c) 2017-2021 Fabrice Bellard
    Copyright (c) 2017-2021 Charlie Gordon
    Copyright (c) 2017-2018 Fabrice Bellard
    Copyright (c) 2017-2018 Charlie Gordon
    Copyright (c) 2016-2017 Fabrice Bellard
    Copyright (c) 2017 Fabrice Bellard
    Copyright (c) 2018 Charlie Gordon
    Copyright (c) 2024 Fabrice Bellard

Full license texts are in licenses/Apache-2.0.txt and licenses/QuickJS-MIT.txt. Each APK contains copies under assets/third-party, along with the resolved release dependency inventory and license/notice files present in the distributed JAR/AAR artifacts. The settings page offers an offline reader.

The supplied StellarWave script's original header and attribution are retained. Its homepage is a placeholder in the supplied file, so no verified upstream repository is claimed. The MIT text is included in licenses/StellarWave-MIT.txt and in the APK's offline notices. Playback services called by the script are operated by third parties; the script's license does not grant rights to their music content.

Development tools and test-only dependencies (Gradle, Android SDK/NDK/CMake, JUnit, Android test libraries and MockWebServer) are not included in the production application. Their upstream licenses continue to apply when those tools or test binaries are redistributed.
