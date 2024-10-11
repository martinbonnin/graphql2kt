import com.apollographql.apollo.ast.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File

/**
 * @param schemaFile a file containing a GraphQL schema
 * @param outputDirectory the directory where to generate the files
 * @param packageName the package name to use for the generated types
 * @param scalarMapping a [Map] containing the name of scalars as the key and the fully qualified
 * name of the Kotlin type to use for that scalar.
 * @param optionalClassName the fully qualified name of the Kotlin type to use for optional input fields.
 */
fun generateCode(
  schemaFile: File,
  outputDirectory: File,
  packageName: String,
  scalarMapping: Map<String, String>,
  optionalClassName: String,
  addApolloExecutionAnnotations: Boolean
) {
  val schema = schemaFile.toGQLDocument().toSchema()

  outputDirectory.apply {
    resolve(packageName.replace(".", File.pathSeparator)).deleteRecursively()
    mkdirs()
  }

  val scalarMappingIncludingBuiltinScalars = buildMap {
    putAll(scalarMapping.mapValues { ClassName.bestGuess(it.value) })
    if (!containsKey("String")) {
      put("String", ClassName("kotlin", "String"))
    }
    if (!containsKey("Int")) {
      put("Int", ClassName("kotlin", "Int"))
    }
    if (!containsKey("Float")) {
      put("Float", ClassName("kotlin", "Double"))
    }
    if (!containsKey("Boolean")) {
      put("Boolean", ClassName("kotlin", "Boolean"))
    }
    if (!containsKey("ID")) {
      put("ID", ClassName("kotlin", "String"))
    }
  }

  val context = Context(
    schema,
    packageName,
    scalarMappingIncludingBuiltinScalars,
    ClassName.bestGuess(optionalClassName),
    addApolloExecutionAnnotations
  )

  schema.typeDefinitions.values.filter { it.name.startsWith("__").not() }.forEach {
    val fileSpec: FileSpec = with(context) {
      when (it) {
        is GQLObjectTypeDefinition -> it.fileSpec()
        is GQLInterfaceTypeDefinition -> it.fileSpec()
        is GQLUnionTypeDefinition -> it.fileSpec()
        is GQLEnumTypeDefinition -> it.fileSpec()
        is GQLInputObjectTypeDefinition -> it.fileSpec()
        is GQLScalarTypeDefinition -> it.fileSpec()
      }
    }

    fileSpec.writeTo(outputDirectory)
  }
}


