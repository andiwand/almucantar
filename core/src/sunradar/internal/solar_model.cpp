#include "sunradar/internal/solar_model.hpp"

#include <stdexcept>

#include "sunradar/internal/astronomy_engine.hpp"
#include "sunradar/internal/noaa_model.hpp"

namespace sunradar::internal {

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

}  // namespace sunradar::internal
