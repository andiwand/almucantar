// Orientation sources of the browser, as quaternions device frame -> East-North-Up (the same
// convention as the core and the Android rotation vector).

import type { Quaternion } from "@sunradar/core";

const DEG = Math.PI / 180;

function multiply(a: Quaternion, b: Quaternion): Quaternion {
  return {
    w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
    x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
    y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
  };
}

/**
 * The quaternion of a W3C DeviceOrientationEvent: the device frame is rotated by alpha about z,
 * then beta about x', then gamma about y'' (intrinsic Z-X'-Y''). The earth frame of the
 * specification is x east, y north, z up. alpha = 0 and beta = 90 is a device held upright with
 * the back camera to the north.
 */
export function fromDeviceOrientation(
  alphaDeg: number,
  betaDeg: number,
  gammaDeg: number,
): Quaternion {
  const qz = { w: Math.cos((alphaDeg * DEG) / 2), x: 0, y: 0, z: Math.sin((alphaDeg * DEG) / 2) };
  const qx = { w: Math.cos((betaDeg * DEG) / 2), x: Math.sin((betaDeg * DEG) / 2), y: 0, z: 0 };
  const qy = { w: Math.cos((gammaDeg * DEG) / 2), x: 0, y: Math.sin((gammaDeg * DEG) / 2), z: 0 };
  return multiply(multiply(qz, qx), qy);
}

type Axis = [number, number, number];

/** The axis `v` of the device in ENU. */
function rotate(q: Quaternion, [x, y, z]: Axis): Axis {
  const r = multiply(multiply(q, { w: 0, x, y, z }), { w: q.w, x: -q.x, y: -q.y, z: -q.z });
  return [r.x, r.y, r.z];
}

/**
 * Turns `q` about the up axis, so that the device has the azimuth of the compass. iOS gives the
 * heading separately (webkitCompassHeading), and its alpha has no fixed north. The heading is the
 * one of the top of the device (+y). If the top points steeply up or down, its azimuth is not
 * defined, so the back camera (-z) is the axis instead.
 */
export function withCompass(q: Quaternion, compassDeg: number): Quaternion {
  const top = rotate(q, [0, 1, 0]);
  const [x, y] = Math.abs(top[2]) < 0.7 ? top : rotate(q, [0, 0, -1]);
  const half = ((Math.atan2(x, y) / DEG - compassDeg) * DEG) / 2;
  return multiply({ w: Math.cos(half), x: 0, y: 0, z: Math.sin(half) }, q);
}

/** The quaternion of a rotation matrix with the columns x, y, and z (device axes in ENU). */
export function fromAxes(x: Axis, y: Axis, z: Axis): Quaternion {
  const [m00, m10, m20] = x;
  const [m01, m11, m21] = y;
  const [m02, m12, m22] = z;
  const trace = m00 + m11 + m22;
  if (trace > 0) {
    const s = 2 * Math.sqrt(trace + 1);
    return { w: s / 4, x: (m21 - m12) / s, y: (m02 - m20) / s, z: (m10 - m01) / s };
  }
  if (m00 > m11 && m00 > m22) {
    const s = 2 * Math.sqrt(1 + m00 - m11 - m22);
    return { w: (m21 - m12) / s, x: s / 4, y: (m01 + m10) / s, z: (m02 + m20) / s };
  }
  if (m11 > m22) {
    const s = 2 * Math.sqrt(1 + m11 - m00 - m22);
    return { w: (m02 - m20) / s, x: (m01 + m10) / s, y: s / 4, z: (m12 + m21) / s };
  }
  const s = 2 * Math.sqrt(1 + m22 - m00 - m11);
  return { w: (m10 - m01) / s, x: (m02 + m20) / s, y: (m12 + m21) / s, z: s / 4 };
}

/**
 * A device held upright in its natural orientation, with the back camera to the azimuth
 * `azimuthDeg` and pitched up by `pitchDeg`. The desktop uses it for the view that the user drags.
 */
export function upright(azimuthDeg: number, pitchDeg: number): Quaternion {
  const a = azimuthDeg * DEG;
  const p = pitchDeg * DEG;
  const forward: Axis = [Math.sin(a) * Math.cos(p), Math.cos(a) * Math.cos(p), Math.sin(p)];
  const right: Axis = [Math.cos(a), -Math.sin(a), 0];
  const up: Axis = [-Math.sin(a) * Math.sin(p), -Math.cos(a) * Math.sin(p), Math.cos(p)];
  return fromAxes(right, up, [-forward[0], -forward[1], -forward[2]]);
}

/** The screen rotation (0 to 3) from screen.orientation.angle: counterclockwise, in degrees. */
export function screenRotation(angleDeg: number): number {
  return ((Math.round(angleDeg / 90) % 4) + 4) % 4;
}
