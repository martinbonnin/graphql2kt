import java.io.File
import kotlin.test.Test

class MainTest {
  @Test
  fun test() {
    generateCode(
      schemaFile = File("/Users/martinbonnin/git/spotify-showcase/subgraphs/spotify/schema.graphql"),
      outputDirectory = File("/Users/martinbonnin/git/apollo-kotlin-spotify-showcase/server/src/main/kotlin/"),
      packageName = "server.graphql",
      scalarMapping = mapOf(
        "Timestamp" to "kotlin.String",
        "CountryCode" to "kotlin.String",
        "DateTime" to "kotlin.String",
        "ErrorRate" to "kotlin.String",
      ),
      optionalClassName = "com.apollographql.apollo.api.Optional",
      true
    )
  }
}