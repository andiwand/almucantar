import { describe, expect, it } from "vitest";
import { fromDeviceOrientation, screenRotation, upright, withCompass } from "../src/orientation";

const close = (a: number, b: number) => expect(Math.abs(a - b)).toBeLessThan(1e-12);

describe("orientation", () => {
  it("alpha 0, beta 90 is a device upright with the back camera to the north", () => {
    // The same quaternion as the "north upright" pose in the core tests: +90° about east.
    const q = fromDeviceOrientation(0, 90, 0);
    close(q.w, Math.cos(Math.PI / 4));
    close(q.x, Math.sin(Math.PI / 4));
    close(q.y, 0);
    close(q.z, 0);
  });

  it("agrees with the upright pose of the desktop view", () => {
    // alpha turns counterclockwise seen from above, so alpha 90 looks to the west (270°).
    const a = fromDeviceOrientation(90, 90, 0);
    const b = upright(270, 0);
    // q and -q are the same rotation.
    const sign = Math.sign(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z);
    close(a.w, sign * b.w);
    close(a.x, sign * b.x);
    close(a.y, sign * b.y);
    close(a.z, sign * b.z);
  });

  it("maps screen.orientation.angle to the screen rotation", () => {
    expect(screenRotation(0)).toBe(0);
    expect(screenRotation(90)).toBe(1);
    expect(screenRotation(180)).toBe(2);
    expect(screenRotation(270)).toBe(3);
    expect(screenRotation(-90)).toBe(3);
  });

  it("turns an orientation without north to the azimuth of the compass", () => {
    const azimuth = (q: ReturnType<typeof fromDeviceOrientation>, upright: boolean) => {
      // The azimuth of the back camera (upright) or of the top of the device (flat).
      const v = upright ? [0, 0, -1] : [0, 1, 0];
      const m = (a: number[], b: number[]) => [
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
      ];
      const r = m(m([q.w, q.x, q.y, q.z], [0, ...v]), [q.w, -q.x, -q.y, -q.z]);
      return ((Math.atan2(r[1], r[2]) * 180) / Math.PI + 360) % 360;
    };
    // An upright, rolled device (gamma 20) and a tilted one; iOS alpha has an arbitrary zero (+37).
    for (const [alpha, beta, gamma, isUpright] of [
      [30, 90, 20, true],
      [200, 80, -15, true],
      [120, 20, 10, false],
    ] as const) {
      const truth = fromDeviceOrientation(alpha, beta, gamma);
      const q = withCompass(
        fromDeviceOrientation(alpha + 37, beta, gamma),
        azimuth(truth, isUpright),
      );
      const sign = Math.sign(q.w * truth.w + q.x * truth.x + q.y * truth.y + q.z * truth.z);
      for (const k of ["w", "x", "y", "z"] as const)
        expect(Math.abs(q[k] - sign * truth[k])).toBeLessThan(1e-9);
    }
  });
});
