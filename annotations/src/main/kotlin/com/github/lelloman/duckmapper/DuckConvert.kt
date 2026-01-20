package com.github.lelloman.duckmapper

import kotlin.reflect.KClass

/**
 * Specifies a custom converter for a property when mapping between two types.
 *
 * The converter class must have an `operator fun invoke(source: S): T` method
 * that converts from the source property type to the target property type.
 *
 * Example:
 * ```
 * object StringToAvailability {
 *     operator fun invoke(value: String): Availability = try {
 *         Availability.valueOf(value)
 *     } catch (_: IllegalArgumentException) {
 *         Availability.Unavailable
 *     }
 * }
 *
 * @DuckMap(MovieDto::class, Movie::class)
 * @DuckConvert(MovieDto::class, Movie::class, "availability", StringToAvailability::class)
 * object Mappings
 * ```
 *
 * @param source The source class of the mapping
 * @param target The target class of the mapping
 * @param property The name of the property to apply the converter to
 * @param converter The converter class (must have operator fun invoke)
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DuckConvert(
    val source: KClass<*>,
    val target: KClass<*>,
    val property: String,
    val converter: KClass<*>
)
