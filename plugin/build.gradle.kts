plugins {
    java
    `maven-publish`
    alias(libs.plugins.lavalink)
}

group = "com.slugyzeon"
version = "1.3.2"

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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}
