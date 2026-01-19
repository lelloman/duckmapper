# DuckMapper

A Kotlin KSP plugin for generating structural mappers between classes that share the same structure but live in independent modules.

*If it quacks the same, it should map automatically.*

## The Problem

In clean architecture setups, you often have:

```
       Module app (glue)
        /           \
   Module domain    Module ui
   (DomainUser)     (UiUser)
```

`domain` and `ui` are intentionally independent - neither depends on the other. But when `DomainUser` and `UiUser` have identical structures, you end up writing tedious 1:1 mapping code in `app`:

```kotlin
fun DomainUser.toUiUser() = UiUser(
    id = this.id,
    name = this.name,
    email = this.email
)
```

This is boilerplate. DuckMapper generates these mappers at compile time.

## Installation

Add Jitpack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add dependencies to your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.github.lelloman.duckmapper:annotations:0.1.0")
    ksp("com.github.lelloman.duckmapper:ksp:0.1.0")
}
```

## Usage

### Basic Mapping

Declare your mapping in the glue module:

```kotlin
@DuckMap(DomainUser::class, UiUser::class)
object Mappings
```

DuckMapper generates bidirectional extension functions:

```kotlin
// Generated
fun DomainUser.toUiUser(): UiUser = UiUser(
    id = this.id,
    name = this.name,
    email = this.email
)

fun UiUser.toDomainUser(): DomainUser = DomainUser(
    id = this.id,
    name = this.name,
    email = this.email
)
```

### Multiple Mappings

Stack multiple annotations on a single object:

```kotlin
@DuckMap(DomainUser::class, UiUser::class)
@DuckMap(DomainAddress::class, UiAddress::class)
@DuckMap(DomainSettings::class, UiSettings::class)
object AppMappings
```

### Nested Types

When classes contain nested types that also need mapping, declare mappings for both:

```kotlin
// Domain module
data class DomainUser(val id: String, val address: DomainAddress)
data class DomainAddress(val street: String, val city: String)

// UI module
data class UiUser(val id: String, val address: UiAddress)
data class UiAddress(val street: String, val city: String)

// App module - declare BOTH mappings
@DuckMap(DomainAddress::class, UiAddress::class)  // Required for nested type
@DuckMap(DomainUser::class, UiUser::class)
object Mappings
```

Generated code automatically uses the nested mapper:

```kotlin
fun DomainUser.toUiUser(): UiUser = UiUser(
    id = this.id,
    address = this.address.toUiAddress()  // Uses generated mapper
)
```

### Enums

DuckMapper generates exhaustive `when` expressions for enum mapping:

```kotlin
// domain
enum class DomainStatus { PENDING, ACTIVE }

// ui
enum class UiStatus { PENDING, ACTIVE, INACTIVE }

// app - explicit mapping required
@DuckMap(DomainStatus::class, UiStatus::class)
object Mappings
```

Generated code:
```kotlin
fun DomainStatus.toUiStatus(): UiStatus = when (this) {
    DomainStatus.PENDING -> UiStatus.PENDING
    DomainStatus.ACTIVE -> UiStatus.ACTIVE
}
```

**Validation rules:**
- ✅ Subset → Superset: OK (all source values exist in target)
- ❌ Superset → Subset: ERROR (source has values not in target)

### Collections

DuckMapper supports `List`, `Array`, and `Map` with automatic element mapping:

```kotlin
data class DomainTeam(val members: List<DomainUser>)
data class UiTeam(val members: List<UiUser>)

// With @DuckMap(DomainUser::class, UiUser::class) declared:
fun DomainTeam.toUiTeam(): UiTeam = UiTeam(
    members = this.members.map { it.toUiUser() }
)
```

For `Map`, both keys and values can be mapped:

```kotlin
data class DomainCache(val entries: Map<DomainId, DomainUser>)
data class UiCache(val entries: Map<UiId, UiUser>)

// With mappings declared for DomainId/UiId and DomainUser/UiUser:
fun DomainCache.toUiCache(): UiCache = UiCache(
    entries = this.entries.map { (k, v) -> k.toUiId() to v.toUiUser() }.toMap()
)
```

### Nullable Types

DuckMapper handles nullable type conversions:

- ✅ `String` → `String?` (downcast allowed)
- ❌ `String?` → `String` (compile error - unsafe)

```kotlin
data class DomainUser(val nickname: String)      // non-nullable
data class UiUser(val nickname: String?)         // nullable - OK

// Works: non-nullable can map to nullable
fun DomainUser.toUiUser(): UiUser = UiUser(nickname = this.nickname)

// Reverse mapping will fail at compile time with clear error message
```

### Subset Mapping

A class with more properties can map to a class with fewer properties:

```kotlin
data class DomainUser(
    val id: String,
    val name: String,
    val internalFlag: Boolean  // Extra property
)

data class UiUser(
    val id: String,
    val name: String
    // No internalFlag - that's fine
)

// Works: DomainUser → UiUser (extra properties ignored)
// Fails: UiUser → DomainUser (missing required property)
```

## Error Messages

DuckMapper provides clear compile-time errors:

**Missing property:**
```
Cannot map UiUser to DomainUser: missing property 'internalFlag' in source class
```

**Type mismatch without mapping:**
```
Property 'address' type mismatch: DomainAddress -> UiAddress.
No @DuckMap declaration found for these types.
```

**Nullable to non-nullable:**
```
Property 'nickname': cannot map nullable type to non-nullable type (kotlin.String? -> kotlin.String)
```

## Key Design Principle

**The annotation lives in the glue module only.** Neither the source nor target class needs any annotation. This keeps your `domain` and `ui` modules completely clean and independent.

## Android Example

Here's a typical Android clean architecture setup:

```
app/
├── build.gradle.kts          # Has KSP plugin + DuckMapper dependencies
└── src/main/kotlin/
    └── com/example/app/
        └── Mappings.kt       # @DuckMap declarations here

domain/
├── build.gradle.kts          # Pure Kotlin, no DuckMapper dependency
└── src/main/kotlin/
    └── com/example/domain/
        └── Models.kt         # DomainUser, DomainAddress, etc.

presentation/
├── build.gradle.kts          # Pure Kotlin, no DuckMapper dependency
└── src/main/kotlin/
    └── com/example/ui/
        └── Models.kt         # UiUser, UiAddress, etc.
```

**app/build.gradle.kts:**
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":presentation"))

    implementation("com.github.lelloman.duckmapper:annotations:0.1.0")
    ksp("com.github.lelloman.duckmapper:ksp:0.1.0")
}
```

**app/src/main/kotlin/.../Mappings.kt:**
```kotlin
package com.example.app

import com.github.lelloman.duckmapper.DuckMap
import com.example.domain.*
import com.example.ui.*

@DuckMap(DomainAddress::class, UiAddress::class)
@DuckMap(DomainUser::class, UiUser::class)
@DuckMap(DomainTeam::class, UiTeam::class)
object Mappings
```

**Usage in ViewModel:**
```kotlin
class UserViewModel(private val getUserUseCase: GetUserUseCase) : ViewModel() {

    private val _user = MutableStateFlow<UiUser?>(null)
    val user: StateFlow<UiUser?> = _user

    fun loadUser(id: String) {
        viewModelScope.launch {
            val domainUser = getUserUseCase(id)
            _user.value = domainUser.toUiUser()  // Generated extension function
        }
    }
}
```

## Requirements

- Kotlin 1.9+
- KSP 1.9+
- Classes must be data classes (for now)
- Property names must match between source and target

## License

Apache 2.0
