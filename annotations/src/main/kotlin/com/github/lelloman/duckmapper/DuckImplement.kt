package com.github.lelloman.duckmapper

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DuckImplement(
    val source: KClass<*>,
    val target: KClass<*>
)
