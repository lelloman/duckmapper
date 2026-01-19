package com.example.app

import com.github.lelloman.duckmapper.DuckMap
import com.example.domain.*
import com.example.ui.*

@DuckMap(DomainAddress::class, UiAddress::class)
@DuckMap(DomainStatus::class, UiStatus::class)
@DuckMap(DomainUser::class, UiUser::class)
@DuckMap(DomainTeam::class, UiTeam::class)
@DuckMap(DomainId::class, UiId::class)
@DuckMap(DomainCache::class, UiCache::class)
object Mappings
