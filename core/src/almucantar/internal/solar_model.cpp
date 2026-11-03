#include "almucantar/internal/solar_model.hpp"

#include <stdexcept>

#include "almucantar/internal/astronomy_engine.hpp"
#include "almucantar/internal/noaa_model.hpp"

namespace almucantar::internal {

const SolarModelImpl& solar_model(const SolarModel model) {
  switch (model) {
    case SolarModel::noaa: {
      static const NoaaModel kNoaa;
      return kNoaa;
    }
    case SolarModel::astronomy_engine: {
      static const AstronomyEngineModel kAstronomyEngine;
      return kAstronomyEngine;
    }
  }
  throw std::invalid_argument("unknown solar model");
}

}  // namespace almucantar::internal
