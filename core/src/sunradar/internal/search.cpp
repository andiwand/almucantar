#include "sunradar/internal/search.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/numeric.hpp"
#include "sunradar/internal/refraction.hpp"

namespace sunradar::internal {

namespace {

/// An upper bound of the rate of change of the separation, in radians per second. The sun moves
/// in the sky at most with the rotation of the Earth (7.29e-5 rad/s), and the refraction makes
/// the apparent motion slower near the horizon, not faster.
constexpr double kMaxRate = 1e-4;

}  // namespace

TimeMatch nearest_time(const SolarModelImpl& model, const Observer& observer, const Vec3 direction,
                       const double start_utc, const double end_utc, const double step_s,
                       const double tolerance_s, const Atmosphere* const atmosphere) {
  const auto position = [&](const double t) {
    const Horizontal h = model.sun(t, observer);
    return atmosphere != nullptr ? refracted(h, *atmosphere) : h;
  };
  // The angle in radians; atan2 keeps the precision near 0.
  const auto separation = [&](const double t) {
    const Vec3 s = to_enu(position(t));
    return std::atan2(norm(cross(s, direction)), dot(s, direction));
  };

  // Sample with a fixed step. The last sample is at the end.
  const double span = end_utc - start_utc;
  const auto steps = static_cast<std::size_t>(std::max(1.0, std::ceil(span / step_s)));
  const double step = span / static_cast<double>(steps);
  double best_t = start_utc;
  double best_s = separation(start_utc);
  const auto keep = [&](const double t, const double s) {
    if (s < best_s) {
      best_s = s;
      best_t = t;
    }
  };

  // A sampled minimum: the bracket around it and its sampled separation.
  struct Bracket {
    double a;
    double b;
    double s;
  };
  std::vector<Bracket> brackets;
  const auto add = [&](const double a, const double b, const double s) {
    if (brackets.empty() || brackets.back().a != a || brackets.back().b != b) {
      brackets.push_back({.a = a, .b = b, .s = s});
    }
  };
  double a = start_utc;
  double sa = best_s;
  double b = start_utc;
  double sb = best_s;
  for (std::size_t i = 1; i <= steps; ++i) {
    const double t = i == steps ? end_utc : start_utc + static_cast<double>(i) * step;
    const double s = separation(t);
    keep(t, s);
    if (sb <= sa && sb <= s) {
      add(a, t, sb);
    }
    a = b;
    sa = sb;
    b = t;
    sb = s;
  }
  if (sb <= sa) {
    add(a, b, sb);
  }

  // The best sample need not surround the best minimum, especially when the interval contains
  // several days. The minimum in a bracket is at most one step from its sample, so it is at
  // least the sampled separation minus kMaxRate * step. Refine only a bracket that can contain
  // a better minimum, and start with the lowest sample.
  std::ranges::sort(brackets, {}, &Bracket::s);
  for (const Bracket& bracket : brackets) {
    if (bracket.s - kMaxRate * step >= best_s) {
      break;
    }
    const double t = golden_min(separation, bracket.a, bracket.b, tolerance_s);
    keep(t, separation(t));
  }
  return TimeMatch{
      .unix_utc = best_t, .separation_deg = best_s * kRadToDeg, .sun = position(best_t)};
}

}  // namespace sunradar::internal
