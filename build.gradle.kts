plugins {
    kotlin("jvm") version "1.3.72"
}

group = "de.baconsquirrel"
version = "1.0"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("io.github.microutils:kotlin-logging:1.8.3")
    testImplementation("org.slf4j:slf4j-simple:1.7.29")

    implementation("com.squareup.okhttp3:okhttp:4.4.0")
    implementation("com.beust:klaxon:5.2")

    implementation("io.reactivex.rxjava2:rxkotlin:2.4.0")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}