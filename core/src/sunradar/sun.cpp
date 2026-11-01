#include "sunradar/sun.hpp"

#include "sunradar/internal/events.hpp"
#include "sunradar/internal/path.hpp"
#include "sunradar/internal/refraction.hpp"
#include "sunradar/internal/solar_model.hpp"
#include "sunradar/internal/validate.hpp"

namespace sunradar {

namespace {

constexpr double kMaxSteps = 1'000'000;

using internal::require;
using internal::require_finite;
using internal::validate;

}  // namespace

Horizontal sun_position(const double unix_utc, const Observer& observer,
                        const SunOptions& options) {
  require_finite(unix_utc, "unix_utc");
  validate(observer);
  if (options.refraction) {
    validate(options.atmosphere);
  }
  const Horizontal position = internal::solar_model(options.model).sun(unix_utc, observer);
  return options.refraction ? internal::refracted(position, options.atmosphere) : position;
}

DayEvents sun_events(const Observer& observer, const double start_utc, const double end_utc,
                     const EventOptions& options) {
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require_finite(options.altitude_deg, "altitude_deg");
  validate(observer);
  require(end_utc > start_utc, "end_utc is not after start_utc");
  require(options.altitude_deg >= -90 && options.altitude_deg <= 90,
          "altitude_deg is not in the range [-90, 90]");
  require(options.search_step_s > 0, "search_step_s is not positive");
  require(options.tolerance_s > 0, "tolerance_s is not positive");
  require((end_utc - start_utc) / options.search_step_s <= kMaxSteps,
          "the interval has too many search steps");
  return internal::find_events(internal::solar_model(options.model), observer, start_utc, end_utc,
                               options.altitude_deg, options.search_step_s, options.tolerance_s);
}

std::vector<PathPoint> sun_path(const Observer& observer, const double start_utc,
                                const double end_utc, const PathOptions& options) {
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  validate(observer);
  require(end_utc >= start_utc, "end_utc is before start_utc");
  require(options.step_s > 0, "step_s is not positive");
  require((end_utc - start_utc) / options.step_s < kMaxSteps, "the path has too many points");
  if (options.refraction) {
    validate(options.atmosphere);
  }
  return internal::compute_path(internal::solar_model(options.model), observer, start_utc, end_utc,
                                options.step_s, options.refraction ? &options.atmosphere : nullptr);
}

}  // namespace sunradar
