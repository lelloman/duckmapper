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
        private const val DUCK_CONVERT = "com.github.lelloman.duckmapper.DuckConvert"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val mappingDeclarations = collectDeclarations(resolver, DUCK_MAP)
        val wrapDeclarations = collectDeclarations(resolver, DUCK_WRAP)
        val implementDeclarations = collectDeclarations(resolver, DUCK_IMPLEMENT)
        val converterDeclarations = collectConverterDeclarations(resolver)

        if (mappingDeclarations.isEmpty() && wrapDeclarations.isEmpty() && implementDeclarations.isEmpty()) {
            return emptyList()
        }

        val mappingRegistry = MappingRegistry(mappingDeclarations)
        val converterRegistry = ConverterRegistry(converterDeclarations)
        val generator = MapperGenerator(codeGenerator, logger, mappingRegistry, converterRegistry)
        generator.generate(mappingDeclarations, wrapDeclarations, implementDeclarations)

        return emptyList()
    }

    private fun collectConverterDeclarations(resolver: Resolver): List<ConverterDeclaration> {
        val declarations = mutableListOf<ConverterDeclaration>()
        val symbols = resolver.getSymbolsWithAnnotation(DUCK_CONVERT)

        symbols.filterIsInstance<KSClassDeclaration>().forEach { classDecl ->
            classDecl.annotations
                .filter { it.annotationType.resolve().declaration.qualifiedName?.asString() == DUCK_CONVERT }
                .forEach { annotation ->
                    val args = annotation.arguments
                    val sourceType = (args.find { it.name?.asString() == "source" }?.value as? KSType)
                    val targetType = (args.find { it.name?.asString() == "target" }?.value as? KSType)
                    val property = args.find { it.name?.asString() == "property" }?.value as? String
                    val converterType = (args.find { it.name?.asString() == "converter" }?.value as? KSType)

                    if (sourceType != null && targetType != null && property != null && converterType != null) {
                        val sourceQName = sourceType.declaration.qualifiedName?.asString()
                        val targetQName = targetType.declaration.qualifiedName?.asString()
                        val converterQName = converterType.declaration.qualifiedName?.asString()

                        if (sourceQName != null && targetQName != null && converterQName != null) {
                            declarations.add(
                                ConverterDeclaration(
                                    sourceQualifiedName = sourceQName,
                                    targetQualifiedName = targetQName,
                                    propertyName = property,
                                    converterQualifiedName = converterQName
                                )
                            )
                        }
                    }
                }
        }

        return declarations
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

data class ConverterDeclaration(
    val sourceQualifiedName: String,
    val targetQualifiedName: String,
    val propertyName: String,
    val converterQualifiedName: String
)

class ConverterRegistry(declarations: List<ConverterDeclaration>) {
    // Key: "sourceQName->targetQName", Value: Map of propertyName to converterQName
    private val converters: Map<String, Map<String, String>>

    init {
        converters = declarations
            .groupBy { "${it.sourceQualifiedName}->${it.targetQualifiedName}" }
            .mapValues { (_, decls) ->
                decls.associate { it.propertyName to it.converterQualifiedName }
            }
    }

    fun getConverters(sourceQualifiedName: String, targetQualifiedName: String): Map<String, String> {
        return converters["$sourceQualifiedName->$targetQualifiedName"] ?: emptyMap()
    }
}

class DuckMapProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return DuckMapProcessor(environment.codeGenerator, environment.logger)
    }
}
