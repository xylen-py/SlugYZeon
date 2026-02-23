plugins {
    java
    `maven-publish`
    alias(libs.plugins.lavalink)
}

group = "com.slugyzeon"
version = "2.1.3"

lavalinkPlugin {
    name = "slugyzeon-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

publishing {
    publications {
        create<MavenPublication>("jitpack") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "slugyzeon-plugin"
            version = project.version.toString()
        }
    }
}

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}
