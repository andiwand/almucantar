/* A test of the C API, in C, so the header is checked as C. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sunradar_c.h"

static int failures = 0;
#define CHECK(condition)                                              \
  do {                                                                \
    if (!(condition)) {                                               \
      fprintf(stderr, "%s:%d: CHECK(%s)\n", __FILE__, __LINE__, #condition); \
      ++failures;                                                     \
    }                                                                 \
  } while (0)

int main(void) {
  const sr_observer vienna = {48.2082, 16.3738};
  const double day_start = 1781992800; /* 2026-06-21T00:00 CEST */

  sr_horizontal sun;
  CHECK(sr_sun_position(1782039420, vienna, sr_default_sun_options(), &sun) == SR_OK);
  CHECK(fabs(sun.azimuth_deg - 180) < 1 && fabs(sun.altitude_deg - 65.25) < 0.3);

  sr_day_events events = {0};
  CHECK(sr_sun_events(vienna, day_start, day_start + 86400, sr_default_event_options(), &events) == SR_OK);
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
  view.device_to_enu = (sr_quaternion){cos(M_PI / 4), sin(M_PI / 4), 0, 0}; /* upright, to the north */
  sr_polylines horizon = {0};
  CHECK(sr_horizon_line(view, &horizon) == SR_OK);
  CHECK(horizon.line_count == 1 && fabs(horizon.points[0].y - 500) < 1e-6);
  sr_free_polylines(&horizon);

  sr_azimuth_crossing* crossings = NULL;
  CHECK(sr_find_azimuth_times(vienna, sun.azimuth_deg, day_start, day_start + 86400, sr_default_search_options(),
                              &crossings, &count) == SR_OK);
  CHECK(count == 1);
  sr_free(crossings);

  /* An invalid argument gives a status and a message, not an exception. */
  const sr_observer invalid = {91, 0};
  CHECK(sr_sun_position(0, invalid, sr_default_sun_options(), &sun) == SR_INVALID_ARGUMENT);
  CHECK(strstr(sr_last_error(), "latitude_deg") != NULL);
  CHECK(sr_sun_position(0, vienna, sr_default_sun_options(), NULL) == SR_INVALID_ARGUMENT);

  CHECK(strlen(sr_version()) >= 5);
  if (failures == 0) printf("all C API checks passed\n");
  return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
