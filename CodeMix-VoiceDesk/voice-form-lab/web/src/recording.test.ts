import { test, expect } from "vitest";
import { encodeWav } from "./recording";

test("recording encoder creates decodable mono PCM WAV with clipping and correct sizes", async () => {
  const blob = encodeWav(new Float32Array([-2, -1, 0, 1, 2]));
  const data = await new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = reject;
    reader.readAsArrayBuffer(blob);
  });
  const view = new DataView(data);
  expect(blob.type).toBe("audio/wav");
  expect(data.byteLength).toBe(54);
  expect(String.fromCharCode(...new Uint8Array(data, 0, 4))).toBe("RIFF");
  expect(view.getUint32(4, true)).toBe(46);
  expect(view.getUint16(22, true)).toBe(1);
  expect(view.getUint32(24, true)).toBe(16000);
  expect(view.getUint16(34, true)).toBe(16);
  expect(view.getUint32(40, true)).toBe(10);
  expect([0, 1, 2, 3, 4].map((i) => view.getInt16(44 + 2 * i, true))).toEqual([
    -32768, -32768, 0, 32767, 32767,
  ]);
});
