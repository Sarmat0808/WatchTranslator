// Проверка «Умного переводчика» на сервере: тот же движок (LiteRT-LM), та же модель
// и тот же текст запроса (LlmPrompt.kt из приложения), что и на телефоне.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../../mobile/src/main/java/com/sarmat/perevodchik/phone/llmshared")
    compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:latest.release")
}

application {
    mainClass.set("MainKt")
}
