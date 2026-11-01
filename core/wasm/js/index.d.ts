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

type Partial2<T> = { [K in keyof T]?: T[K] extends object ? Partial<T[K]> : T[K] };

export interface Sunradar {
  version(): string;
  readonly sunriseAltitudeDeg: number;
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
}

/** Loads the WebAssembly module. Options go to the Emscripten module (for example locateFile). */
export declare function load(moduleOptions?: object): Promise<Sunradar>;