internal class Context(
  val schema: Schema,
  val packageName: String,
  val scalarMapping: Map<String, ClassName>,
  val optional: ClassName,
  val addApolloExecutionAnnotations: Boolean
) {
  private fun GQLType.toClassName(): TypeName {
    return when (this) {
      is GQLNamedType -> {
        val typeDefinition = schema.typeDefinition(name)
        if (typeDefinition is GQLScalarTypeDefinition) {
          scalarMapping.get(name) ?: error("Cannot find scalar definition for '$name'")
        } else {
          ClassName(packageName, typeDefinition.name.toKotlinClassName())
        }.copy(nullable = true)
      }

      is GQLListType -> ClassName("kotlin.collections", "List").parameterizedBy(type.toClassName())
        .copy(nullable = true)

      is GQLNonNullType -> type.toClassName().copy(nullable = false)
    }
  }

  private fun GQLType.toOutputClassName(isSubscription: Boolean): TypeName {

    return when {
      isSubscription -> ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(toClassName())
      else -> toClassName()
    }
  }

  private fun GQLType.toInputClassName(hasDefaultValue: Boolean): TypeName {
    return if (this is GQLNonNullType || hasDefaultValue) {
      toClassName()
    } else {
      optional.parameterizedBy(toClassName())
    }
  }

  private fun TypeSpec.Builder.addSuperTypes(superTypes: List<String>) = apply {
    superTypes.forEach {
      addSuperinterface(ClassName(packageName, it.toKotlinClassName()))
    }
  }

  private fun GQLTypeDefinition.superFields(): Set<String> {
    val implementInterfaces = when (this) {
      is GQLInterfaceTypeDefinition -> implementsInterfaces
      is GQLObjectTypeDefinition -> implementsInterfaces
      else -> error("superFields() cannot be called on '$this'")
    }

    return implementInterfaces.map { schema.typeDefinition(it) }
      .flatMap {
        when (it) {
          is GQLInterfaceTypeDefinition -> it.fields
          is GQLObjectTypeDefinition -> it.fields
          else -> error("Type '$name' cannot implement type '${it.name}' because it is neither an interface nor an object")
        }
      }.map {
        it.name
      }.toSet()
  }

  private fun TypeSpec.Builder.addFields(
    fields: List<GQLFieldDefinition>,
    superFields: Set<String>,
    withInitializer: Boolean,
    isSubscription: Boolean
  ) = apply {

    fields.forEach { fieldDefinition ->
      val typeDefinition = schema.typeDefinition(fieldDefinition.type.rawType().name)
      if (!isSubscription && typeDefinition is GQLScalarTypeDefinition && fieldDefinition.arguments.isEmpty() && fieldDefinition.name == "id") {
        addProperty(
          PropertySpec.builder(fieldDefinition.name, fieldDefinition.type.toClassName())
            .apply {
              if (superFields.contains(fieldDefinition.name)) {
                addModifiers(KModifier.OVERRIDE)
              }
              maybeAddDescription(fieldDefinition.description)
              if (withInitializer) {
                initializer("%L", fieldDefinition.name)
              }
            }
            .build()
        )
      } else {
        addFunction(
          FunSpec.builder(fieldDefinition.name)
            .returns(fieldDefinition.type.toOutputClassName(isSubscription))
            .addParameter(ParameterSpec.builder("executionContext", ClassName("com.apollographql.apollo.api", "ExecutionContext")).build())
            .apply {
              fieldDefinition.arguments.forEach { argumentDefinition ->
                addParameter(
                  ParameterSpec.builder(
                    argumentDefinition.name,
                    argumentDefinition.type.toInputClassName(argumentDefinition.defaultValue != null)
                  )
                    .maybeAddDescription(argumentDefinition.description)
                    .maybeAddDefaultValue(argumentDefinition.defaultValue)
                    .maybeAddDefaultValueAnnotation(argumentDefinition.defaultValue)
                    .build()
                )
              }
              if (superFields.contains(fieldDefinition.name)) {
                addModifiers(KModifier.OVERRIDE)
              }
              maybeAddDescription(fieldDefinition.description)
            }
            .addCode("TODO()")
            .build()
        )
      }
    }
  }

  /*
   * From https://github.com/square/wire/pull/1445/files
   */
  internal fun String.sanitizeKdoc(): String {
    return this
      // Remove trailing whitespace on each line.
      .replace("[^\\S\n]+\n".toRegex(), "\n")
      .replace("\\s+$".toRegex(), "")
      .replace("\\*/".toRegex(), "&#42;/")
      .replace("/\\*".toRegex(), "/&#42;")
  }

  private fun TypeSpec.Builder.addInputFields(inputFields: List<GQLInputValueDefinition>) = apply {
    inputFields.forEach { inputFieldDefinition ->
      addProperty(
        PropertySpec.builder(
          inputFieldDefinition.name,
          inputFieldDefinition.type.toInputClassName(inputFieldDefinition.defaultValue != null)
        )
          .maybeAddDescription(inputFieldDefinition.description)
          .maybeAddDefaultValue(inputFieldDefinition.defaultValue)
          .maybeAddDefaultValueAnnotation(inputFieldDefinition.defaultValue)
          .initializer("%L", inputFieldDefinition.name)
          .build()
      )
    }
  }

  private fun <T : Documentable.Builder<T>> Documentable.Builder<T>.maybeAddDescription(description: String? = null): T {
    return if (description != null) {
      addKdoc("%L", description.sanitizeKdoc())
    } else {
      @Suppress("UNCHECKED_CAST")
      this as T
    }
  }

  private fun <T : Documentable.Builder<T>> Documentable.Builder<T>.maybeAddDefaultValue(defaultValue: GQLValue? = null): T {
    return if (defaultValue != null) {
      addKdoc("\nDefault value: %L", defaultValue.toUtf8("").replace("\n", "").sanitizeKdoc())
    } else {
      @Suppress("UNCHECKED_CAST")
      this as T
    }
  }

  private fun <T : Annotatable.Builder<T>> Annotatable.Builder<T>.maybeAddDefaultValueAnnotation(defaultValue: GQLValue? = null): T {
    return if (defaultValue != null && addApolloExecutionAnnotations) {
      addAnnotation(
        AnnotationSpec.builder(ClassName("com.apollographql.execution.annotation", "GraphQLDefault"))
          .addMember("%S", defaultValue.toUtf8("").replace("\n", ""))
          .build()
      )
    } else {
      @Suppress("UNCHECKED_CAST")
      this as T
    }
  }

  internal fun GQLObjectTypeDefinition.fileSpec(): FileSpec {
    val superTypes = implementsInterfaces + schema.typeDefinitions.values.filterIsInstance<GQLUnionTypeDefinition>()
      .filter { it.memberTypes.map { it.name }.toSet().contains(name) }
      .map { it.name }
    val superFields = superFields()

    val isSubscription = name == schema.rootTypeNameFor("subscription")

    return FileSpec.builder(packageName, name)
      .addType(
        TypeSpec.classBuilder(name.toKotlinClassName())
          .addSuperTypes(superTypes)
          .addFields(fields, superFields, true, isSubscription)
          .primaryConstructorFromProperties()
          .maybeAddDescription(description)
          .maybeAddRootAnnotation(this)
          .build()
      )
      .build()
  }

  private fun TypeSpec.Builder.maybeAddRootAnnotation(objectTypeDefinition: GQLObjectTypeDefinition): TypeSpec.Builder {
    if (addApolloExecutionAnnotations) {
      for (operationType in listOf("query", "mutation", "subscription")) {
        if (objectTypeDefinition.name == schema.rootTypeNameFor(operationType)) {
          addAnnotation(
            AnnotationSpec.builder(
              ClassName(
                "com.apollographql.execution.annotation",
                "GraphQL${operationType.capitalizeFirstChar()}"
              )
            ).build()
          )
        }
      }
    }
    return this
  }

  internal fun GQLInterfaceTypeDefinition.fileSpec(): FileSpec {
    val superTypes = implementsInterfaces
    val superFields = superFields()

    return FileSpec.builder(packageName, name)
      .addType(
        TypeSpec.interfaceBuilder(name.toKotlinClassName())
          .addModifiers(KModifier.SEALED)
          .addSuperTypes(superTypes)
          .addFields(fields, superFields, false, false)
          .maybeAddDescription(description)
          .build()
      )
      .build()
  }

  internal fun GQLUnionTypeDefinition.fileSpec(): FileSpec {
    return FileSpec.builder(packageName, name)
      .addType(
        TypeSpec.interfaceBuilder(name.toKotlinClassName())
          .addModifiers(KModifier.SEALED)
          .maybeAddDescription(description)
          .build()
      )
      .build()
  }

  internal fun GQLEnumTypeDefinition.fileSpec(): FileSpec {
    return FileSpec.builder(packageName, name)
      .addType(
        TypeSpec.enumBuilder(name.toKotlinClassName())
          .apply {
            enumValues.forEach {
              addEnumConstant(
                it.name, TypeSpec.anonymousClassBuilder()
                  .maybeAddDescription(it.description)
                  .build()
              )
            }
          }
          .maybeAddDescription(description)
          .build()
      )
      .build()
  }

  internal fun GQLScalarTypeDefinition.fileSpec(): FileSpec {
    return FileSpec.builder(packageName, name)
      .addFileComment("Put your scalar definition and coercing here")
      .build()
  }

  internal fun GQLInputObjectTypeDefinition.fileSpec(): FileSpec {
    return FileSpec.builder(packageName, name)
      .addType(
        TypeSpec.classBuilder(name.toKotlinClassName())
          .addInputFields(inputFields = inputFields)
          .maybeAddDescription(description)
          .primaryConstructorFromProperties()
          .build()
      )
      .build()
  }
}

private fun TypeSpec.Builder.primaryConstructorFromProperties(): TypeSpec.Builder {
  return primaryConstructor(
    FunSpec.constructorBuilder()
      .apply {
        propertySpecs.forEach { propertySpec ->
          addParameter(ParameterSpec.builder(propertySpec.name, propertySpec.type).build())
        }
      }
      .build()
  )
}

internal fun String.toKotlinClassName() = replaceFirstChar { it.uppercase() }

internal fun String.capitalizeFirstChar() = replaceFirstChar { it.uppercase() }
