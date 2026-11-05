#pragma once

#include <vector>

#include "almucantar/sun.hpp"
#include "almucantar/terrain.hpp"
#include "almucantar/types.hpp"

/// The photo planner (Alidade, PLAN.md 14.1): a subject (a body of the sky) behind a landmark.
/// "I stand here" is `find_alignments()`, "where must I stand?" is `alignment_line()`, and the
/// checks of a match are `line_of_sight()` and `dark_windows()`.

namespace almucantar {

struct BodyOptions {
  SolarModel model = SolarModel::noaa;  ///< used only for the sun
  bool refraction = true;               ///< add the refraction, to get the position that one sees
  Atmosphere atmosphere;                ///< used only if `refraction` is true
};

/// Returns the apparent topocentric position of `body` at `unix_utc` for `observer`: the same as
/// `sun_position()`, `moon_position()`, `galactic_position()` of the galactic center, or
/// `planet_position()`. Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] Horizontal body_position(Body body, double unix_utc, const Observer& observer,
                                       const BodyOptions& options = {});

/// A point with its height: a place on the ground, the eye of an observer, or the top of a
/// landmark.
struct Site {
  double latitude_deg = 0;   ///< north is positive, -90 to 90
  double longitude_deg = 0;  ///< east is positive, -180 to 180
  double elevation_m = 0;    ///< above sea level
};

struct SightOptions {
  /// The coefficient of the terrestrial refraction, as in `HorizonOptions`. 0 gives none.
  double refraction_coefficient = 0.13;
  /// Used only by `line_of_sight()`: the terrain nearer than this to the target does not count,
  /// because it is the target itself. A DEM smooths a summit, so its flanks just below the top can
  /// be higher than the line of sight to the top. The terrain within 1.5 cells always counts as
  /// the target.
  double target_margin_m = 0;
};

/// The direction from one site to another, and the distance on the ground.
struct Sight {
  Horizontal direction;
  double distance_m = 0;
};

/// Returns the direction in which `from` sees `to`: the initial bearing of the great circle, and
/// the apparent altitude with the curvature of the Earth and the terrestrial refraction. This is
/// the model of `horizon_profile()`, so a landmark on the terrain line has the altitude of the
/// line. Throws `std::invalid_argument` if the sites are at the same place, more than 500 km
/// apart, or not valid.
[[nodiscard]] Sight sight(const Site& from, const Site& to, const SightOptions& options = {});

struct AlignmentOptions {
  BodyOptions body;
  /// Only the times when the body is nearer than this angle to the direction. The disc of the sun
  /// and the moon is about 0.5° wide, so 0.25° puts the center of the disc on the direction.
  double max_separation_deg = 1;
  double tolerance_s = 1;  ///< the time accuracy of the results
};

/// A time when a body is near a direction.
struct Alignment {
  double unix_utc = 0;
  double separation_deg = 0;  ///< the angle between the body and the direction
  Horizontal body;            ///< the position of the body at `unix_utc`
};

/// "I stand here": finds the times between `start_utc` and `end_utc` when `body` is nearest to
/// `direction` (for example the direction of a landmark from `sight()`) and nearer than
/// `max_separation_deg`, in time order. The body passes the hour angle of the direction once per
/// day (the moon once per 24.8 hours); the function follows the hour angle to that time and then
/// finds the smallest separation. The body can be below the horizon at a match, if the direction
/// is. Throws `std::invalid_argument` if `end_utc <= start_utc`, if the interval is longer than
/// 3660 days, or if an argument is not valid.
[[nodiscard]] std::vector<Alignment> find_alignments(Body body, const Observer& observer,
                                                     Horizontal direction, double start_utc,
                                                     double end_utc,
                                                     const AlignmentOptions& options = {});

struct AlignmentLineOptions {
  BodyOptions body;
  double min_distance_m = 100;     ///< the first point of the line, from the landmark
  double max_distance_m = 30'000;  ///< the last point of the line, at most 500 km
  double step_m = 100;             ///< the distance between two points, at most 100,000 points
  double eye_height_m = 1.6;       ///< the eye above the ground, for the spots
  /// The coefficient of the terrestrial refraction, as in `HorizonOptions`.
  double refraction_coefficient = 0.13;
};

