plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

import java.io.FileInputStream
import java.util.Properties

// 从 Gradle properties 读取签名配置（来源优先级：
//   1. CI workflow 通过 -Pyouji.storeFile=... 等参数显式传入
//   2. $GRADLE_USER_HOME/gradle.properties 或项目根 gradle.properties
//   3. local.properties（仅本地开发，已被 gitignore）
// 密码绝不写死在仓库代码里。
fun signingProp(name: String): String? {
    val fromGradle = providers.gradleProperty(name).orNull
    if (!fromGradle.isNullOrBlank()) return fromGradle
    val local = rootProject.file("local.properties")
    if (!local.exists()) return null
    val props = Properties()
    FileInputStream(local).use { props.load(it) }
    val v = props.getProperty(name)
    return if (v.isNullOrBlank()) null else v
}

android {
    namespace = "cn.hllcloud.youji"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.hllcloud.youji"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // 有签名参数时用统一签名，没有则回退到 Android 默认 debug.keystore（仅 debug 可跑通，
        // release 必须显式提供签名否则报错）。这样本地开发和 CI 各得其所。
        val youjiStoreFile = signingProp("youji.storeFile")
        val youjiStorePassword = signingProp("youji.storePassword")
        val youjiKeyAlias = signingProp("youji.keyAlias")
        val youjiKeyPassword = signingProp("youji.keyPassword")

        val hasYoujiSigning =
            !youjiStoreFile.isNullOrBlank() &&
            !youjiStorePassword.isNullOrBlank() &&
            !youjiKeyAlias.isNullOrBlank() &&
            !youjiKeyPassword.isNullOrBlank()

        if (hasYoujiSigning) {
            create("youji") {
                storeFile = file(youjiStoreFile!!)
                storePassword = youjiStorePassword!!
                keyAlias = youjiKeyAlias!!
                keyPassword = youjiKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            signingConfig =
                signingConfigs.findByName("youji")
                ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig =
                signingConfigs.findByName("youji")
                    ?: signingConfigs.getByName("debug") // CI 本地无 youji 配置时，用 debug 签名兜底（CI 通过 secrets 注入 youji 配置后会被覆盖）
            // 注：release 必须有 youji 签名才能正式发布，但 gradle 配置阶段不能抛异常（会影响 debug 构建）。
            // CI workflow 在 release 构建前会显式校验 secrets 是否配置，缺失则 fail-fast。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    ksp("androidx.room:room-compiler:2.6.0")

    // Coil Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore (for settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Accompanist (Permissions, Pager)
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    implementation("com.google.accompanist:accompanist-pager:0.32.0")
    implementation("com.google.accompanist:accompanist-pager-indicators:0.32.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
