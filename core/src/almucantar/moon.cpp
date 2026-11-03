#include "almucantar/moon.hpp"

#include "almucantar/internal/astronomy_engine.hpp"
#include "almucantar/internal/refraction.hpp"
#include "almucantar/internal/validate.hpp"

namespace almucantar {

Horizontal moon_position(const double unix_utc, const Observer& observer,
                         const MoonOptions& options) {
  internal::require_finite(unix_utc, "unix_utc");
  internal::validate(observer);
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }
  const Horizontal position = internal::moon_airless(unix_utc, observer);
  return options.refraction ? internal::refracted(position, options.atmosphere) : position;
}

double moon_illuminated_fraction(const double unix_utc) {
  internal::require_finite(unix_utc, "unix_utc");
  return internal::moon_fraction(unix_utc);
}

}  // namespace almucantar
