// Types of @sunradar/core. All times are Unix time in seconds (UTC). All angles are in degrees.
// An invalid argument throws a RangeError.

export interface Observer { latitudeDeg: number; longitudeDeg: number }
export interface Horizontal { azimuthDeg: number; altitudeDeg: number }
export interface Atmosphere { pressureHpa: number; temperatureC: number }
export interface Quaternion { w: number; x: number; y: number; z: number }
export interface Vector3 { x: number; y: number; z: number }
export interface ScreenPoint { x: number; y: number }

export declare const SolarModel: { readonly noaa: 0; readonly astronomyEngine: 1 };
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
export interface MagnetometerOptions { sensorSigmaDeg: number }

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

export interface Sunradar {
  version(): string;
  readonly sunriseAltitudeDeg: number;
  /** The C++ defaults (frozen). */
  readonly defaults: {
    readonly sunOptions: Readonly<SunOptions>;
    readonly eventOptions: Readonly<EventOptions>;
    readonly pathOptions: Readonly<PathOptions>;
    readonly searchOptions: Readonly<SearchOptions>;
    readonly moonOptions: Readonly<MoonOptions>;
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
  moonIlluminatedFraction(unixUtc: number): number;
  magneticDeclinationDeg(observer: Observer, unixUtc: number): number;
  cameraFromFov(width: number, height: number, horizontalFovDeg: number): Camera;
  project(view: Partial2<View>, direction: Horizontal): Projection;
  unproject(view: Partial2<View>, point: ScreenPoint): Horizontal;
  projectLine(view: Partial2<View>, line: Horizontal[]): Polyline[];
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
export declare function load(moduleOptions?: object): Promise<Sunradar>;
