#pragma once

#include <string_view>

namespace sunradar {

/// Returns the version of the core library, for example "0.1.0".
std::string_view version() noexcept;

}  // namespace sunradar
