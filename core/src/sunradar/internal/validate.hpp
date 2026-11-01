#pragma once

#include "sunradar/types.hpp"

namespace sunradar::internal {

/// Throws `std::invalid_argument(message)` if `condition` is false.
void require(bool condition, const char* message);

/// Throws `std::invalid_argument` that names `name` if `value` is not finite.
void require_finite(double value, const char* name);

/// Throws `std::invalid_argument` that names `name` if `value` is not finite or not positive.
void require_positive(double value, const char* name);

/// Throws `std::invalid_argument` that names `name` if a component of `q` is not finite, or if
/// `q` has length 0.
void validate(const Quaternion& q, const char* name);

/// Validates the fields of the value types. Each function throws `std::invalid_argument`.
void validate(const Observer& observer);
void validate(const Atmosphere& atmosphere);

}  // namespace sunradar::internal
