plugins {
    java
    `maven-publish`
    alias(libs.plugins.lavalink)
}

lavalinkPlugin {
    name = "slugyzeon-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
    configurePublishing = false
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

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation(project(":slugyzeon-main"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "slugyzeon-plugin"
        }
    }
}
