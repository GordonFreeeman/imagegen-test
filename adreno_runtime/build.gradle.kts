plugins {
    id("com.android.library")
}

android {
    namespace = "com.localflux.adreno"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_CPU_REPACK=ON",
                    "-DSD_VULKAN=ON",
                    "-DSD_BUILD_EXAMPLES=OFF",
                    "-DSD_WEBP=OFF",
                    "-DSD_WEBM=OFF",
                    "-DSPIRV-Headers_DIR=/usr/share/cmake/SPIRV-Headers",
                    "-DVulkan_INCLUDE_DIR=/usr/include"
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
