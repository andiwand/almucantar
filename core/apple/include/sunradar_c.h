/* The C API of the sunradar core, for the Swift package (core/apple). It wraps the public
 * C++ API (the headers in core/src/sunradar) in plain C: the structs have the same fields, and no
 * function throws. Each function returns SR_OK, or an error code; sr_last_error() gives the
 * message of the last error of the calling thread. A function that returns a list allocates
 * it; free it with sr_free(). All times are Unix time in seconds (UTC), all angles in degrees. */

#ifndef SUNRADAR_C_H
#define SUNRADAR_C_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum sr_status { SR_OK = 0, SR_INVALID_ARGUMENT = 1, SR_ERROR = 2 } sr_status;

typedef struct sr_observer {
  double latitude_deg, longitude_deg;
} sr_observer;
typedef struct sr_horizontal {
  double azimuth_deg, altitude_deg;
} sr_horizontal;
typedef struct sr_atmosphere {
  double pressure_hpa, temperature_c;
} sr_atmosphere;
typedef struct sr_quaternion {
  double w, x, y, z;
} sr_quaternion;
typedef struct sr_vector3 {
  double x, y, z;
} sr_vector3;
typedef struct sr_screen_point {
  double x, y;
} sr_screen_point;

typedef struct sr_sun_options {
  int model;
  bool refraction;
  sr_atmosphere atmosphere;
} sr_sun_options;
typedef struct sr_event_options {
  int model;
  double altitude_deg, search_step_s, tolerance_s;
} sr_event_options;
typedef struct sr_path_options {
  int model;
  double step_s;
  bool refraction;
  sr_atmosphere atmosphere;
} sr_path_options;
typedef struct sr_search_options {
  int model;
  double step_s, tolerance_s;
  bool refraction;
  sr_atmosphere atmosphere;
  double max_separation_deg;
} sr_search_options;
typedef struct sr_moon_options {
  bool refraction;
  sr_atmosphere atmosphere;
} sr_moon_options;

typedef struct sr_camera {
  double width, height, fx, fy, cx, cy, k1, k2, k3, p1, p2;
} sr_camera;
typedef struct sr_view {
  sr_quaternion device_to_enu;
  int screen_rotation;
  double heading_offset_deg;
  sr_camera camera;
} sr_view;

typedef struct sr_projection {
  bool valid;
  sr_screen_point point;
} sr_projection;
typedef struct sr_crossing {
  double unix_utc;
  bool rising;
} sr_crossing;
typedef struct sr_day_events {
  int kind;
  sr_crossing* crossings; /* free with sr_free() */
  size_t crossing_count;
  double culmination_utc, culmination_altitude_deg;
} sr_day_events;
typedef struct sr_path_point {
  double unix_utc;
  sr_horizontal position;
} sr_path_point;
typedef struct sr_time_match {
  double unix_utc, separation_deg;
  sr_horizontal sun;
} sr_time_match;
typedef struct sr_azimuth_crossing {
  double unix_utc;
  sr_horizontal sun;
} sr_azimuth_crossing;
/* Polylines: all points one after the other; line i has counts[i] points. */
typedef struct sr_polylines {
  sr_screen_point* points; /* free with sr_free() */
  size_t* counts;          /* free with sr_free() */
  size_t line_count;
} sr_polylines;
typedef struct sr_heading_estimate {
  double offset_deg, variance_deg2;
} sr_heading_estimate;
typedef struct sr_heading_measurement {
  double offset_deg, variance_deg2;
} sr_heading_measurement;
typedef struct sr_magnetometer_options {
  double sensor_sigma_deg;
} sr_magnetometer_options;
/* The number of values in the state of a heading filter. */
#define SR_HEADING_FILTER_SIZE 7
/* The heading filter with the magnetometer. The covariance is row by row, in the order offset,
   bias x, y, z, disturbance east, north, up. Make it with sr_heading_filter_start(). */
typedef struct sr_heading_filter {
  double offset_deg;
  sr_vector3 bias_ut;
  sr_vector3 disturbance_ut;
  double covariance[SR_HEADING_FILTER_SIZE * SR_HEADING_FILTER_SIZE];
} sr_heading_filter;
typedef struct sr_heading_filter_options {
  double drift_deg2_per_s, bias_drift_ut2_per_s, disturbance_sigma_ut, disturbance_time_s,
      noise_sigma_ut;
} sr_heading_filter_options;

/* The terrain horizon (sunradar/terrain.hpp). */
typedef struct sr_elevation_grid {
  double north_latitude_deg, west_longitude_deg, latitude_step_deg, longitude_step_deg;
  int rows, columns;
  /* rows * columns heights in meters, row by row from the north; NaN is a sample without data.
     The grid of sr_read_elevation_geotiff() owns it: free it with sr_free_elevation_grid().
     An input of sr_calculate_horizon_profile() only borrows it. */
  float* heights_m;
} sr_elevation_grid;
typedef struct sr_elevation_tile {
  int latitude_deg, longitude_deg;
  double distance_m;
} sr_elevation_tile;
typedef struct sr_horizon_options {
  double azimuth_step_deg, max_distance_m, eye_height_m;
  bool use_grid_elevation;
  double observer_elevation_m, refraction_coefficient;
} sr_horizon_options;
/* The altitude and the distance have count values each. The result of
   sr_calculate_horizon_profile() owns them: free them with sr_free_horizon_profile(). An input only
   borrows them. */
