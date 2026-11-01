#pragma once

#include "sunradar/internal/solar_model.hpp"
#include "sunradar/types.hpp"

namespace sunradar::internal {

/// The sun from Astronomy Engine (third_party/astronomy-engine): the apparent topocentric
/// position of date, with aberration, without refraction. It is more precise than NoaaModel.
class AstronomyEngineModel final : public SolarModelImpl {
 public:
  [[nodiscard]] Horizontal sun(double unix_utc, const Observer& observer) const override;
};

/// The apparent topocentric position of the center of the moon, without refraction.
[[nodiscard]] Horizontal moon_airless(double unix_utc, const Observer& observer);

/// The illuminated fraction of the disc of the moon, 0 (new moon) to 1 (full moon).
[[nodiscard]] double moon_fraction(double unix_utc);

}  // namespace sunradar::internal
