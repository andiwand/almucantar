#include "almucantar/search.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

#include "almucantar/internal/math.hpp"
#include "almucantar/internal/numeric.hpp"
#include "almucantar/internal/refraction.hpp"
#include "almucantar/internal/search.hpp"
#include "almucantar/internal/solar_model.hpp"
#include "almucantar/internal/validate.hpp"
#include "almucantar/internal/vector.hpp"

namespace almucantar {

namespace {

constexpr double kDay = 86400;
constexpr double kMaxSteps = 1'000'000;
/// find_dates searches each day, so it may take more steps: a year at the default step of 300 s
/// takes about 1e5.
constexpr double kMaxDateSteps = 10'000'000;
constexpr double kMaxDays = 3660;

using internal::require;
using internal::require_finite;

void validate(const Observer& observer, const Horizontal& direction, const double start_utc,
              const double end_utc, const SearchOptions& options) {
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require_finite(direction.azimuth_deg, "azimuth_deg");
  require_finite(direction.altitude_deg, "altitude_deg");
  require(direction.altitude_deg >= -90 && direction.altitude_deg <= 90,
          "altitude_deg is not in the range [-90, 90]");
  internal::validate(observer);
  require(end_utc > start_utc, "end_utc is not after start_utc");
  internal::require_positive(options.step_s, "step_s");
  internal::require_positive(options.tolerance_s, "tolerance_s");
  require(options.step_s <= kDay, "step_s is longer than one day");
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }
}

TimeMatch search_window(const Observer& observer, const internal::Vec3 direction,
                        const double start_utc, const double end_utc,
                        const SearchOptions& options) {
  return internal::nearest_time(internal::solar_model(options.model), observer, direction,
                                start_utc, end_utc, options.step_s, options.tolerance_s,
                                options.refraction ? &options.atmosphere : nullptr);
}

}  // namespace

TimeMatch find_time(const Observer& observer, const Horizontal direction, const double start_utc,
                    const double end_utc, const SearchOptions& options) {
  validate(observer, direction, start_utc, end_utc, options);
  require((end_utc - start_utc) / options.step_s <= kMaxSteps,
          "the interval has too many search steps");
  return search_window(observer, internal::to_enu(direction), start_utc, end_utc, options);
}

std::vector<TimeMatch> find_dates(const Observer& observer, const Horizontal direction,
                                  const double start_utc, const double end_utc,
                                  const SearchOptions& options) {
  validate(observer, direction, start_utc, end_utc, options);
  require_finite(options.max_separation_deg, "max_separation_deg");
  require(end_utc - start_utc >= kDay, "the interval is shorter than one day");
  require(end_utc - start_utc <= kMaxDays * kDay, "the interval is longer than 3660 days");
  require((end_utc - start_utc) / options.step_s <= kMaxDateSteps,
          "the interval has too many search steps");

  const internal::Vec3 d = internal::to_enu(direction);
  const auto days = static_cast<std::size_t>(std::ceil((end_utc - start_utc) / kDay));
  std::vector<TimeMatch> daily;
  daily.reserve(days);
  for (std::size_t i = 0; i < days; ++i) {
    const double start = start_utc + static_cast<double>(i) * kDay;
    // The last window ends at end_utc, so it finds a minimum in a partial last day.
    const double end = std::min(start + kDay, end_utc);
    daily.push_back(search_window(observer, d, start, end, options));
  }

  // A local minimum over the days: not larger than the day before, and smaller than
  // the day after. The first and the last day compare with the day outside the interval, so a
  // minimum outside the interval does not give a date at its edge.
  const double first_neighbor =
      search_window(observer, d, start_utc - kDay, start_utc, options).separation_deg;
  const double last_neighbor =
      search_window(observer, d, end_utc, end_utc + kDay, options).separation_deg;
  std::vector<TimeMatch> result;
  for (std::size_t i = 0; i < daily.size(); ++i) {
    const double s = daily[i].separation_deg;
    const bool before = s <= (i == 0 ? first_neighbor : daily[i - 1].separation_deg);
    const bool after = s < (i + 1 == daily.size() ? last_neighbor : daily[i + 1].separation_deg);
    if (before && after && s <= options.max_separation_deg) {
      result.push_back(daily[i]);
    }
  }
  return result;
}

std::vector<AzimuthCrossing> find_azimuth_times(const Observer& observer, const double azimuth_deg,
                                                const double start_utc, const double end_utc,
                                                const SearchOptions& options) {
  validate(observer, {.azimuth_deg = azimuth_deg, .altitude_deg = 0}, start_utc, end_utc, options);
  require((end_utc - start_utc) / options.step_s <= kMaxSteps,
          "the interval has too many search steps");

  const internal::SolarModelImpl& model = internal::solar_model(options.model);
  const auto position = [&](const double t) {
    const Horizontal h = model.sun(t, observer);
    return options.refraction ? internal::refracted(h, options.atmosphere) : h;
  };
  // The signed difference of the azimuths. It jumps from +180 to -180 when the sun is opposite
  // the direction; such a sign change is not a crossing.
  const auto difference = [&](const double t) {
    return internal::wrap_180(position(t).azimuth_deg - azimuth_deg);
  };

  std::vector<AzimuthCrossing> result;
  const double span = end_utc - start_utc;
  const auto steps = static_cast<std::size_t>(std::max(1.0, std::ceil(span / options.step_s)));
  const double step = span / static_cast<double>(steps);
  double t_prev = start_utc;
  double d_prev = difference(t_prev);
  if (d_prev == 0) {
    result.push_back({.unix_utc = t_prev, .sun = position(t_prev)});
  }
  for (std::size_t i = 1; i <= steps; ++i) {
    const double t = i == steps ? end_utc : start_utc + static_cast<double>(i) * step;
    const double d = difference(t);
    if (d == 0) {
      result.push_back({.unix_utc = t, .sun = position(t)});
    } else if (d_prev != 0 && (d < 0) != (d_prev < 0) && std::abs(d - d_prev) < 180) {
      const double root = internal::bisect(difference, t_prev, t, d_prev, options.tolerance_s);
      result.push_back({.unix_utc = root, .sun = position(root)});
    }
    t_prev = t;
    d_prev = d;
  }
  return result;
}

}  // namespace almucantar
