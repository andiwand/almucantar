#pragma once

#include <vector>

#include "almucantar/internal/solar_model.hpp"
#include "almucantar/sky.hpp"
#include "almucantar/types.hpp"

namespace almucantar::internal {

/// The sun from Astronomy Engine (third_party/astronomy-engine): the apparent topocentric
/// position of date, with aberration, without refraction. It is more precise than NoaaModel.
class AstronomyEngineModel final : public SolarModelImpl {
 public:
  [[nodiscard]] Horizontal sun(double unix_utc, const Observer& observer) const override;
};

/// The apparent topocentric position of the center of the moon, without refraction.
[[nodiscard]] Horizontal moon_airless(double unix_utc, const Observer& observer);

/// The apparent topocentric position of the center of a planet, without refraction.
[[nodiscard]] Horizontal planet_airless(Planet planet, double unix_utc, const Observer& observer);

/// The visual magnitude of a planet, as seen from the center of the Earth.
[[nodiscard]] double planet_visual_magnitude(Planet planet, double unix_utc);

/// A direction in the galactic system (IAU 1958).
struct Galactic {
  double longitude_deg = 0;
  double latitude_deg = 0;
};

/// The positions of galactic directions for the observer, without refraction and aberration.
[[nodiscard]] std::vector<Horizontal> galactic_airless(const std::vector<Galactic>& directions,
                                                       double unix_utc, const Observer& observer);

/// The illuminated fraction of the disc of the moon, 0 (new moon) to 1 (full moon).
[[nodiscard]] double moon_fraction(double unix_utc);

}  // namespace almucantar::internal
