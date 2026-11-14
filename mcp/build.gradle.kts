plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.gson)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

// تشغيل الفاحص من سطر الأوامر ضد خادم MCP (حقيقي أو وهمي للاختبار):
// ./gradlew :mcp:runProbe --args="--url http://127.0.0.1:8787/mcp --out build/probe-output"
tasks.register<JavaExec>("runProbe") {
    group = "verification"
    description = "Runs McpProbe (initialize + tools/list) against an MCP endpoint."
    mainClass.set("com.musaed.analysis.mcp.McpProbeKt")
    classpath = sourceSets.getByName("main").runtimeClasspath
}
