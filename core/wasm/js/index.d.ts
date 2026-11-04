// Types of @almucantar/core. All times are Unix time in seconds (UTC). All angles are in degrees.
// An invalid argument throws a RangeError.

export interface Observer { latitudeDeg: number; longitudeDeg: number }
export interface Horizontal { azimuthDeg: number; altitudeDeg: number }
export interface Atmosphere { pressureHpa: number; temperatureC: number }
export interface Quaternion { w: number; x: number; y: number; z: number }
export interface Vector3 { x: number; y: number; z: number }
export interface ScreenPoint { x: number; y: number }

export declare const SolarModel: { readonly noaa: 0; readonly astronomyEngine: 1 };
/** The bodies of terrainEvents(), bodyPosition(), findAlignments(), and alignmentLine(). */
export declare const Body: {
  readonly sun: 0; readonly moon: 1; readonly galacticCenter: 2; readonly mercury: 3; readonly venus: 4;
  readonly mars: 5; readonly jupiter: 6; readonly saturn: 7; readonly uranus: 8; readonly neptune: 9;
};
export declare const DayKind: { readonly normal: 0; readonly polarDay: 1; readonly polarNight: 2 };
export declare const ScreenRotation: {
  readonly rotation0: 0; readonly rotation90: 1; readonly rotation180: 2; readonly rotation270: 3;
};
export declare const UNKNOWN_HEADING_VARIANCE_DEG2: number;

export interface SunOptions { model: number; refraction: boolean; atmosphere: Atmosphere }
export interface EventOptions { model: number; altitudeDeg: number; searchStepS: number; toleranceS: number }
export interface PathOptions { model: number; stepS: number; refraction: boolean; atmosphere: Atmosphere }
export interface SearchOptions {
  model: number; stepS: number; toleranceS: number; refraction: boolean; atmosphere: Atmosphere;
  maxSeparationDeg: number;
}
export interface MoonOptions { refraction: boolean; atmosphere: Atmosphere }
export interface SkyOptions { refraction: boolean; atmosphere: Atmosphere }

/** The planets of planetPosition() and planetMagnitude(). */
export declare const Planet: Readonly<{
  mercury: 0;
  venus: 1;
  mars: 2;
  jupiter: 3;
  saturn: 4;
  uranus: 5;
  neptune: 6;
}>;
export type Planet = (typeof Planet)[keyof typeof Planet];

/** The galactic coordinates of the center of the Milky Way (Sagittarius A*). */
export declare const GALACTIC_CENTER: Readonly<{ longitudeDeg: number; latitudeDeg: number }>;

/**
 * A catalogue of stars as columns of the same length: J2000 positions at the epoch 2000.0, and the
 * proper motions (pmRaArcsecPerYear is d(ra)/dt * cos(dec)). The proper motions are optional.
 */
export interface StarCatalog {
  raDeg: ArrayLike<number>;
  decDeg: ArrayLike<number>;
  pmRaArcsecPerYear?: ArrayLike<number>;
  pmDecArcsecPerYear?: ArrayLike<number>;
}
export interface MagnetometerOptions { sensorSigmaDeg: number }

/**
 * A DEM as a grid of heights. The sample in row r and column c is at the latitude
 * northLatitudeDeg - r * latitudeStepDeg and the longitude westLongitudeDeg + c * longitudeStepDeg.
 * heightsM has rows * columns values, row by row; NaN is a sample without data.
 */
export interface ElevationGrid {
  northLatitudeDeg: number; westLongitudeDeg: number; latitudeStepDeg: number; longitudeStepDeg: number;
  rows: number; columns: number; heightsM: Float32Array | number[];
}
/** A tile of 1° by 1°, named by its south-west corner (Copernicus N47_00_E011_00 is 47, 11). */
export interface ElevationTile { latitudeDeg: number; longitudeDeg: number; distanceM: number }
/** A tile of the Web Mercator scheme of web maps: x from west to east, y from north to south. */
export interface MapTile { zoom: number; x: number; y: number; distanceM: number }
export interface HorizonOptions {
  azimuthStepDeg: number; maxDistanceM: number; eyeHeightM: number; useGridElevation: boolean;
  observerElevationM: number; refractionCoefficient: number;
  /** The terrain nearer than this is not used: 0 for a bare-earth model, about 2 km for a surface model with buildings. */
  minDistanceM: number;
}
/** The terrain horizon: the entry i is for the azimuth i * azimuthStepDeg. NaN without data. */
export interface HorizonProfile {
  azimuthStepDeg: number; altitudeDeg: Float64Array | number[]; distanceM: Float64Array | number[];
  observerElevationM: number;
}
export interface TerrainEventOptions {
  /** A value of Body; Body.sun is the default. */
  body: number;
  model: number; refraction: boolean; atmosphere: Atmosphere; limbDeg: number; searchStepS: number;
  toleranceS: number;
}
/** The times when the body appears (rising) or goes away behind the terrain. */
export interface TerrainEvents { kind: number; crossings: Crossing[] }

