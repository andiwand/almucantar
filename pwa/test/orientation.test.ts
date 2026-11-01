import { describe, expect, it } from "vitest";
import { fromDeviceOrientation, screenRotation, upright } from "../src/orientation";

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
});
