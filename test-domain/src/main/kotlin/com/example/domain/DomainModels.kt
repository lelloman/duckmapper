package com.example.domain

data class DomainUser(
    val id: String,
    val name: String,
    val email: String,
    val address: DomainAddress,
    val status: DomainStatus
)

data class DomainAddress(
    val street: String,
    val city: String,
    val zipCode: String
)

data class DomainTeam(
    val id: String,
    val name: String,
    val members: List<DomainUser>
)

data class DomainId(val value: String)

data class DomainCache(
    val entries: Map<DomainId, DomainUser>
)

// Enum with subset of values (PENDING, ACTIVE) - can map TO larger enum
enum class DomainStatus {
    PENDING,
    ACTIVE
}

// Enum with more values - can only map FROM, not TO DomainStatus
enum class DomainStatusExtended {
    PENDING,
    ACTIVE,
    INACTIVE,
    ARCHIVED
}

// Interface as source - common pattern for API responses
interface Identifiable {
    val id: String
    val name: String
}

// Interface for @DuckWrap testing - source must implement target interface
interface DomainDisplayable {
    val title: String
    val description: String
}

// Concrete class for @DuckWrap testing - implements the interface
// @DuckWrap creates a wrapper that hides implementation details
data class DomainItem(
    override val title: String,
    override val description: String,
    val extraData: String  // Additional property not in interface (hidden by wrapper)
) : DomainDisplayable

// Concrete class for @DuckImplement testing - does NOT implement interface
// @DuckImplement generates an implementation by copying values
data class DomainDetails(
    val title: String,
    val description: String,
    val metadata: String
)

// Sealed interface for connection state mapping
sealed interface DomainConnectionState {
    data object Disconnected : DomainConnectionState
    data object Connecting : DomainConnectionState
    data class Connected(val deviceId: Int, val serverVersion: String) : DomainConnectionState
    data class Error(val message: String) : DomainConnectionState
}

// DTO with String that maps to Enum (for testing automatic String -> Enum conversion)
data class MovieDto(
    val id: String,
    val title: String,
    val availability: String  // "Available", "ComingSoon", "Unavailable"
)

enum class ContentAvailability {
    Available,
    ComingSoon,
    Unavailable
}

data class Movie(
    val id: String,
    val title: String,
    val availability: ContentAvailability
)

// For testing custom converter with fallback
data class MovieDtoWithFallback(
    val id: String,
    val title: String,
    val availability: String
)

data class MovieWithFallback(
    val id: String,
    val title: String,
    val availability: ContentAvailability
)

// Custom converter object
object StringToAvailabilityWithFallback {
    operator fun invoke(value: String): ContentAvailability = try {
        ContentAvailability.valueOf(value)
    } catch (_: IllegalArgumentException) {
        ContentAvailability.Unavailable
    }
}
