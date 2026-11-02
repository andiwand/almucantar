#!/usr/bin/env python3
"""Write small GeoTIFF files with known heights for the tests of the GeoTIFF reader.

The heights are h(row, column) = 1000 + 10 * row + column + 0.25, in a grid of 7 rows and 5
columns with a step of 0.5°. The tie point puts the corner of the image at 48° north, 11° east.

    python3 core/test/tools/make_geotiff.py

Files in core/test/data/geotiff/:

- float_tiles_deflate_p3.tif: float32, little endian, tiles of 4 x 4, DEFLATE, predictor 3,
  with an overview of 4 x 3. This is the format of Copernicus GLO-90.
- int16_strips_deflate_p2_be.tif: int16, big endian, strips of 3 rows, DEFLATE, predictor 2,
  PixelIsPoint, and the no-data value -32768 at row 2, column 3.
- float_strips_none.tif: float32, little endian, one strip, no compression.
"""

import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "data" / "geotiff"
ROWS, COLUMNS, STEP = 7, 5, 0.5


def height(row, column):
    return 1000 + 10 * row + column + 0.25


def float_predictor(samples, width, rows):
    """The floating point predictor (3): planes of big-endian bytes, then byte differences."""
    out = bytearray()
    for r in range(rows):
        row = samples[r * width : (r + 1) * width]
        raw = [struct.pack(">f", v) for v in row]
        planes = bytearray(raw[i][b] for b in range(4) for i in range(width))
        diff = bytearray(planes[:1])
        for i in range(1, len(planes)):
            diff.append((planes[i] - planes[i - 1]) & 0xFF)
        out += diff
    return bytes(out)


def int_predictor(samples, width, rows, fmt):
    out = bytearray()
    for r in range(rows):
        row = samples[r * width : (r + 1) * width]
        prev = 0
        for v in row:
            # The difference wraps around, as 16-bit integers do.
            out += struct.pack(fmt, ((v - prev + 32768) & 0xFFFF) - 32768)
            prev = v
    return bytes(out)


def chunk_samples(values, top, left, height_, width, fill):
    """The samples of a tile or a strip, padded with `fill` outside the image."""
    samples = []
    for r in range(top, top + height_):
        for c in range(left, left + width):
            inside = r < len(values) and c < len(values[0])
            samples.append(values[r][c] if inside else fill)
    return samples


class Writer:
    def __init__(self, big_endian):
        self.e = ">" if big_endian else "<"
        self.data = bytearray(b"MM" if big_endian else b"II")
        self.data += struct.pack(self.e + "HI", 42, 0)

    def append(self, blob):
        while len(self.data) % 2:
            self.data.append(0)
        offset = len(self.data)
        self.data += blob
        return offset

    def directory(self, entries):
        """entries: (tag, type, values). Returns the offset and the position of the next link."""
        sizes = {2: 1, 3: 2, 4: 4, 12: 8}
        codes = {2: "s", 3: "H", 4: "I", 12: "d"}
        blobs = []
        for tag, kind, values in sorted(entries):
            if kind == 2:
                blob = values.encode() + b"\0"
                count = len(blob)
            else:
                blob = struct.pack(self.e + codes[kind] * len(values), *values)
                count = len(values)
            blobs.append((tag, kind, count, blob))
        placed = []
        for tag, kind, count, blob in blobs:
            if len(blob) <= 4:
                placed.append((tag, kind, count, blob.ljust(4, b"\0")))
            else:
                placed.append(
                    (tag, kind, count, struct.pack(self.e + "I", self.append(blob)))
                )
        offset = self.append(struct.pack(self.e + "H", len(placed)))
        for tag, kind, count, field in placed:
            self.data += struct.pack(self.e + "HHI", tag, kind, count) + field
        link = len(self.data)
        self.data += struct.pack(self.e + "I", 0)
        return offset, link

    def link(self, position, offset):
        self.data[position : position + 4] = struct.pack(self.e + "I", offset)