/// A place on the line of an alignment.
struct LinePoint {
  Observer place;
  double distance_m = 0;  ///< from the landmark
  /// The elevation of the eye above sea level at which the landmark is in front of the body.
  /// A lower eye sees the landmark higher than the body.
  double eye_elevation_m = 0;
  /// The ground from the tiles, NaN if the tiles have no data.
  double ground_elevation_m = 0;
  Horizontal body;  ///< the body, seen from the place
};

struct AlignmentLine {
  /// The places from which the body is in the direction of the landmark, from the nearest to the
  /// farthest: they lie on a line away from the landmark, opposite to the body.
  std::vector<LinePoint> points;
  /// The places on the line where the ground plus the eye height is at `eye_elevation_m`: there,
  /// an observer on the ground sees the body behind the landmark. Linear between two points.
  std::vector<LinePoint> spots;
};

/// "Where must I stand?": the places from which `body` is behind `target` at `unix_utc`. For each
/// distance, the function finds the place whose bearing to the target is the azimuth of the body
/// there, and the height of the eye that puts the target on the body (with the model of
/// `sight()`). The moon is near, so its direction changes with the place (up to 1°); the function
/// calculates the body for each place. With `tiles` (DEM grids, as for `horizon_profile()`), the
/// points have the ground and the line has the spots; without them, the line has no spots. The
/// line does not check the view to the target: use `line_of_sight()` for a spot. Throws
/// `std::invalid_argument` if the body is within 1° of the zenith or the nadir, or if an argument
/// is not valid.
[[nodiscard]] AlignmentLine alignment_line(Body body, const Site& target, double unix_utc,
                                           const std::vector<ElevationGrid>& tiles,
                                           const AlignmentLineOptions& options = {});

/// The result of `line_of_sight()`.
struct LineOfSight {
  bool visible = true;
  /// The angle between the target and the highest terrain in front of it: positive if the target
  /// is visible. NaN if the tiles have no data between the two sites.
  double clearance_deg = 0;
  /// The distance from the eye to the highest terrain in front of the target. NaN if there is
  /// none.
  double blocking_distance_m = 0;
  Observer blocking;  ///< the place of the highest terrain in front of the target; NaN if none
};

/// Checks whether the eye at `from` sees `to` over the terrain of `tiles`, with the model of
/// `sight()`. The terrain within 1.5 cells of the target, or within `target_margin_m`, is the
/// target itself, so it does not count. Buildings and trees are not in a bare-earth DEM; a surface
/// model has them, smeared over its cells. Throws `std::invalid_argument` if `tiles` is empty, if
/// the sites are at the same place or more than 500 km apart, or if an argument is not valid.
[[nodiscard]] LineOfSight line_of_sight(const Site& from, const Site& to,
                                        const std::vector<ElevationGrid>& tiles,
                                        const SightOptions& options = {});

struct DarkOptions {
  /// The sky is dark if the airless center of the sun is below this altitude: -18° is the
  /// astronomical night, -12° the nautical twilight.
  double max_sun_altitude_deg = -18;
  /// The moon does not disturb if its apparent center is below this altitude over the terrain...
  double max_moon_altitude_deg = 0;
  /// ...or if its illuminated fraction is at most this value.
  double max_moon_fraction = 0.1;
  /// If true, a window also needs the galactic center above `min_galactic_center_altitude_deg`
  /// over the terrain: the windows for a photo of the Milky Way.
  bool galactic_center = false;
  double min_galactic_center_altitude_deg = 0;
  double step_s = 300;     ///< a window shorter than this can be missed
  double tolerance_s = 1;  ///< the time accuracy of the results
};

/// An interval of time.
struct TimeWindow {
  double start_utc = 0;
  double end_utc = 0;
};

/// Finds the windows between `start_utc` and `end_utc` when the sky is dark for `options`, in time
/// order. The altitudes of the moon and the galactic center are over the terrain of `profile`;
/// an empty profile is the horizon of 0°. A window that is open at `start_utc` or `end_utc` starts
/// or ends there. Throws `std::invalid_argument` if `end_utc <= start_utc`, if the interval has
/// more than 1,000,000 steps, or if an argument is not valid.
[[nodiscard]] std::vector<TimeWindow> dark_windows(const Observer& observer, double start_utc,
                                                   double end_utc, const DarkOptions& options = {},
                                                   const HorizonProfile& profile = {});

}  // namespace almucantar
