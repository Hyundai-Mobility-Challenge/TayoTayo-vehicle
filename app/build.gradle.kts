import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.hanhyo.pleosshuttle"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.hanhyo.pleosshuttle"
        minSdk = 28
        targetSdk = 35
        versionCode = 9
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 페어링 때 서버로 보내는 Pleos SDK 버전(InstitutionConnection.pair → pleosSdkVersion). 값은 libs.versions.toml 한곳에서 온다.
        buildConfigField("String", "PLEOS_VEHICLE_SDK", "\"${libs.versions.vehicleSdk.get()}\"")
        buildConfigField("String", "PLEOS_NAVI_SDK", "\"${libs.versions.naviHelper.get()}\"")
        buildConfigField("String", "PLEOS_TTS_SDK", "\"${libs.versions.textToSpeech.get()}\"")
    }
    // 릴리스 서명: 저장소 루트의 keystore.properties(git 제외)가 있을 때만 서명한다. 키 파일과 비밀번호는 저장소에 올리지 않는다.
    // 형식: storeFile=<keystore 절대경로>, storePassword=, keyAlias=, keyPassword=
    val releaseKeys = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (releaseKeys != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].java.srcDir("../common/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.kotlin.reflect)
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.text.to.speech)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
