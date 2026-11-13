
plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

// ==========================================
// SYLVPAY VERSION
// Change this for each new release.
// ==========================================

group = "org.sylvarion.sylvpay"
version = "0.4.0-beta"

base {
    archivesName.set("SylvPay")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    // Paper 26.3 API
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    // Vault economy API
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // Official Nexo API
    compileOnly("com.nexomc:nexo:1.30.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    runServer {
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to project.version.toString())

        inputs.properties(props)

        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(props)
        }
    }
}

// ==========================================
// JAVA COMPILER WARNINGS
// ==========================================
// Show the exact deprecated API methods,
// including their file names and line numbers.

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
