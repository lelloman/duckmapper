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
    private val mappingRegistry: MappingRegistry,
    private val converterRegistry: ConverterRegistry = ConverterRegistry(emptyList())
) {
    fun generate(
        mappingDeclarations: List<MappingDeclaration>,
        wrapDeclarations: List<MappingDeclaration> = emptyList(),
        implementDeclarations: List<MappingDeclaration> = emptyList()
    ) {
        val allDeclarations = (mappingDeclarations + wrapDeclarations + implementDeclarations).distinct()
        if (allDeclarations.isEmpty()) return

        // Convert lists to sets for efficient lookup
        val wrapDeclSet = wrapDeclarations.toSet()
        val implDeclSet = implementDeclarations.toSet()

        val declarationsByFile = allDeclarations.groupBy { it.annotatedClass.containingFile?.filePath }

        declarationsByFile.forEach { (_, allMappings) ->
            val packageName = allMappings.first().annotatedClass.packageName.asString()
            val fileName = "DuckMappers"

            val fileSpec = FileSpec.builder(packageName, fileName).apply {
                // Process @DuckMap declarations
                val mappings = allMappings.filter { it !in wrapDeclSet && it !in implDeclSet }
                mappings.forEach { mapping ->
                    val forwardResult = generateMapper(mapping.source, mapping.target)
                    val reverseResult = generateMapper(mapping.target, mapping.source)

                    when (forwardResult) {
                        is MapperResult.Success -> addFunction(forwardResult.funSpec)
                        is MapperResult.Error -> logger.error(forwardResult.message, mapping.annotatedClass)
                        is MapperResult.Skipped -> { /* silently skip */ }
                    }

                    when (reverseResult) {
                        is MapperResult.Success -> addFunction(reverseResult.funSpec)
                        is MapperResult.Error -> logger.error(reverseResult.message, mapping.annotatedClass)
                        is MapperResult.Skipped -> { /* silently skip */ }
                    }
                }

                // Process @DuckWrap declarations
                val wraps = allMappings.filter { it in wrapDeclSet }
                wraps.forEach { wrap ->
                    val result = generateWrapper(wrap.source, wrap.target)
                    when (result) {
                        is WrapperResult.Success -> {
                            addType(result.typeSpec)
                            addFunction(result.funSpec)
                        }
                        is WrapperResult.Error -> logger.error(result.message, wrap.annotatedClass)
                    }
                }

                // Process @DuckImplement declarations
                val implements = allMappings.filter { it in implDeclSet }
                implements.forEach { impl ->
                    val result = generateImplementation(impl.source, impl.target)
                    when (result) {
                        is WrapperResult.Success -> {
                            addType(result.typeSpec)
                            addFunction(result.funSpec)
                        }
                        is WrapperResult.Error -> logger.error(result.message, impl.annotatedClass)
                    }
                }
            }.build()

            val dependencies = Dependencies(
                aggregating = true,
                sources = allDeclarations.mapNotNull { it.annotatedClass.containingFile }.toTypedArray()
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

        // Check if both are sealed classes/interfaces
        val sourceIsSealed = Modifier.SEALED in source.modifiers
        val targetIsSealed = Modifier.SEALED in target.modifiers

        if (sourceIsSealed && targetIsSealed) {
            return generateSealedMapper(source, target)
        } else if (sourceIsSealed || targetIsSealed) {
            return MapperResult.Error(
                "Cannot map between sealed and non-sealed: ${source.qualifiedName?.asString()} -> ${target.qualifiedName?.asString()}"
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

    private fun generateSealedMapper(source: KSClassDeclaration, target: KSClassDeclaration): MapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val functionName = "to${target.simpleName.asString()}"

        // Get sealed subclasses
        val sourceSubclasses = source.getSealedSubclasses().toList()
        val targetSubclasses = target.getSealedSubclasses().associateBy { it.simpleName.asString() }

        // Check that all source subclasses have matching target subclasses (subset -> superset OK)
        // If source has subclasses not in target, skip silently (allows one-way mapping)
        val missingSubclasses = sourceSubclasses.filter { it.simpleName.asString() !in targetSubclasses }
        if (missingSubclasses.isNotEmpty()) {
            return MapperResult.Skipped
        }

        val whenBlock = CodeBlock.builder().apply {
            beginControlFlow("when (this)")
            for (sourceSubclass in sourceSubclasses) {
                val subclassName = sourceSubclass.simpleName.asString()
                val targetSubclass = targetSubclasses[subclassName]!!
                val sourceSubclassType = sourceTypeName.nestedClass(subclassName)
                val targetSubclassType = targetTypeName.nestedClass(subclassName)

                // Check if it's a data object (no constructor params) or data class (has params)
                val isDataObject = Modifier.DATA in sourceSubclass.modifiers &&
                        (sourceSubclass.primaryConstructor?.parameters?.isEmpty() ?: true) &&
                        sourceSubclass.classKind == ClassKind.OBJECT

                if (isDataObject) {
                    // data object -> direct reference
                    addStatement("is %T -> %T", sourceSubclassType, targetSubclassType)
                } else {
                    // data class -> map properties via constructor
                    val constructorCall = buildSealedSubclassConstructorCall(
                        sourceSubclass,
                        targetSubclass,
                        targetSubclassType
                    )
                    if (constructorCall is SealedConstructorResult.Error) {
                        return MapperResult.Error(constructorCall.message)
                    }
                    addStatement("is %T -> %L", sourceSubclassType, (constructorCall as SealedConstructorResult.Success).codeBlock)
                }
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

    private sealed class SealedConstructorResult {
        data class Success(val codeBlock: CodeBlock) : SealedConstructorResult()
        data class Error(val message: String) : SealedConstructorResult()
    }

    private fun buildSealedSubclassConstructorCall(
        sourceSubclass: KSClassDeclaration,
        targetSubclass: KSClassDeclaration,
        targetSubclassType: ClassName
    ): SealedConstructorResult {
        val targetConstructorParams = targetSubclass.primaryConstructor?.parameters
            ?: return SealedConstructorResult.Success(CodeBlock.of("%T", targetSubclassType))

        if (targetConstructorParams.isEmpty()) {
            return SealedConstructorResult.Success(CodeBlock.of("%T", targetSubclassType))
        }

        val sourceProps = sourceSubclass.getAllProperties().associateBy { it.simpleName.asString() }

        val builder = CodeBlock.builder()
        builder.add("%T(\n", targetSubclassType)

        targetConstructorParams.forEachIndexed { index, param ->
            val paramName = param.name?.asString() ?: return SealedConstructorResult.Error(
                "Parameter without name in ${targetSubclass.qualifiedName?.asString()}"
            )
            val sourceProp = sourceProps[paramName]
                ?: return SealedConstructorResult.Error(
                    "Missing property '$paramName' in source sealed subclass ${sourceSubclass.qualifiedName?.asString()}"
                )

            val sourceType = sourceProp.type.resolve()
            val targetType = param.type.resolve()

            val mappingResult = resolvePropertyMapping(paramName, sourceType, targetType)
            if (mappingResult is PropertyMappingResult.Error) {
                return SealedConstructorResult.Error(mappingResult.message)
            }

            val mapping = (mappingResult as PropertyMappingResult.Success).mapping
            val comma = if (index < targetConstructorParams.size - 1) "," else ""

            when (mapping.type) {
                PropertyMappingType.DIRECT -> {
                    builder.add("  %N = this.%N%L\n", paramName, paramName, comma)
                }
                PropertyMappingType.MAPPED -> {
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.%N()%L\n", paramName, paramName, mapping.mapperName, comma)
                    } else {
                        builder.add("  %N = this.%N.%N()%L\n", paramName, paramName, mapping.mapperName, comma)
                    }
                }
                PropertyMappingType.LIST_MAPPED -> {
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.map { it.%N() }%L\n", paramName, paramName, mapping.mapperName, comma)
                    } else {
                        builder.add("  %N = this.%N.map { it.%N() }%L\n", paramName, paramName, mapping.mapperName, comma)
                    }
                }
                PropertyMappingType.ARRAY_MAPPED -> {
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.map { it.%N() }?.toTypedArray()%L\n", paramName, paramName, mapping.mapperName, comma)
                    } else {
                        builder.add("  %N = this.%N.map { it.%N() }.toTypedArray()%L\n", paramName, paramName, mapping.mapperName, comma)
                    }
                }
                PropertyMappingType.MAP_MAPPED -> {
                    val keyExpr = mapping.keyMapperName?.let { "k.$it()" } ?: "k"
                    val valueExpr = mapping.mapperName?.let { "v.$it()" } ?: "v"
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.map { (k, v) -> %L to %L }?.toMap()%L\n",
                            paramName, paramName, keyExpr, valueExpr, comma)
                    } else {
                        builder.add("  %N = this.%N.map { (k, v) -> %L to %L }.toMap()%L\n",
                            paramName, paramName, keyExpr, valueExpr, comma)
                    }
                }
                PropertyMappingType.STRING_TO_ENUM -> {
                    val enumClass = ClassName.bestGuess(mapping.enumClassName!!)
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.let { %T.valueOf(it) }%L\n",
                            paramName, paramName, enumClass, comma)
                    } else {
                        builder.add("  %N = %T.valueOf(this.%N)%L\n",
                            paramName, enumClass, paramName, comma)
                    }
                }
                PropertyMappingType.ENUM_TO_STRING -> {
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.name%L\n", paramName, paramName, comma)
                    } else {
                        builder.add("  %N = this.%N.name%L\n", paramName, paramName, comma)
                    }
                }
                PropertyMappingType.CUSTOM_CONVERTED -> {
                    val converter = ClassName.bestGuess(mapping.converterName!!)
                    if (mapping.sourceNullable) {
                        builder.add("  %N = this.%N?.let { %T(it) }%L\n",
                            paramName, paramName, converter, comma)
                    } else {
                        builder.add("  %N = %T(this.%N)%L\n",
                            paramName, converter, paramName, comma)
                    }
                }
            }
        }

        builder.add(")")
        return SealedConstructorResult.Success(builder.build())
    }

    private fun generateDataClassMapper(source: KSClassDeclaration, target: KSClassDeclaration): MapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val functionName = "to${target.simpleName.asString()}"

        // Target must be instantiable (not an interface or abstract class)
        // Skip silently - this allows interface->class mapping without generating class->interface
        if (target.classKind == ClassKind.INTERFACE) {
            return MapperResult.Skipped
        }

        val sourceProps = source.getAllProperties().associateBy { it.simpleName.asString() }
        val targetProps = target.getAllProperties().toList()

        val targetConstructorParams = target.primaryConstructor?.parameters ?: return MapperResult.Error(
            "Target class ${target.qualifiedName?.asString()} must have a primary constructor"
        )

        // Get custom converters for this source->target mapping
        val sourceQName = source.qualifiedName?.asString() ?: ""
        val targetQName = target.qualifiedName?.asString() ?: ""
        val customConvertersMap = converterRegistry.getConverters(sourceQName, targetQName)
        val customConverters = customConvertersMap.map { (prop, conv) ->
            prop to ConverterInfo(prop, conv)
        }.toMap()

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

            val mappingResult = resolvePropertyMapping(paramName, sourceType, targetType, customConverters)
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
        targetType: KSType,
        customConverters: Map<String, ConverterInfo> = emptyMap()
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

        // Check for custom converter first
        val customConverter = customConverters[propertyName]
        if (customConverter != null) {
            return PropertyMappingResult.Success(
                PropertyMapping(
                    propertyName,
                    PropertyMappingType.CUSTOM_CONVERTED,
                    converterName = customConverter.converterQualifiedName,
                    sourceNullable = sourceNullable
                )
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

        // Check for automatic String -> Enum conversion
        val targetDecl = targetType.declaration as? KSClassDeclaration
        if (sourceQName == "kotlin.String" && targetDecl?.classKind == ClassKind.ENUM_CLASS) {
            return PropertyMappingResult.Success(
                PropertyMapping(
                    propertyName,
                    PropertyMappingType.STRING_TO_ENUM,
                    enumClassName = targetQName,
                    sourceNullable = sourceNullable
                )
            )
        }

        // Check for automatic Enum -> String conversion
        val sourceDecl = sourceType.declaration as? KSClassDeclaration
        if (sourceDecl?.classKind == ClassKind.ENUM_CLASS && targetQName == "kotlin.String") {
            return PropertyMappingResult.Success(
                PropertyMapping(
                    propertyName,
                    PropertyMappingType.ENUM_TO_STRING,
                    sourceNullable = sourceNullable
                )
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

    private fun generateWrapper(source: KSClassDeclaration, target: KSClassDeclaration): WrapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val targetSimpleName = target.simpleName.asString()

        // Target must be an interface
        if (target.classKind != ClassKind.INTERFACE) {
            return WrapperResult.Error(
                "@DuckWrap target must be an interface: ${target.qualifiedName?.asString()}"
            )
        }

        // Generate: internal class DuckWrap<TargetName>(private val wrapped: <Source>) : <Target> by wrapped
        val wrapperClassName = "DuckWrap$targetSimpleName"

        val typeSpec = TypeSpec.classBuilder(wrapperClassName)
            .addModifiers(KModifier.INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("wrapped", sourceTypeName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("wrapped", sourceTypeName, KModifier.PRIVATE)
                    .initializer("wrapped")
                    .build()
            )
            .addSuperinterface(targetTypeName, CodeBlock.of("wrapped"))
            .build()

        // Generate: fun <Source>.as<Target>(): <Target> = DuckWrap<TargetName>(this)
        val extensionFunSpec = FunSpec.builder("as$targetSimpleName")
            .receiver(sourceTypeName)
            .returns(targetTypeName)
            .addStatement("return %N(this)", wrapperClassName)
            .build()

        return WrapperResult.Success(typeSpec, extensionFunSpec)
    }

    private fun generateImplementation(source: KSClassDeclaration, target: KSClassDeclaration): WrapperResult {
        val sourceTypeName = source.toClassName()
        val targetTypeName = target.toClassName()
        val targetSimpleName = target.simpleName.asString()

        // Target must be an interface
        if (target.classKind != ClassKind.INTERFACE) {
            return WrapperResult.Error(
                "@DuckImplement target must be an interface: ${target.qualifiedName?.asString()}"
            )
        }

        // Get all properties from the interface that need to be implemented
        val interfaceProperties = target.getAllProperties().toList()

        // Get source properties
        val sourceProps = source.getAllProperties().associateBy { it.simpleName.asString() }

        // Validate all interface properties exist in source
        val propertyOverrides = mutableListOf<PropertySpec>()
        for (prop in interfaceProperties) {
            val propName = prop.simpleName.asString()
            val sourceProp = sourceProps[propName]

            if (sourceProp == null) {
                return WrapperResult.Error(
                    "@DuckImplement: source ${source.qualifiedName?.asString()} is missing property '$propName' " +
                            "required by interface ${target.qualifiedName?.asString()}"
                )
            }

            val propType = prop.type.resolve().toTypeName()

            propertyOverrides.add(
                PropertySpec.builder(propName, propType)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("source.$propName")
                    .build()
            )
        }

        // Generate: internal class DuckImpl<TargetName>(source: <Source>) : <Target> { override val ... }
        val implClassName = "DuckImpl$targetSimpleName"

        val typeSpec = TypeSpec.classBuilder(implClassName)
            .addModifiers(KModifier.INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("source", sourceTypeName)
                    .build()
            )
            .addSuperinterface(targetTypeName)
            .addProperties(propertyOverrides)
            .build()

        // Generate: fun <Source>.to<Target>(): <Target> = DuckImpl<TargetName>(this)
        val extensionFunSpec = FunSpec.builder("to$targetSimpleName")
            .receiver(sourceTypeName)
            .returns(targetTypeName)
            .addStatement("return %N(this)", implClassName)
            .build()

        return WrapperResult.Success(typeSpec, extensionFunSpec)
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
                    PropertyMappingType.STRING_TO_ENUM -> {
                        val enumClass = ClassName.bestGuess(mapping.enumClassName!!)
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.let { %T.valueOf(it) }%L\n",
                                mapping.propertyName, mapping.propertyName, enumClass, comma)
                        } else {
                            add("%N = %T.valueOf(this.%N)%L\n",
                                mapping.propertyName, enumClass, mapping.propertyName, comma)
                        }
                    }
                    PropertyMappingType.ENUM_TO_STRING -> {
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.name%L\n", mapping.propertyName, mapping.propertyName, comma)
                        } else {
                            add("%N = this.%N.name%L\n", mapping.propertyName, mapping.propertyName, comma)
                        }
                    }
                    PropertyMappingType.CUSTOM_CONVERTED -> {
                        val converter = ClassName.bestGuess(mapping.converterName!!)
                        if (mapping.sourceNullable) {
                            add("%N = this.%N?.let { %T(it) }%L\n",
                                mapping.propertyName, mapping.propertyName, converter, comma)
                        } else {
                            add("%N = %T(this.%N)%L\n",
                                mapping.propertyName, converter, mapping.propertyName, comma)
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
    val keyMapperName: String? = null,
    val enumClassName: String? = null,
    val converterName: String? = null
)

enum class PropertyMappingType {
    DIRECT,
    MAPPED,
    LIST_MAPPED,
    ARRAY_MAPPED,
    MAP_MAPPED,
    STRING_TO_ENUM,
    ENUM_TO_STRING,
    CUSTOM_CONVERTED
}

data class ConverterInfo(
    val propertyName: String,
    val converterQualifiedName: String
)

sealed class MapperResult {
    data class Success(val funSpec: FunSpec) : MapperResult()
    data class Error(val message: String) : MapperResult()
    data object Skipped : MapperResult()
}

sealed class PropertyMappingResult {
    data class Success(val mapping: PropertyMapping) : PropertyMappingResult()
    data class Error(val message: String) : PropertyMappingResult()
}

sealed class WrapperResult {
    data class Success(val typeSpec: TypeSpec, val funSpec: FunSpec) : WrapperResult()
    data class Error(val message: String) : WrapperResult()
}
