import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 签名配置来源（按优先级）：
// 1. 环境变量（CI 用）
// 2. keystore.properties（本地开发用，已在 .gitignore 中排除）
// 两者都没有时不签名，release 包无法直接安装 —— 克隆仓库的人需自行生成密钥，
// 参见 README 的「自行构建」一节。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val storeFilePath = System.getenv("TODO_KEYSTORE_PATH")
    ?: keystoreProps.getProperty("storeFile")
val storePass = System.getenv("TODO_KEYSTORE_PASSWORD")
    ?: keystoreProps.getProperty("storePassword")
val aliasName = System.getenv("TODO_KEY_ALIAS")
    ?: keystoreProps.getProperty("keyAlias")
val keyPass = System.getenv("TODO_KEY_PASSWORD")
    ?: keystoreProps.getProperty("keyPassword")

val hasKeystore = !storeFilePath.isNullOrBlank() &&
    rootProject.file(storeFilePath).exists() &&
    !storePass.isNullOrBlank()

android {
    namespace = "com.example.todooverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.todooverlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
