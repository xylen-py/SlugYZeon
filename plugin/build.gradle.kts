plugins {
    java
    alias(libs.plugins.lavalink)
}

version = "4.2.6"

lavalinkPlugin {
    name = "slugyzeon-plugin"
    path = "com.slugyzeon.plugin"
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

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation(project(":slugyzeon-main"))
}
