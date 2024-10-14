# graphql2kt

Given a GraphQL schema, generate Kotlin code that implements it. 

> [!NOTE]
> This is a work in progress. 

- `id` is mapped to a Kotlin property.
- other GraphQL fields are mapped to Kotlin suspend functions.
- optionally adds annotations that can be used to generate the GraphQL schema back.
                 
Because there are different possible ways to generate Kotlin code (property vs function, suspend or not, with execution Context or not), this project hardcodes some choices and a proper flexible API is still TBD.

See [generateCode](https://github.com/martinbonnin/graphql2kt/blob/93ac59a9c145f26ea9e8d2f66f6c617b622235a0/src/main/kotlin/Generator.kt#L14) fot the entry point.