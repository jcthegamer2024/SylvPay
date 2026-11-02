
plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")

    // Official Nexo Maven repository
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
    compileOnly("com.nexomc:nexo:1.29.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
