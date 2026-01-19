package com.github.lelloman.duckmapper.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.*

class DuckMapProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotationName = "com.github.lelloman.duckmapper.DuckMap"
        val symbols = resolver.getSymbolsWithAnnotation(annotationName)

        val mappingDeclarations = mutableListOf<MappingDeclaration>()

        symbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            classDecl.annotations
                .filter { it.annotationType.resolve().declaration.qualifiedName?.asString() == annotationName }
                .forEach { annotation ->
                    val args = annotation.arguments
                    val sourceArg = args.find { it.name?.asString() == "source" }
                    val targetArg = args.find { it.name?.asString() == "target" }

                    val sourceType = (sourceArg?.value as? KSType)
                    val targetType = (targetArg?.value as? KSType)

                    if (sourceType != null && targetType != null) {
                        val sourceDecl = sourceType.declaration as? KSClassDeclaration
                        val targetDecl = targetType.declaration as? KSClassDeclaration

                        if (sourceDecl != null && targetDecl != null) {
                            mappingDeclarations.add(
                                MappingDeclaration(
                                    source = sourceDecl,
                                    target = targetDecl,
                                    annotatedClass = classDecl
                                )
                            )
                        }
                    }
                }
        }

        if (mappingDeclarations.isEmpty()) {
            return emptyList()
        }

        val mappingRegistry = MappingRegistry(mappingDeclarations)
        val generator = MapperGenerator(codeGenerator, logger, mappingRegistry)
        generator.generate(mappingDeclarations)

        return emptyList()
    }
}

data class MappingDeclaration(
    val source: KSClassDeclaration,
    val target: KSClassDeclaration,
    val annotatedClass: KSClassDeclaration
)

class MappingRegistry(declarations: List<MappingDeclaration>) {
    private val mappings: Set<Pair<String, String>>

    init {
        // Store both directions since @DuckMap generates bidirectional mappers
        mappings = declarations.flatMap { decl ->
            val sourceQName = decl.source.qualifiedName!!.asString()
            val targetQName = decl.target.qualifiedName!!.asString()
            listOf(
                sourceQName to targetQName,
                targetQName to sourceQName
            )
        }.toSet()
    }

    fun hasMapping(sourceQualifiedName: String, targetQualifiedName: String): Boolean {
        return mappings.contains(sourceQualifiedName to targetQualifiedName)
    }

    fun getMapperFunctionName(sourceQualifiedName: String, targetQualifiedName: String): String {
        val targetSimpleName = targetQualifiedName.substringAfterLast(".")
        return "to$targetSimpleName"
    }
}

class DuckMapProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return DuckMapProcessor(environment.codeGenerator, environment.logger)
    }
}
