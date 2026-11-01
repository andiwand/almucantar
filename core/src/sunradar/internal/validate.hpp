#pragma once

#include "sunradar/types.hpp"

namespace sunradar::internal {

/// Throws `std::invalid_argument(message)` if `condition` is false.
void require(bool condition, const char* message);

/// Throws `std::invalid_argument` that names `name` if `value` is not finite.
void require_finite(double value, const char* name);

/// Validates the fields of the value types. Each function throws `std::invalid_argument`.
void validate(const Observer& observer);
void validate(const Atmosphere& atmosphere);

}  // namespace sunradar::internal
