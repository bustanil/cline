plugins {
    kotlin("jvm")
    id("com.google.protobuf")
}

val grpcVersion = "1.73.0"
val protobufVersion = "4.28.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-services:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        proto {
            // The protos live in the VS Code app; the plugin reads them in place.
            srcDir("../../vscode/proto")
            include("host/core_connection.proto")
            include("host/env.proto")
            include("host/window.proto")
            include("host/diff.proto")
            include("host/workspace.proto")
            include("cline/common.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

tasks.test {
    useJUnitPlatform()
}
