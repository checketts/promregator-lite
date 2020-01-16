import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "2.2.2.RELEASE"
	id("io.spring.dependency-management") version "1.0.8.RELEASE"
	kotlin("jvm") version "1.3.61"
	kotlin("plugin.spring") version "1.3.61"
}

group = "org.cloudfoundry.promregator"
version = "0.0.9-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_1_8

repositories {
	mavenCentral()
}

dependencyManagement {
	dependencies {
		dependency("io.projectreactor.netty:reactor-netty:0.9.4.RELEASE") //Upgrade to avoid https://github.com/reactor/reactor-netty/issues/969
	}
}

dependencies {
	implementation("io.projectreactor.tools:blockhound:1.0.1.RELEASE")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("io.github.microutils:kotlin-logging:1.7.7")

	implementation("io.micrometer:micrometer-registry-prometheus:latest.release")

	//CF API
	implementation("com.github.checketts.cf-java-client:cloudfoundry-client-reactor:refresh-token-leak-SNAPSHOT")
	implementation("com.github.checketts.cf-java-client:cloudfoundry-operations:refresh-token-leak-SNAPSHOT")
	implementation("org.cloudfoundry:cloudfoundry-client-reactor:4.3.0.RELEASE")
	implementation("org.cloudfoundry:cloudfoundry-operations:4.3.0.RELEASE")

	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
	}
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.mock-server:mockserver-client-java:5.5.4")
	testImplementation("org.mock-server:mockserver-netty:5.5.4")
	testImplementation("org.testcontainers:mockserver:1.12.3")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "1.8"
	}
}

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	this.archiveFileName.set("${archiveBaseName.get()}.${archiveExtension.get()}")
}