/* A test of the C API, in C, so the header is checked as C. */
#include "almucantar_c.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;
#define CHECK(condition)                                                     \
  do {                                                                       \
    if (!(condition)) {                                                      \
      fprintf(stderr, "%s:%d: CHECK(%s)\n", __FILE__, __LINE__, #condition); \
      ++failures;                                                            \
    }                                                                        \
  } while (0)

int main(void) {
  const sr_observer vienna = {48.2082, 16.3738};
  const double day_start = 1781992800; /* 2026-06-21T00:00 CEST */

  sr_horizontal sun;
  CHECK(sr_sun_position(1782039420, vienna, sr_default_sun_options(), &sun) == SR_OK);
  CHECK(fabs(sun.azimuth_deg - 180) < 1 && fabs(sun.altitude_deg - 65.25) < 0.3);

  sr_day_events events = {0};
  CHECK(sr_sun_events(vienna, day_start, day_start + 86400, sr_default_event_options(), &events) ==
        SR_OK);
  CHECK(events.kind == 0 && events.crossing_count == 2 && events.crossings[0].rising);
  sr_free(events.crossings);

  sr_path_point* path = NULL;
  size_t count = 0;
  sr_path_options path_options = sr_default_path_options();
  path_options.step_s = 3600;
  CHECK(sr_sun_path(vienna, day_start, day_start + 86400, path_options, &path, &count) == SR_OK);
  CHECK(count == 25 && path[1].unix_utc == day_start + 3600);
  sr_free(path);

  sr_view view = sr_default_view();
  view.device_to_enu =
      (sr_quaternion){cos(M_PI / 4), sin(M_PI / 4), 0, 0}; /* upright, to the north */
  sr_polylines horizon = {0};
  CHECK(sr_horizon_line(view, &horizon) == SR_OK);
  CHECK(horizon.line_count == 1 && fabs(horizon.points[0].y - 500) < 1e-6);
  sr_free_polylines(&horizon);

  sr_azimuth_crossing* crossings = NULL;
  CHECK(sr_find_azimuth_times(vienna, sun.azimuth_deg, day_start, day_start + 86400,
                              sr_default_search_options(), &crossings, &count) == SR_OK);
  CHECK(count == 1);
  sr_free(crossings);

  /* The heading filter: a field to the north of the frame (device -z) and down (device -y),
     plus a bias of (1, 2, 3) µT. Then a sun tap. */
  sr_heading_filter filter;
  const sr_heading_filter_options filter_options = sr_default_heading_filter_options();
  CHECK(sr_heading_filter_start((sr_vector3){1, 2, 3}, 0.5, filter_options, &filter) == SR_OK);
  CHECK(filter.covariance[0] == sr_unknown_heading_variance_deg2());
  CHECK(sr_heading_filter_predict(&filter, 1, filter_options, &filter) == SR_OK);
  CHECK(sr_heading_filter_update_magnetometer(&filter, view.device_to_enu,
                                              (sr_vector3){1, -38, -17}, vienna, 1767225600,
                                              filter_options, &filter) == SR_OK);
  double declination = 0;
  CHECK(sr_magnetic_declination_deg(vienna, 1767225600, &declination) == SR_OK);
  CHECK(fabs(filter.offset_deg - declination) < 5);
  CHECK(sr_heading_filter_update(&filter, (sr_heading_measurement){10, 0.01}, &filter) == SR_OK);
  CHECK(fabs(filter.offset_deg - 10) < 0.1);
  CHECK(sr_heading_filter_predict(NULL, 1, filter_options, &filter) == SR_INVALID_ARGUMENT);

  /* The terrain horizon. A GeoTIFF of the core tests: 7 x 5 floats, with an overview. */
  FILE* file = fopen(ALMUCANTAR_TEST_DATA_DIR "/geotiff/float_tiles_deflate_p3.tif", "rb");
  CHECK(file != NULL);
  if (file != NULL) {
    unsigned char bytes[65536];
    const size_t size = fread(bytes, 1, sizeof bytes, file);
    fclose(file);
    sr_elevation_grid grid = {0};
    CHECK(sr_read_elevation_geotiff(bytes, size, 0, &grid) == SR_OK);
    CHECK(grid.rows == 7 && grid.columns == 5 && fabs(grid.north_latitude_deg - 47.75) < 1e-9);
    CHECK(grid.heights_m != NULL && fabs(grid.heights_m[0] - 1000.25) < 1e-3);
    sr_free_elevation_grid(&grid);
    CHECK(grid.heights_m == NULL);
    CHECK(sr_read_elevation_geotiff(bytes, size, 1, &grid) == SR_OK && grid.rows == 4);
    sr_free_elevation_grid(&grid);
    CHECK(sr_read_elevation_geotiff(bytes, 4, 0, &grid) == SR_INVALID_ARGUMENT);
    uint32_t prefix = 0;
    CHECK(sr_elevation_geotiff_prefix_size(bytes, size, 1, &prefix) == SR_OK);
    CHECK(prefix > 0 && prefix <= size);
    CHECK(sr_read_elevation_geotiff(bytes, prefix, 1, &grid) == SR_OK && grid.rows == 4);
    sr_free_elevation_grid(&grid);
    CHECK(sr_elevation_geotiff_prefix_size(bytes, 16, 0, &prefix) == SR_OK && prefix == 0);
  }

  sr_elevation_tile* tiles = NULL;
  CHECK(sr_elevation_tiles(vienna, 100000, &tiles, &count) == SR_OK);
  CHECK(count >= 4 && tiles[0].latitude_deg == 48 && tiles[0].longitude_deg == 16 &&
        tiles[0].distance_m == 0);
  sr_free(tiles);

  /* Flat ground at 150 m around Vienna: the horizon is a little below 0°. */
  enum { rows = 61, columns = 91 };
  static float flat[rows * columns];
  for (size_t i = 0; i < rows * columns; ++i) flat[i] = 150;
  const sr_elevation_grid flat_grid = {vienna.latitude_deg + 0.25,
                                       vienna.longitude_deg - 0.375,
                                       1.0 / 120,
                                       1.0 / 120,
                                       rows,
                                       columns,
                                       flat};
  sr_horizon_options horizon_options = sr_default_horizon_options();
  CHECK(horizon_options.azimuth_step_deg == 0.25 && horizon_options.use_grid_elevation);
  horizon_options.azimuth_step_deg = 10;
  sr_horizon_profile profile = {0};
  CHECK(sr_calculate_horizon_profile(vienna, &flat_grid, 1, horizon_options, &profile) == SR_OK);
  CHECK(profile.count == 36 && fabs(profile.observer_elevation_m - 150) < 1e-3);
  CHECK(profile.altitude_deg[0] < 0 && profile.altitude_deg[0] > -0.1);
  double altitude = 0;
  CHECK(sr_terrain_altitude_deg(&profile, 5, &altitude) == SR_OK);
  CHECK(fabs(altitude - (profile.altitude_deg[0] + profile.altitude_deg[1]) / 2) < 1e-9);
  sr_free_horizon_profile(&profile);
  CHECK(sr_terrain_altitude_deg(&profile, 5, &altitude) == SR_INVALID_ARGUMENT);
  CHECK(sr_calculate_horizon_profile(vienna, NULL, 1, horizon_options, &profile) ==
        SR_INVALID_ARGUMENT);

  /* A wall of 80° hides the sun all day. A flat profile gives a sunrise and a sunset. */
  double wall_altitude[360], wall_distance[360];
  for (size_t i = 0; i < 360; ++i) {
    wall_altitude[i] = 80;
    wall_distance[i] = 1000;
  }
  sr_horizon_profile wall = {1, wall_altitude, wall_distance, 360, 0};
  sr_terrain_events terrain = {0};
  CHECK(sr_find_terrain_events(vienna, &wall, day_start, day_start + 86400,
                               sr_default_terrain_event_options(), &terrain) == SR_OK);
  CHECK(terrain.kind == 2 && terrain.crossing_count == 0);
  sr_free(terrain.crossings);
  for (size_t i = 0; i < 360; ++i) wall_altitude[i] = 0;
  CHECK(sr_find_terrain_events(vienna, &wall, day_start, day_start + 86400,
                               sr_default_terrain_event_options(), &terrain) == SR_OK);
  CHECK(terrain.kind == 0 && terrain.crossing_count == 2 && terrain.crossings[0].rising);
  sr_free(terrain.crossings);
  CHECK(sr_find_terrain_events(vienna, NULL, day_start, day_start + 86400,
                               sr_default_terrain_event_options(),
                               &terrain) == SR_INVALID_ARGUMENT);

  /* The sky: Venus is bright, the galactic plane has 180 points for a step of 2°, and a star at
     the north celestial pole is at the latitude of the observer. */
  const sr_sky_options sky = sr_default_sky_options();
  CHECK(sky.refraction);
  sr_horizontal venus;
  CHECK(sr_planet_position(1, day_start, vienna, sky, &venus) == SR_OK);
  CHECK(venus.azimuth_deg >= 0 && venus.azimuth_deg < 360);
  double magnitude = 0;
  CHECK(sr_planet_magnitude(1, day_start, &magnitude) == SR_OK && magnitude < -3);
  CHECK(sr_planet_magnitude(7, day_start, &magnitude) == SR_INVALID_ARGUMENT);
  sr_horizontal center;
  CHECK(sr_galactic_position(sr_galactic_center_longitude_deg(), sr_galactic_center_latitude_deg(),
                             day_start, vienna, sky, &center) == SR_OK);
  sr_horizontal* plane = NULL;
  CHECK(sr_galactic_plane(day_start, vienna, 2, sky, &plane, &count) == SR_OK && count == 180);
  sr_free(plane);
  const double ra[2] = {0, 101.28708};
  const double dec[2] = {90, -16.71611};
  sr_star_catalog catalog = {ra, dec, NULL, NULL, 2};
  sr_horizontal* stars = NULL;
  sr_sky_options airless = sky;
  airless.refraction = false;
  CHECK(sr_star_positions(&catalog, day_start, vienna, airless, &stars, &count) == SR_OK);
  CHECK(count == 2 && fabs(stars[0].altitude_deg - vienna.latitude_deg) < 0.5);
  sr_free(stars);
  /* The view looks north at the horizon: north is at the center, and south is behind it. */
  const sr_horizontal directions[2] = {{0, 0}, {180, 0}};
  sr_projection* points = NULL;
  CHECK(sr_project_points(view, directions, 2, &points) == SR_OK);
  CHECK(points != NULL && points[0].valid && fabs(points[0].point.x - 500) < 1e-6);
  CHECK(points != NULL && !points[1].valid);
  sr_free(points);
  catalog.dec_deg = NULL;
  CHECK(sr_star_positions(&catalog, day_start, vienna, sky, &stars, &count) == SR_INVALID_ARGUMENT);
  CHECK(sr_project_points(view, NULL, 1, &points) == SR_INVALID_ARGUMENT);

  /* An invalid argument gives a status and a message, not an exception. */
  const sr_observer invalid = {91, 0};
  CHECK(sr_sun_position(0, invalid, sr_default_sun_options(), &sun) == SR_INVALID_ARGUMENT);
  CHECK(strstr(sr_last_error(), "latitude_deg") != NULL);
  CHECK(sr_sun_position(0, vienna, sr_default_sun_options(), NULL) == SR_INVALID_ARGUMENT);

  CHECK(strlen(sr_version()) >= 5);
  if (failures == 0) printf("all C API checks passed\n");
  return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
