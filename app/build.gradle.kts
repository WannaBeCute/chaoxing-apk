plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.cxrunner.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cxrunner.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Python 3.13 仅支持 64 位 ABI
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("C:/Users/Admin/.workbuddy/binaries/python/versions/3.13.12/python.exe")
        pip {
            // 纯 Python 依赖（Chaquopy 会从 PyPI 安装通用 wheel）
            install("requests")
            install("urllib3")
            install("certifi")
            install("chardet")
            install("idna")
            install("beautifulsoup4")
            install("soupsieve")
            install("loguru")
            install("tqdm")
            install("tenacity")
            install("fonttools")
            install("typing_extensions")
            install("httpx")
            install("httpcore")
            install("h11")
            install("sniffio")
            install("anyio")
            // 含原生组件的依赖（Chaquopy 官方仓库已提供 Android wheel）
            install("lxml")
        }
        // pyaes 在 PyPI 上只提供 sdist，已随源码内置在 src/main/python/pyaes
        // openai 依赖 pydantic-core（Rust 扩展），无 Android wheel，
        // 已由 src/main/python/openai.py 轻量替代实现提供兼容接口
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
