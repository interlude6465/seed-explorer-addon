pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "meteor-seed-explorer"