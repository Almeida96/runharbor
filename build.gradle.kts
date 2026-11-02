plugins { application; jacoco }
group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "io.github.almeida96.runharbor.Main" }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror")) }
tasks.test {
    useJUnitPlatform()
    systemProperty("runharbor.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    finalizedBy(tasks.jacocoTestReport)
}
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
distributions { main { contents {
    from("README.md", "LICENSE", "CONTRIBUTING.md", "CHANGELOG.md")
    from("docs") { into("docs") }; from("examples") { into("examples") }
} } }
dependencyLocking { lockAllConfigurations() }
