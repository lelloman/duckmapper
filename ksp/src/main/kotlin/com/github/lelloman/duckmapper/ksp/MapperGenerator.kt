package com.github.lelloman.duckmapper.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.*

class MapperGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val mappingRegistry: MappingRegistry
) {
    fun generate(declarations: List<MappingDeclaration>) {
        val declarationsByFile = declarations.groupBy { it.annotatedClass.containingFile }

        declarationsByFile.forEach { (_, mappings) ->
            val packageName = mappings.first().annotatedClass.packageName.asString()
            val fileName = "DuckMappers"

            val fileSpec = FileSpec.builder(packageName, fileName).apply {
                mappings.forEach { mapping ->
                    val forwardResult = generateMapper(mapping.source, mapping.target)
                    val reverseResult = generateMapper(mapping.target, mapping.source)

                    if (forwardResult is MapperResult.Success) {
                        addFunction(forwardResult.funSpec)
                    } else if (forwardResult is MapperResult.Error) {
                        logger.error(forwardResult.message, mapping.annotatedClass)
                    }

                    if (reverseResult is MapperResult.Success) {
                        addFunction(reverseResult.funSpec)
                    } else if (reverseResult is MapperResult.Error) {
                        logger.error(reverseResult.message, mapping.annotatedClass)
                    }
                }
            }.build()

            val dependencies = Dependencies(
                aggregating = true,
                sources = declarations.mapNotNull { it.annotatedClass.containingFile }.toTypedArray()
            )

            codeGenerator.createNewFile(dependencies, packageName, fileName).use { output ->
                output.writer().use { writer ->
                    fileSpec.writeTo(writer)
                }
            }
        }
    }

    private fun generateMapper(source: KSClassDeclaration, target: KSClassDeclaration): MapperResult {
        // Check if both are enums
        val sourceIsEnum = source.classKind == ClassKind.ENUM_CLASS
        val targetIsEnum = target.classKind == ClassKind.ENUM_CLASS

        if (sourceIsEnum && targetIsEnum) {
            return generateEnumMapper(source, target)
        } else if (sourceIsEnum || targetIsEnum) {
            return MapperResult.Error(
                "Cannot map between enum and non-enum: ${source.qualifiedName?.asString()} -> ${target.qualifiedName?.asString()}"
            )
        }

        return generateDataClassMapper(source, target)
    }

    private fun generateEnumMapper(source: KSClassDeclaration, target: KSClassDeclaration): MapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val functionName = "to${target.simpleName.asString()}"

        val sourceEntries = source.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()

        val targetEntries = target.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toSet()

        // Check that all source entries exist in target (subset -> superset is OK)
        val missingEntries = sourceEntries.filter { it !in targetEntries }
        if (missingEntries.isNotEmpty()) {
            return MapperResult.Error(
                "Cannot map ${source.qualifiedName?.asString()} to ${target.qualifiedName?.asString()}: " +
                        "source enum values [${missingEntries.joinToString(", ")}] have no matching values in target"
            )
        }

        val whenBlock = CodeBlock.builder().apply {
            beginControlFlow("when (this)")
            sourceEntries.forEach { entry ->
                addStatement("%T.%L -> %T.%L", sourceTypeName, entry, targetTypeName, entry)
            }
            endControlFlow()
        }.build()

        val funSpec = FunSpec.builder(functionName)
            .receiver(sourceTypeName)
            .returns(targetTypeName)
            .addStatement("return %L", whenBlock)
            .build()

        return MapperResult.Success(funSpec)
    }

    private fun generateDataClassMapper(source: KSClassDeclaration, target: KSClassDeclaration): MapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val functionName = "to${target.simpleName.asString()}"

        val sourceProps = source.getAllProperties().associateBy { it.simpleName.asString() }
        val targetProps = target.getAllProperties().toList()

        val targetConstructorParams = target.primaryConstructor?.parameters ?: return MapperResult.Error(
            "Target class ${target.qualifiedName?.asString()} must have a primary constructor"
        )

        val propertyMappings = mutableListOf<PropertyMapping>()

        for (param in targetConstructorParams) {
            val paramName = param.name?.asString() ?: continue
            val targetProp = targetProps.find { it.simpleName.asString() == paramName }
            val sourceProp = sourceProps[paramName]

            if (sourceProp == null) {
                return MapperResult.Error(
                    "Cannot map ${source.qualifiedName?.asString()} to ${target.qualifiedName?.asString()}: " +
                            "missing property '$paramName' in source class"
                )
            }

            val sourceType = sourceProp.type.resolve()
            val targetType = targetProp?.type?.resolve() ?: param.type.resolve()

            val mappingResult = resolvePropertyMapping(paramName, sourceType, targetType)
            if (mappingResult is PropertyMappingResult.Error) {
                return MapperResult.Error(mappingResult.message)
            }

            propertyMappings.add((mappingResult as PropertyMappingResult.Success).mapping)
        }

        val constructorCall = buildConstructorCall(targetTypeName, propertyMappings)

        val funSpec = FunSpec.builder(functionName)
            .receiver(sourceTypeName)
            .returns(targetTypeName)
            .addStatement("return %L", constructorCall)
            .build()

        return MapperResult.Success(funSpec)
    }

    private fun resolvePropertyMapping(
        propertyName: String,
        sourceType: KSType,
        targetType: KSType
    ): PropertyMappingResult {
        val sourceQName = sourceType.declaration.qualifiedName?.asString() ?: ""
        val targetQName = targetType.declaration.qualifiedName?.asString() ?: ""
        val sourceNullable = sourceType.isMarkedNullable
        val targetNullable = targetType.isMarkedNullable

        // Check nullable compatibility: source nullable -> target non-nullable is NOT allowed
        if (sourceNullable && !targetNullable) {
            return PropertyMappingResult.Error(
                "Property '$propertyName': cannot map nullable type to non-nullable type " +
                        "(${sourceQName}? -> $targetQName)"
            )
        }

        // Check for collection types BEFORE same-type check to handle List<A> -> List<B>
        val collectionMapping = resolveCollectionMapping(propertyName, sourceType, targetType)
        if (collectionMapping != null) {
            return collectionMapping
        }

        // Same type (ignoring nullability for downcast case)
        if (sourceQName == targetQName) {
            return PropertyMappingResult.Success(
                PropertyMapping(propertyName, PropertyMappingType.DIRECT)
            )
        }

        // Check if there's a registered mapping
        if (mappingRegistry.hasMapping(sourceQName, targetQName)) {
            val mapperName = mappingRegistry.getMapperFunctionName(sourceQName, targetQName)
            return PropertyMappingResult.Success(
                PropertyMapping(propertyName, PropertyMappingType.MAPPED, mapperName, sourceNullable)
            )
        }

        return PropertyMappingResult.Error(
            "Property '$propertyName' type mismatch: $sourceQName -> $targetQName. " +
                    "No @DuckMap declaration found for these types."
        )
    }

    private fun resolveCollectionMapping(
        propertyName: String,
        sourceType: KSType,
        targetType: KSType
    ): PropertyMappingResult? {
        val sourceQName = sourceType.declaration.qualifiedName?.asString() ?: ""
        val targetQName = targetType.declaration.qualifiedName?.asString() ?: ""

        val listTypes = setOf("kotlin.collections.List", "kotlin.collections.MutableList")
        val mapTypes = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

        // List mapping
        if (sourceQName in listTypes && targetQName in listTypes) {
            return resolveListMapping(propertyName, sourceType, targetType)
        }

        // Array mapping
        if (sourceQName == "kotlin.Array" && targetQName == "kotlin.Array") {
            return resolveArrayMapping(propertyName, sourceType, targetType)
        }

        // Map mapping
        if (sourceQName in mapTypes && targetQName in mapTypes) {
            return resolveMapMapping(propertyName, sourceType, targetType)
        }

        return null
    }

    private fun resolveListMapping(
        propertyName: String,
        sourceType: KSType,
        targetType: KSType
    ): PropertyMappingResult {
        val sourceElementType = sourceType.arguments.firstOrNull()?.type?.resolve()
        val targetElementType = targetType.arguments.firstOrNull()?.type?.resolve()

        if (sourceElementType == null || targetElementType == null) {
            return PropertyMappingResult.Error(
                "Property '$propertyName': could not resolve List element types"
            )
        }

        val sourceElementQName = sourceElementType.declaration.qualifiedName?.asString() ?: ""
        val targetElementQName = targetElementType.declaration.qualifiedName?.asString() ?: ""

        if (sourceElementQName == targetElementQName) {
            return PropertyMappingResult.Success(
                PropertyMapping(propertyName, PropertyMappingType.DIRECT)
            )
        }

        if (mappingRegistry.hasMapping(sourceElementQName, targetElementQName)) {
            val mapperName = mappingRegistry.getMapperFunctionName(sourceElementQName, targetElementQName)
            return PropertyMappingResult.Success(
                PropertyMapping(
                    propertyName,
                    PropertyMappingType.LIST_MAPPED,
                    mapperName,
                    sourceType.isMarkedNullable
                )
            )
        }

        return PropertyMappingResult.Error(
            "Property '$propertyName': List element type mismatch: $sourceElementQName -> $targetElementQName. " +
                    "No @DuckMap declaration found for these types."
        )
    }

    private fun resolveArrayMapping(
        propertyName: String,
        sourceType: KSType,
        targetType: KSType
    ): PropertyMappingResult {
        val sourceElementType = sourceType.arguments.firstOrNull()?.type?.resolve()
        val targetElementType = targetType.arguments.firstOrNull()?.type?.resolve()

        if (sourceElementType == null || targetElementType == null) {
            return PropertyMappingResult.Error(
                "Property '$propertyName': could not resolve Array element types"
            )
        }

        val sourceElementQName = sourceElementType.declaration.qualifiedName?.asString() ?: ""
        val targetElementQName = targetElementType.declaration.qualifiedName?.asString() ?: ""

        if (sourceElementQName == targetElementQName) {
            return PropertyMappingResult.Success(
                PropertyMapping(propertyName, PropertyMappingType.DIRECT)
            )
        }

        if (mappingRegistry.hasMapping(sourceElementQName, targetElementQName)) {
            val mapperName = mappingRegistry.getMapperFunctionName(sourceElementQName, targetElementQName)
            return PropertyMappingResult.Success(
                PropertyMapping(
                    propertyName,
                    PropertyMappingType.ARRAY_MAPPED,
                    mapperName,
                    sourceType.isMarkedNullable
                )
            )
        }

        return PropertyMappingResult.Error(
            "Property '$propertyName': Array element type mismatch: $sourceElementQName -> $targetElementQName. " +
                    "No @DuckMap declaration found for these types."
        )
    }

    private fun resolveMapMapping(
        propertyName: String,
        sourceType: KSType,
        targetType: KSType
    ): PropertyMappingResult {
        val sourceKeyType = sourceType.arguments.getOrNull(0)?.type?.resolve()
        val sourceValueType = sourceType.arguments.getOrNull(1)?.type?.resolve()
        val targetKeyType = targetType.arguments.getOrNull(0)?.type?.resolve()
        val targetValueType = targetType.arguments.getOrNull(1)?.type?.resolve()

        if (sourceKeyType == null || sourceValueType == null ||
            targetKeyType == null || targetValueType == null
        ) {
            return PropertyMappingResult.Error(
                "Property '$propertyName': could not resolve Map key/value types"
            )
        }

        val sourceKeyQName = sourceKeyType.declaration.qualifiedName?.asString() ?: ""
        val targetKeyQName = targetKeyType.declaration.qualifiedName?.asString() ?: ""
        val sourceValueQName = sourceValueType.declaration.qualifiedName?.asString() ?: ""
        val targetValueQName = targetValueType.declaration.qualifiedName?.asString() ?: ""

        val keysMatch = sourceKeyQName == targetKeyQName
        val valuesMatch = sourceValueQName == targetValueQName

        val keyMapperName: String? = when {
            keysMatch -> null
            mappingRegistry.hasMapping(sourceKeyQName, targetKeyQName) ->
                mappingRegistry.getMapperFunctionName(sourceKeyQName, targetKeyQName)
            else -> return PropertyMappingResult.Error(
                "Property '$propertyName': Map key type mismatch: $sourceKeyQName -> $targetKeyQName. " +
                        "No @DuckMap declaration found for these types."
            )
        }

        val valueMapperName: String? = when {
            valuesMatch -> null
            mappingRegistry.hasMapping(sourceValueQName, targetValueQName) ->
                mappingRegistry.getMapperFunctionName(sourceValueQName, targetValueQName)
            else -> return PropertyMappingResult.Error(
                "Property '$propertyName': Map value type mismatch: $sourceValueQName -> $targetValueQName. " +
                        "No @DuckMap declaration found for these types."
            )
        }

        if (keysMatch && valuesMatch) {
            return PropertyMappingResult.Success(
                PropertyMapping(propertyName, PropertyMappingType.DIRECT)
            )
        }

        return PropertyMappingResult.Success(
            PropertyMapping(
                propertyName,
                PropertyMappingType.MAP_MAPPED,
                valueMapperName,
                sourceType.isMarkedNullable,
                keyMapperName
            )
        )
    }

    private fun buildConstructorCall(
        targetTypeName: ClassName,
        mappings: List<PropertyMapping>
    ): CodeBlock {
        return CodeBlock.builder().apply {
            add("%T(\n", targetTypeName)
            indent()
            mappings.forEachIndexed { index, mapping ->
                val comma = if (index < mappings.size - 1) "," else ""
                when (mapping.type) {
                    PropertyMappingType.DIRECT -> {
                        add("%N = this.%N%L\n", mapping.propertyName, mapping.propertyName, comma)
                    }
                    PropertyMappingType.MAPPED -> {
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.%N()%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        } else {
                            add("%N = this.%N.%N()%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        }
                    }
                    PropertyMappingType.LIST_MAPPED -> {
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.map { it.%N() }%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        } else {
                            add("%N = this.%N.map { it.%N() }%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        }
                    }
                    PropertyMappingType.ARRAY_MAPPED -> {
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.map { it.%N() }?.toTypedArray()%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        } else {
                            add("%N = this.%N.map { it.%N() }.toTypedArray()%L\n", mapping.propertyName, mapping.propertyName, mapping.mapperName, comma)
                        }
                    }
                    PropertyMappingType.MAP_MAPPED -> {
                        val keyExpr = mapping.keyMapperName?.let { "k.$it()" } ?: "k"
                        val valueExpr = mapping.mapperName?.let { "v.$it()" } ?: "v"
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.map { (k, v) -> %L to %L }?.toMap()%L\n",
                                mapping.propertyName, mapping.propertyName, keyExpr, valueExpr, comma)
                        } else {
                            add("%N = this.%N.map { (k, v) -> %L to %L }.toMap()%L\n",
                                mapping.propertyName, mapping.propertyName, keyExpr, valueExpr, comma)
                        }
                    }
                }
            }
            unindent()
            add(")")
        }.build()
    }
}

data class PropertyMapping(
    val propertyName: String,
    val type: PropertyMappingType,
    val mapperName: String? = null,
    val sourceNullable: Boolean = false,
    val keyMapperName: String? = null
)

enum class PropertyMappingType {
    DIRECT,
    MAPPED,
    LIST_MAPPED,
    ARRAY_MAPPED,
    MAP_MAPPED
}

sealed class MapperResult {
    data class Success(val funSpec: FunSpec) : MapperResult()
    data class Error(val message: String) : MapperResult()
}

sealed class PropertyMappingResult {
    data class Success(val mapping: PropertyMapping) : PropertyMappingResult()
    data class Error(val message: String) : PropertyMappingResult()
}
