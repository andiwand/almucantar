#include "sunradar/internal/events.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

#include "sunradar/internal/numeric.hpp"

namespace sunradar::internal {

DayEvents find_events(const SolarModelImpl& model, const Observer& observer, const double start_utc,
                      const double end_utc, const double altitude_deg, const double search_step_s,
                      const double tolerance_s) {
  DayEvents result;

  const auto altitude = [&](double t) { return model.sun(t, observer).altitude_deg; };
  const auto above = [&](double t) { return altitude(t) - altitude_deg; };

  // Sample the interval with a fixed step. The last sample is at the end.
  const double span = end_utc - start_utc;
  const auto steps = static_cast<std::size_t>(std::max(1.0, std::ceil(span / search_step_s)));
  const double step = span / static_cast<double>(steps);

  double t_prev = start_utc;
  double f_prev = above(t_prev);
  double best_t = t_prev;
  double best_f = f_prev;

  for (std::size_t i = 1; i <= steps; ++i) {
    const double t = i == steps ? end_utc : start_utc + static_cast<double>(i) * step;
    const double f = above(t);
    if ((f > 0) != (f_prev > 0)) {
      result.crossings.push_back(Crossing{
          .unix_utc = bisect(above, t_prev, t, f_prev, tolerance_s),
          .rising = f > 0,
      });
    }
    if (f > best_f) {
      best_f = f;
      best_t = t;
    }
    t_prev = t;
    f_prev = f;
  }

  // Refine the culmination between the neighbors of the highest sample. The
  // search can give a lower value if the maximum is at one end of the interval,
  // so keep the better of the two.
  result.culmination_utc = best_t;
  result.culmination_altitude_deg = best_f + altitude_deg;
  const auto negative_altitude = [&](double t) { return -altitude(t); };
  const double refined = golden_min(negative_altitude, std::max(start_utc, best_t - step),
                                    std::min(end_utc, best_t + step), tolerance_s);
  const double refined_altitude = altitude(refined);
  if (refined_altitude > result.culmination_altitude_deg) {
    result.culmination_utc = refined;
    result.culmination_altitude_deg = refined_altitude;
  }

  if (result.crossings.empty()) {
    // No crossing, so all samples are on the same side of the altitude.
    result.kind = above(start_utc) <= 0 ? DayKind::polar_night : DayKind::polar_day;
  }
  return result;
}

}  // namespace sunradar::internal