def encode(writer, values, *, kind, tiled, chunk, compress, predictor, fill):
    """Writes the chunks of one image and returns the tags of the layout."""
    rows, columns = len(values), len(values[0])
    cw, ch = (chunk, chunk) if tiled else (columns, chunk)
    offsets, counts = [], []
    for top in range(0, rows, ch):
        for left in range(0, columns, cw):
            h = ch if tiled else min(ch, rows - top)
            samples = chunk_samples(values, top, left, h, cw, fill)
            if predictor == 3:
                blob = float_predictor(samples, cw, h)
            elif predictor == 2:
                blob = int_predictor(samples, cw, h, writer.e + "h")
            else:
                code = "f" if kind == "float" else "h"
                blob = struct.pack(writer.e + code * len(samples), *samples)
            if compress:
                blob = zlib.compress(blob)
            offsets.append(writer.append(blob))
            counts.append(len(blob))
            if not tiled:
                break
    bits, fmt = (32, 3) if kind == "float" else (16, 2)
    tags = [
        (256, 3, [columns]),
        (257, 3, [rows]),
        (258, 3, [bits]),
        (259, 3, [8 if compress else 1]),
        (262, 3, [1]),
        (277, 3, [1]),
        (284, 3, [1]),
        (317, 3, [predictor]),
        (339, 3, [fmt]),
    ]
    if tiled:
        tags += [(322, 3, [cw]), (323, 3, [ch]), (324, 4, offsets), (325, 4, counts)]
    else:
        tags += [(278, 3, [ch]), (273, 4, offsets), (279, 4, counts)]
    return tags


def geo_tags(pixel_is_point):
    raster = 2 if pixel_is_point else 1
    tie_x, tie_y = (11 + STEP / 2, 48 - STEP / 2) if pixel_is_point else (11, 48)
    return [
        (33550, 12, [STEP, STEP, 0.0]),
        (33922, 12, [0.0, 0.0, 0.0, tie_x, tie_y, 0.0]),
        (34735, 3, [1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, raster, 2048, 0, 1, 4326]),
    ]


def write(
    name,
    *,
    big_endian=False,
    kind="float",
    tiled,
    chunk,
    compress,
    predictor,
    pixel_is_point=False,
    no_data=None,
    overview=False
):
    values = [[height(r, c) for c in range(COLUMNS)] for r in range(ROWS)]
    if kind != "float":
        values = [[int(v) for v in row] for row in values]
    if no_data is not None:
        values[2][3] = no_data
    fill = 0 if kind != "float" else 0.0
    w = Writer(big_endian)
    tags = encode(
        w,
        values,
        kind=kind,
        tiled=tiled,
        chunk=chunk,
        compress=compress,
        predictor=predictor,
        fill=fill,
    )
    tags += geo_tags(pixel_is_point)
    if no_data is not None:
        tags.append((42113, 2, str(no_data)))
    first, link = w.directory(tags)
    w.link(4, first)
    if overview:
        # Every second row and column, as an image of 4 x 3.
        small = [row[::2] for row in values[::2]]
        tags = encode(
            w,
            small,
            kind=kind,
            tiled=tiled,
            chunk=chunk,
            compress=compress,
            predictor=predictor,
            fill=fill,
        )
        tags.append((254, 4, [1]))
        second, _ = w.directory(tags)
        w.link(link, second)
    (OUT / name).write_bytes(bytes(w.data))
    print("wrote", OUT / name)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    write(
        "float_tiles_deflate_p3.tif",
        tiled=True,
        chunk=4,
        compress=True,
        predictor=3,
        overview=True,
    )
    write(
        "int16_strips_deflate_p2_be.tif",
        big_endian=True,
        kind="int16",
        tiled=False,
        chunk=3,
        compress=True,
        predictor=2,
        pixel_is_point=True,
        no_data=-32768,
    )
    write("float_strips_none.tif", tiled=False, chunk=ROWS, compress=False, predictor=1)
