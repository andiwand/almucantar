#include "sunradar/internal/events.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace sunradar::internal {

namespace {

/// Finds a root of `f` in [a, b] with bisection. `f(a)` must have the sign of `fa`,
/// and `f(b)` must have the other sign.
template <typename F>
double bisect(const F& f, double a, double b, double fa, double tolerance) {
  while (b - a > tolerance) {
    const double mid = 0.5 * (a + b);
    const double fm = f(mid);
    if ((fm < 0) == (fa < 0)) {
      a = mid;
      fa = fm;
    } else {
      b = mid;
    }
  }
  return 0.5 * (a + b);
}

/// Finds the maximum of a unimodal function `f` in [a, b] with a golden-section search.
template <typename F>
double golden_max(const F& f, double a, double b, double tolerance) {
  constexpr double kInvPhi = 0.6180339887498949;
  double c = b - kInvPhi * (b - a);
  double d = a + kInvPhi * (b - a);
  double fc = f(c);
  double fd = f(d);
  while (b - a > tolerance) {
    if (fc > fd) {
      b = d;
      d = c;
      fd = fc;
      c = b - kInvPhi * (b - a);
      fc = f(c);
    } else {
      a = c;
      c = d;
      fc = fd;
      d = a + kInvPhi * (b - a);
      fd = f(d);
    }
  }
  return 0.5 * (a + b);
}

}  // namespace

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
  bool any_below = f_prev <= 0;

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
    any_below = any_below || f <= 0;
    t_prev = t;
    f_prev = f;
  }

  // Refine the culmination between the neighbors of the highest sample. The
  // search can give a lower value if the maximum is at one end of the interval,
  // so keep the better of the two.
  result.culmination_utc = best_t;
  result.culmination_altitude_deg = best_f + altitude_deg;
  const double refined = golden_max(altitude, std::max(start_utc, best_t - step),
                                    std::min(end_utc, best_t + step), tolerance_s);
  const double refined_altitude = altitude(refined);
  if (refined_altitude > result.culmination_altitude_deg) {
    result.culmination_utc = refined;
    result.culmination_altitude_deg = refined_altitude;
  }

  if (result.crossings.empty()) {
    result.kind = any_below ? DayKind::polar_night : DayKind::polar_day;
  }
  return result;
}

}  // namespace sunradar::internal
