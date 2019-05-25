import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

group = "org.nixos"
version = "1.0.0-SNAPSHOT"

repositories {
    jcenter()
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.gradle:gradle-tooling-api:${gradle.gradleVersion}")
    implementation("com.github.ajalt:clikt:2.0.0")
}

application {
    mainClassName = "org.nixos.gradle2nix.MainKt"
    applicationName = "gradle2nix"
    applicationDefaultJvmArgs += "-Dorg.nixos.gradle2nix.initScript=@APP_HOME@/gradle/init.gradle"
    applicationDistribution
        .from(
            tasks.getByPath(":plugin:shadowJar"),
            project(":plugin").file("src/main/resources/init.gradle"))
        .into("gradle")
        .rename("plugin.*\\.jar", "plugin.jar")
}

tasks {
    val startScripts by existing(CreateStartScripts::class)
    startScripts {
        doLast {
            unixScript.writeText(unixScript.readText().replace("@APP_HOME@", "\$APP_HOME"))
            windowsScript.writeText(windowsScript.readText().replace("@APP_HOME@", "%APP_HOME%"))
        }
    }
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }
}
