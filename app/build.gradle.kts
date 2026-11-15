plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---- 固定 release 签名 ----
//
// 以前 release 直接套 debug signing config，而 CI 每次都是全新 runner，
// ~/.android/debug.keystore 由 AGP 现场生成 —— 于是每个版本的证书都不同，
// 覆盖安装被系统拒绝（必须先卸载）。现在统一读一份固定的 keystore。
//
// 取值优先级：Gradle -P 属性 > 环境变量（CI 用）> signing/keystore.properties（本地用）。
// 三者都没有时退回 debug 签名，保证刚 clone 下来的仓库仍能 assembleRelease；
// 但 CI 在出包后会校验证书指纹，debug 签名的包会被拦下，不会发出去。
val signingPropsFile = rootProject.file("signing/keystore.properties")
val signingProps = java.util.Properties().apply {
    if (signingPropsFile.isFile) signingPropsFile.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String, key: String): String? =
    (findProperty(prop) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("WKN_KEYSTORE_FILE", "WKN_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("WKN_KEYSTORE_PASSWORD", "WKN_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("WKN_KEY_ALIAS", "WKN_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("WKN_KEY_PASSWORD", "WKN_KEY_PASSWORD", "keyPassword")
    ?: releaseStorePassword
val hasReleaseSigning = listOf(
    releaseKeystoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() } && rootProject.file(releaseKeystoreFile!!).isFile

android {
    namespace = "com.jinge.weknora"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.jinge.weknora"
        minSdk = 35
        targetSdk = 35
        // 每次出包都要往上加，别偷懒复用同一个号。
        //
        // 踩过的坑：一直停在 versionCode=1 时，覆盖安装后启动器**不会**刷新图标缓存
        // —— 启动器的图标位图缓存是按 (包名, versionCode) 做键的，号没变它就认为
        // 「这个包没换过」。表现是：退到后台的过渡动画用的是缓存里的旧图标
        // （原 AS 模板那套白底图标），动画结束重新加载 drawable 才变回设计图标。
        // APK 里的图标资源本身没问题（aapt2 查过，各密度都指向自适应图标）。
        //
        // 递增规则：versionCode 每次编译 +1；versionName **只动末位**
        // （1.1.1 → 1.1.2 → …），前两位只在正式发版时手动动。
        // 每次编译完要同步在对话里给一份 release note，并追加到 CHANGELOG.md。
        versionCode = 40
        versionName = "1.1.38"
    }

    // 有固定证书就签正式包；没配 keystore 时退回 debug（CI 的指纹校验会拦住后者）
    signingConfigs {
        getByName("debug")
        if (hasReleaseSigning) {
            create("releaseCert") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("releaseCert")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 图片加载：对话里的知识库图片/Markdown 图片。只引 Coil 一个库，
    // 自写 Bitmap 加载要处理缓存、生命周期、取消，不值得
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-beta01")
    implementation("androidx.pdf:pdf-ink:1.0.0-beta01")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.pdf:pdf-ocr-play-services:1.0.0-beta01")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // 网络：OkHttp 直接手写请求。不用 Retrofit —— SSE 流式响应本来就要自己
    // 读 Source，统一用 OkHttp 反而少一层抽象和依赖。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
