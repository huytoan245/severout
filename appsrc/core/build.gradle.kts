plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")) }

tasks.register<JavaExec>("coreSelfCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.family.core.CoreSelfTestKt")
    dependsOn("testClasses")
}
tasks.register<JavaExec>("scenarioCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.family.core.ScenarioTestKt")
    dependsOn("testClasses")
}
