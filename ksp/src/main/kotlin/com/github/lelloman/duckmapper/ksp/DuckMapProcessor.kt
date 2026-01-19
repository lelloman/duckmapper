package com.github.lelloman.duckmapper.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.*

class DuckMapProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    companion object {
        private const val DUCK_MAP = "com.github.lelloman.duckmapper.DuckMap"
        private const val DUCK_WRAP = "com.github.lelloman.duckmapper.DuckWrap"
        private const val DUCK_IMPLEMENT = "com.github.lelloman.duckmapper.DuckImplement"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val mappingDeclarations = collectDeclarations(resolver, DUCK_MAP)
        val wrapDeclarations = collectDeclarations(resolver, DUCK_WRAP)
        val implementDeclarations = collectDeclarations(resolver, DUCK_IMPLEMENT)

        if (mappingDeclarations.isEmpty() && wrapDeclarations.isEmpty() && implementDeclarations.isEmpty()) {
            return emptyList()
        }

        val mappingRegistry = MappingRegistry(mappingDeclarations)
        val generator = MapperGenerator(codeGenerator, logger, mappingRegistry)
        generator.generate(mappingDeclarations, wrapDeclarations, implementDeclarations)

        return emptyList()
    }

    private fun collectDeclarations(resolver: Resolver, annotationName: String): List<MappingDeclaration> {
        val declarations = mutableSetOf<MappingDeclaration>()
        val symbols = resolver.getSymbolsWithAnnotation(annotationName)

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
                            declarations.add(
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

        return declarations.toList()
    }
}

data class MappingDeclaration(
    val source: KSClassDeclaration,
    val target: KSClassDeclaration,
    val annotatedClass: KSClassDeclaration
) {
    // Use qualified names for equality to avoid duplicates
    private val sourceQName = source.qualifiedName?.asString()
    private val targetQName = target.qualifiedName?.asString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingDeclaration) return false
        return sourceQName == other.source.qualifiedName?.asString() &&
                targetQName == other.target.qualifiedName?.asString()
    }

    override fun hashCode(): Int {
        var result = sourceQName?.hashCode() ?: 0
        result = 31 * result + (targetQName?.hashCode() ?: 0)
        return result
    }
}

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