/** The planner (shotradar): a body of the sky behind a landmark. */
export interface BodyOptions { model: number; refraction: boolean; atmosphere: Atmosphere }
/** A point with its height above sea level: the ground, an eye, or the top of a landmark. */
export interface Site { latitudeDeg: number; longitudeDeg: number; elevationM: number }
export interface SightOptions { refractionCoefficient: number }
export interface Sight { direction: Horizontal; distanceM: number }
export interface AlignmentOptions { body: BodyOptions; maxSeparationDeg: number; toleranceS: number }
export interface Alignment { unixUtc: number; separationDeg: number; body: Horizontal }
export interface AlignmentLineOptions {
  body: BodyOptions; minDistanceM: number; maxDistanceM: number; stepM: number; eyeHeightM: number;
  refractionCoefficient: number;
}
export interface LinePoint {
  place: Observer; distanceM: number;
  /** The elevation of the eye at which the landmark is in front of the body. */
  eyeElevationM: number;
  /** NaN without tiles. */
  groundElevationM: number;
  body: Horizontal;
}
export interface AlignmentLine { points: LinePoint[]; spots: LinePoint[] }
export interface LineOfSight { visible: boolean; clearanceDeg: number; blockingDistanceM: number; blocking: Observer }
export interface DarkOptions {
  maxSunAltitudeDeg: number; maxMoonAltitudeDeg: number; maxMoonFraction: number; galacticCenter: boolean;
  minGalacticCenterAltitudeDeg: number; stepS: number; toleranceS: number;
}
export interface TimeWindow { startUtc: number; endUtc: number }

export interface Camera {
  width: number; height: number; fx: number; fy: number; cx: number; cy: number;
  k1: number; k2: number; k3: number; p1: number; p2: number;
}
export interface View { deviceToEnu: Quaternion; screenRotation: number; headingOffsetDeg: number; camera: Camera }

export interface Crossing { unixUtc: number; rising: boolean }
export interface DayEvents { kind: number; crossings: Crossing[]; culminationUtc: number; culminationAltitudeDeg: number }
export interface PathPoint { unixUtc: number; position: Horizontal }
export interface Projection { valid: boolean; point: ScreenPoint }
export type Polyline = ScreenPoint[];
export interface TimeMatch { unixUtc: number; separationDeg: number; sun: Horizontal }
export interface AzimuthCrossing { unixUtc: number; sun: Horizontal }
export interface HeadingEstimate { offsetDeg: number; varianceDeg2: number }
export interface HeadingMeasurement { offsetDeg: number; varianceDeg2: number }
/** The noise model of a HeadingFilter. */
export interface HeadingFilterOptions {
  driftDeg2PerS: number; biasDriftUt2PerS: number; disturbanceSigmaUt: number;
  disturbanceTimeS: number; noiseSigmaUt: number;
}
/**
 * The heading filter with the magnetometer: the offset, the hard-iron bias (device frame), and
 * the local disturbance (East-North-Up), with the covariance row by row in that order. The
 * first value of the covariance is the variance of the offset.
 */
export interface HeadingFilter { offsetDeg: number; biasUt: Vector3; disturbanceUt: Vector3; covariance: number[] }

type Partial2<T> = { [K in keyof T]?: T[K] extends object ? Partial<T[K]> : T[K] };