typedef struct sr_horizon_profile {
  double azimuth_step_deg;
  double* altitude_deg;
  double* distance_m;
  size_t count;
  double observer_elevation_m;
} sr_horizon_profile;
typedef struct sr_terrain_event_options {
  int model;
  bool refraction;
  sr_atmosphere atmosphere;
  double limb_deg, search_step_s, tolerance_s;
  int body; /* 0: the sun, 1: the moon */
} sr_terrain_event_options;
typedef struct sr_terrain_events {
  int kind;
  sr_crossing* crossings; /* free with sr_free() */
  size_t crossing_count;
} sr_terrain_events;

/* The C++ defaults. */
sr_sun_options sr_default_sun_options(void);
sr_event_options sr_default_event_options(void);
sr_path_options sr_default_path_options(void);
sr_search_options sr_default_search_options(void);
sr_moon_options sr_default_moon_options(void);
sr_magnetometer_options sr_default_magnetometer_options(void);
sr_heading_estimate sr_default_heading_estimate(void);
sr_heading_filter_options sr_default_heading_filter_options(void);
sr_camera sr_default_camera(void);
sr_view sr_default_view(void);
sr_horizon_options sr_default_horizon_options(void);
sr_terrain_event_options sr_default_terrain_event_options(void);
double sr_sunrise_altitude_deg(void);
double sr_unknown_heading_variance_deg2(void);
double sr_default_heading_drift_deg2_per_s(void);

const char* sr_version(void);
const char* sr_last_error(void);
void sr_free(void* pointer);

sr_status sr_sun_position(double unix_utc, sr_observer observer, sr_sun_options options,
                          sr_horizontal* out);
sr_status sr_sun_events(sr_observer observer, double start_utc, double end_utc,
                        sr_event_options options, sr_day_events* out);
sr_status sr_sun_path(sr_observer observer, double start_utc, double end_utc,
                      sr_path_options options, sr_path_point** out, size_t* count);
sr_status sr_moon_position(double unix_utc, sr_observer observer, sr_moon_options options,
                           sr_horizontal* out);
sr_status sr_moon_illuminated_fraction(double unix_utc, double* out);
sr_status sr_magnetic_declination_deg(sr_observer observer, double unix_utc, double* out);
sr_status sr_camera_from_fov(double width, double height, double horizontal_fov_deg,
                             sr_camera* out);
sr_status sr_project(sr_view view, sr_horizontal direction, sr_projection* out);
sr_status sr_unproject(sr_view view, sr_screen_point point, sr_horizontal* out);
sr_status sr_project_line(sr_view view, const sr_horizontal* line, size_t count, sr_polylines* out);
sr_status sr_horizon_line(sr_view view, sr_polylines* out);
void sr_free_polylines(sr_polylines* lines);
sr_status sr_find_time(sr_observer observer, sr_horizontal direction, double start_utc,
                       double end_utc, sr_search_options options, sr_time_match* out);
sr_status sr_find_dates(sr_observer observer, sr_horizontal direction, double start_utc,
                        double end_utc, sr_search_options options, sr_time_match** out,
                        size_t* count);
sr_status sr_find_azimuth_times(sr_observer observer, double azimuth_deg, double start_utc,
                                double end_utc, sr_search_options options,
                                sr_azimuth_crossing** out, size_t* count);
sr_status sr_heading_predict(sr_heading_estimate estimate, double dt_s, double drift_deg2_per_s,
                             sr_heading_estimate* out);
sr_status sr_heading_update(sr_heading_estimate estimate, sr_heading_measurement measurement,
                            sr_heading_estimate* out);
sr_status sr_heading_from_magnetometer(sr_quaternion device_to_frame, sr_vector3 field_ut,
                                       sr_observer observer, double unix_utc,
                                       sr_magnetometer_options options,
                                       sr_heading_measurement* out);
sr_status sr_heading_from_direction(sr_view view, sr_screen_point point, double true_azimuth_deg,
                                    double sigma_deg, sr_heading_measurement* out);
sr_status sr_heading_filter_start(sr_vector3 bias_ut, double bias_sigma_ut,
                                  sr_heading_filter_options options, sr_heading_filter* out);
sr_status sr_heading_filter_predict(const sr_heading_filter* filter, double dt_s,
                                    sr_heading_filter_options options, sr_heading_filter* out);
sr_status sr_heading_filter_update(const sr_heading_filter* filter,
                                   sr_heading_measurement measurement, sr_heading_filter* out);
sr_status sr_heading_filter_update_magnetometer(const sr_heading_filter* filter,
                                                sr_quaternion device_to_frame, sr_vector3 field_ut,
                                                sr_observer observer, double unix_utc,
                                                sr_heading_filter_options options,
                                                sr_heading_filter* out);

/* Reads one image of a GeoTIFF of heights (level 0 is the full image, 1, 2, ... the overviews).
   On success, free *out with sr_free_elevation_grid(). */
sr_status sr_read_elevation_geotiff(const uint8_t* data, size_t size, int level,
                                    sr_elevation_grid* out);
void sr_free_elevation_grid(sr_elevation_grid* grid);
sr_status sr_elevation_tiles(sr_observer observer, double max_distance_m, sr_elevation_tile** out,
                             size_t* count);
/* The grids are only read. On success, free *out with sr_free_horizon_profile(). */
sr_status sr_calculate_horizon_profile(sr_observer observer, const sr_elevation_grid* tiles,
                                       size_t tile_count, sr_horizon_options options,
                                       sr_horizon_profile* out);
void sr_free_horizon_profile(sr_horizon_profile* profile);
sr_status sr_terrain_altitude_deg(const sr_horizon_profile* profile, double azimuth_deg,
                                  double* out);
sr_status sr_find_terrain_events(sr_observer observer, const sr_horizon_profile* profile,
                                 double start_utc, double end_utc, sr_terrain_event_options options,
                                 sr_terrain_events* out);

#ifdef __cplusplus
}
#endif

#endif /* SUNRADAR_C_H */
