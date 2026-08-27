plugins {
    id("com.android.application")
}

android {
    namespace = "com.localflux.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localflux.studio"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DSD_VULKAN=ON",
                    "-DSD_BUILD_EXAMPLES=OFF",
                    "-DSD_WEBP=OFF",
                    "-DSD_WEBM=OFF"
                )
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    testImplementation("junit:junit:4.13.2")
}