export interface Almucantar {
  version(): string;
  readonly sunriseAltitudeDeg: number;
  /** The C++ defaults (frozen). */
  readonly defaults: {
    readonly sunOptions: Readonly<SunOptions>;
    readonly eventOptions: Readonly<EventOptions>;
    readonly pathOptions: Readonly<PathOptions>;
    readonly searchOptions: Readonly<SearchOptions>;
    readonly moonOptions: Readonly<MoonOptions>;
    readonly skyOptions: Readonly<SkyOptions>;
    readonly horizonOptions: Readonly<HorizonOptions>;
    readonly terrainEventOptions: Readonly<TerrainEventOptions>;
    readonly bodyOptions: Readonly<BodyOptions>;
    readonly sightOptions: Readonly<SightOptions>;
    readonly alignmentOptions: Readonly<AlignmentOptions>;
    readonly alignmentLineOptions: Readonly<AlignmentLineOptions>;
    readonly darkOptions: Readonly<DarkOptions>;
    readonly magnetometerOptions: Readonly<MagnetometerOptions>;
    readonly headingEstimate: Readonly<HeadingEstimate>;
    readonly camera: Readonly<Camera>;
    readonly view: Readonly<View>;
    readonly sunriseAltitudeDeg: number;
    readonly headingDriftDeg2PerS: number;
    readonly headingFilterOptions: Readonly<HeadingFilterOptions>;
    readonly headingFilterSize: number;
  };
  sunPosition(unixUtc: number, observer: Observer, options?: Partial2<SunOptions>): Horizontal;
  sunEvents(observer: Observer, startUtc: number, endUtc: number, options?: Partial2<EventOptions>): DayEvents;
  sunPath(observer: Observer, startUtc: number, endUtc: number, options?: Partial2<PathOptions>): PathPoint[];
  moonPosition(unixUtc: number, observer: Observer, options?: Partial2<MoonOptions>): Horizontal;
  /** The apparent topocentric position of the center of a planet. */
  planetPosition(planet: Planet, unixUtc: number, observer: Observer, options?: Partial2<SkyOptions>): Horizontal;
  /** The visual magnitude of a planet, as seen from the center of the Earth. Smaller is brighter. */
  planetMagnitude(planet: Planet, unixUtc: number): number;
  /** The position of a direction in the galactic system, for example GALACTIC_CENTER. */
  galacticPosition(
    longitudeDeg: number,
    latitudeDeg: number,
    unixUtc: number,
    observer: Observer,
    options?: Partial2<SkyOptions>,
  ): Horizontal;
  /** The center line of the Milky Way: the point i is at the galactic longitude i * stepDeg. */
  galacticPlane(unixUtc: number, observer: Observer, stepDeg?: number, options?: Partial2<SkyOptions>): Horizontal[];
  /**
   * The apparent positions of all stars of the catalogue, as a flat array [azimuthDeg0,
   * altitudeDeg0, azimuthDeg1, ...]: one call per second for all stars, then projectPoints().
   */
  starPositions(catalog: StarCatalog, unixUtc: number, observer: Observer, options?: Partial2<SkyOptions>): Float64Array;
  moonIlluminatedFraction(unixUtc: number): number;
  /** Reads a GeoTIFF of heights, for example a Copernicus tile. level 1, 2, ... are the overviews. */
  readElevationGeotiff(data: Uint8Array, level?: number): ElevationGrid & { heightsM: Float32Array };
  /**
   * The bytes from the start of a GeoTIFF that readElevationGeotiff() needs for a level, from the
   * first part of the file (for an HTTP Range request). 0 if the header is too short to tell.
   */
  elevationGeotiffPrefixSize(header: Uint8Array, level: number): number;
  /** The tiles of 1° that a horizon profile with maxDistanceM needs, the nearest first. */
  elevationTiles(observer: Observer, maxDistanceM: number): ElevationTile[];
  /** The Web Mercator tiles of zoom (0 to 12) that a horizon profile with maxDistanceM needs, the nearest first. */
  mapTiles(observer: Observer, maxDistanceM: number, zoom: number): MapTile[];
  /**
   * Converts the RGBA pixels of a Terrarium elevation tile (ImageData.data of a canvas) to a grid
   * with equal steps in latitude.
   */
  terrariumGrid(rgba: Uint8Array | Uint8ClampedArray, width: number, height: number, tile: Pick<MapTile, "zoom" | "x" | "y">): ElevationGrid & { heightsM: Float32Array };
  horizonProfile(observer: Observer, tiles: ElevationGrid[], options?: Partial<HorizonOptions>): HorizonProfile;
  terrainAltitudeDeg(profile: HorizonProfile, azimuthDeg: number): number;
  terrainEvents(observer: Observer, profile: HorizonProfile, startUtc: number, endUtc: number, options?: Partial2<TerrainEventOptions>): TerrainEvents;
  /** The apparent position of any body (a value of Body). */
  bodyPosition(body: number, unixUtc: number, observer: Observer, options?: Partial2<BodyOptions>): Horizontal;
  /** The direction in which one site sees another, with the curvature of the Earth and the refraction. */
  sight(from: Site, to: Site, options?: Partial<SightOptions>): Sight;
  /** "I stand here": the times when the body is in the direction, for example of a landmark. */
  findAlignments(body: number, observer: Observer, direction: Horizontal, startUtc: number, endUtc: number, options?: Partial2<AlignmentOptions>): Alignment[];
  /** "Where must I stand?": the places from which the body is behind the target at the time. */
  alignmentLine(body: number, target: Site, unixUtc: number, tiles?: ElevationGrid[], options?: Partial2<AlignmentLineOptions>): AlignmentLine;
  /** Whether the eye at from sees to over the terrain of the tiles. */
  lineOfSight(from: Site, to: Site, tiles: ElevationGrid[], options?: Partial<SightOptions>): LineOfSight;
  /** The windows when the sky is dark; the moon and the galactic center over the terrain of the profile. */
  darkWindows(observer: Observer, startUtc: number, endUtc: number, options?: Partial<DarkOptions>, profile?: HorizonProfile | null): TimeWindow[];
  magneticDeclinationDeg(observer: Observer, unixUtc: number): number;
  cameraFromFov(width: number, height: number, horizontalFovDeg: number): Camera;
  project(view: Partial2<View>, direction: Horizontal): Projection;
  unproject(view: Partial2<View>, point: ScreenPoint): Horizontal;
  projectLine(view: Partial2<View>, line: Horizontal[]): Polyline[];
  /**
   * Projects many directions, given as a flat array [azimuthDeg0, altitudeDeg0, ...] (for example
   * the result of starPositions). Returns a flat array [x0, y0, x1, y1, ...] with NaN where a
   * direction is not valid (behind the camera or outside the valid cone).
   */
  projectPoints(view: Partial2<View>, directions: ArrayLike<number>): Float64Array;
  projectPath(view: Partial2<View>, path: PathPoint[]): Polyline[];
  horizonLine(view: Partial2<View>): Polyline[];
  findTime(observer: Observer, direction: Horizontal, startUtc: number, endUtc: number, options?: Partial2<SearchOptions>): TimeMatch;
  findDates(observer: Observer, direction: Horizontal, startUtc: number, endUtc: number, options?: Partial2<SearchOptions>): TimeMatch[];
  findAzimuthTimes(observer: Observer, azimuthDeg: number, startUtc: number, endUtc: number, options?: Partial2<SearchOptions>): AzimuthCrossing[];
  headingPredict(estimate: HeadingEstimate, dtS: number, driftDeg2PerS?: number): HeadingEstimate;
  headingUpdate(estimate: HeadingEstimate, measurement: HeadingMeasurement): HeadingEstimate;
  headingFromMagnetometer(deviceToFrame: Quaternion, fieldUt: Vector3, observer: Observer, unixUtc: number, options?: Partial<MagnetometerOptions>): HeadingMeasurement;
  headingFromDirection(view: Partial2<View>, point: ScreenPoint, trueAzimuthDeg: number, sigmaDeg: number): HeadingMeasurement;
  headingFilterStart(biasUt: Vector3, biasSigmaUt: number, options?: Partial<HeadingFilterOptions>): HeadingFilter;
  headingFilterPredict(filter: HeadingFilter, dtS: number, options?: Partial<HeadingFilterOptions>): HeadingFilter;
  headingFilterUpdate(filter: HeadingFilter, measurement: HeadingMeasurement): HeadingFilter;
  headingFilterUpdateMagnetometer(filter: HeadingFilter, deviceToFrame: Quaternion, fieldUt: Vector3, observer: Observer, unixUtc: number, options?: Partial<HeadingFilterOptions>): HeadingFilter;
}

/** Loads the WebAssembly module. Options go to the Emscripten module (for example locateFile). */
export declare function load(moduleOptions?: object): Promise<Almucantar>;
