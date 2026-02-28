plugins {
    java
    `maven-publish`
}

group = "com.slugyzeon"
version = "2.1.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.lavalink.dev/snapshots")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("dev.arbjerg:lavaplayer:2.0.4")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
}

publishing {
    publications {
        create<MavenPublication>("jitpack") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "slugyzeon-main"
            version = project.version.toString()
        }
    }
}
