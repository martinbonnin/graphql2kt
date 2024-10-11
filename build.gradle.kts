plugins {
  id("org.jetbrains.kotlin.jvm").version("2.0.20")
}

dependencies {
  implementation("com.squareup:kotlinpoet:1.18.1")
  implementation("com.apollographql.apollo:apollo-ast:4.0.0")
  testImplementation("org.jetbrains.kotlin:kotlin-test")
}