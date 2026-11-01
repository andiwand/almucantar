#pragma once

namespace sunradar::internal {

/// Finds a root of `f` in [a, b] with bisection. `f(a)` must have the sign of `fa`,
/// and `f(b)` must have the other sign.
template <typename F>
double bisect(const F& f, double a, double b, double fa, const double tolerance) {
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

/// Finds the minimum of a unimodal function `f` in [a, b] with a golden-section search.
template <typename F>
double golden_min(const F& f, double a, double b, const double tolerance) {
  constexpr double kInvPhi = 0.6180339887498949;
  double c = b - kInvPhi * (b - a);
  double d = a + kInvPhi * (b - a);
  double fc = f(c);
  double fd = f(d);
  while (b - a > tolerance) {
    if (fc < fd) {
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

}  // namespace sunradar::internal
