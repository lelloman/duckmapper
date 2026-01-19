package com.example.app

import com.github.lelloman.duckmapper.DuckMap
import com.github.lelloman.duckmapper.DuckWrap
import com.github.lelloman.duckmapper.DuckImplement
import com.example.domain.*
import com.example.ui.*

@DuckMap(DomainAddress::class, UiAddress::class)
@DuckMap(DomainStatus::class, UiStatus::class)
@DuckMap(DomainUser::class, UiUser::class)
@DuckMap(DomainTeam::class, UiTeam::class)
@DuckMap(DomainId::class, UiId::class)
@DuckMap(DomainCache::class, UiCache::class)
@DuckMap(Identifiable::class, SimpleItem::class)
// @DuckWrap: source must implement target interface - creates wrapper using Kotlin delegation
@DuckWrap(DomainItem::class, DomainDisplayable::class)
// @DuckImplement: source just needs matching properties - generates implementation class
@DuckImplement(DomainDetails::class, UiDisplayable::class)
// Sealed interface mapping: Domain (subset) -> Ui (superset with extra Reconnecting state)
@DuckMap(DomainConnectionState::class, UiConnectionState::class)
object Mappings
