#!/usr/bin/env python3
import sys
from PIL import Image


VERSION = 5
SIZE = 21 + 4 * (VERSION - 1)
DATA_CODEWORDS = 108
EC_CODEWORDS = 26


def gf_tables():
    exp = [0] * 512
    log = [0] * 256
    value = 1
    for i in range(255):
        exp[i] = value
        log[value] = i
        value <<= 1
        if value & 0x100:
            value ^= 0x11D
    for i in range(255, 512):
        exp[i] = exp[i - 255]
    return exp, log


GF_EXP, GF_LOG = gf_tables()


def gf_mul(a, b):
    if a == 0 or b == 0:
        return 0
    return GF_EXP[GF_LOG[a] + GF_LOG[b]]


def poly_mul(a, b):
    result = [0] * (len(a) + len(b) - 1)
    for i, av in enumerate(a):
        for j, bv in enumerate(b):
            result[i + j] ^= gf_mul(av, bv)
    return result


def rs_generator(degree):
    result = [1]
    for i in range(degree):
        result = poly_mul(result, [1, GF_EXP[i]])
    return result


def rs_remainder(data, degree):
    generator = rs_generator(degree)
    result = [0] * degree
    for value in data:
        factor = value ^ result.pop(0)
        result.append(0)
        for i in range(degree):
            result[i] ^= gf_mul(generator[i + 1], factor)
    return result


def bits_to_bytes(bits):
    values = []
    for i in range(0, len(bits), 8):
        value = 0
        for bit in bits[i:i + 8]:
            value = (value << 1) | bit
        values.append(value)
    return values


def make_data(text):
    payload = text.encode("utf-8")
    bits = []
    bits += [0, 1, 0, 0]
    bits += [(len(payload) >> i) & 1 for i in range(7, -1, -1)]
    for byte in payload:
        bits += [(byte >> i) & 1 for i in range(7, -1, -1)]
    max_bits = DATA_CODEWORDS * 8
    bits += [0] * min(4, max_bits - len(bits))
    while len(bits) % 8:
        bits.append(0)
    data = bits_to_bytes(bits)
    pads = [0xEC, 0x11]
    index = 0
    while len(data) < DATA_CODEWORDS:
        data.append(pads[index % 2])
        index += 1
    return data + rs_remainder(data, EC_CODEWORDS)


def blank_matrix():
    return [[None for _ in range(SIZE)] for _ in range(SIZE)], [[False for _ in range(SIZE)] for _ in range(SIZE)]


def set_module(matrix, reserved, x, y, value, reserve=True):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        matrix[y][x] = bool(value)
        if reserve:
            reserved[y][x] = True


def finder(matrix, reserved, x, y):
    for dy in range(-1, 8):
        for dx in range(-1, 8):
            xx = x + dx
            yy = y + dy
            if not (0 <= xx < SIZE and 0 <= yy < SIZE):
                continue
            on = 0 <= dx <= 6 and 0 <= dy <= 6 and (
                dx in (0, 6) or dy in (0, 6) or (2 <= dx <= 4 and 2 <= dy <= 4)
            )
            set_module(matrix, reserved, xx, yy, on)


def alignment(matrix, reserved, cx, cy):
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            on = max(abs(dx), abs(dy)) != 1
            set_module(matrix, reserved, cx + dx, cy + dy, on)


def draw_function_patterns(matrix, reserved):
    finder(matrix, reserved, 0, 0)
    finder(matrix, reserved, SIZE - 7, 0)
    finder(matrix, reserved, 0, SIZE - 7)
    alignment(matrix, reserved, 30, 30)
    for i in range(8, SIZE - 8):
        set_module(matrix, reserved, i, 6, i % 2 == 0)
        set_module(matrix, reserved, 6, i, i % 2 == 0)
    set_module(matrix, reserved, 8, 4 * VERSION + 9, True)
    for i in range(9):
        set_module(matrix, reserved, 8, i, False)
        set_module(matrix, reserved, i, 8, False)
    for i in range(8):
        set_module(matrix, reserved, SIZE - 1 - i, 8, False)
        set_module(matrix, reserved, 8, SIZE - 1 - i, False)


def mask_bit(mask, x, y):
    if mask == 0:
        return (x + y) % 2 == 0
    if mask == 1:
        return y % 2 == 0
    if mask == 2:
        return x % 3 == 0
    if mask == 3:
        return (x + y) % 3 == 0
    if mask == 4:
        return (x // 3 + y // 2) % 2 == 0
    if mask == 5:
        return (x * y) % 2 + (x * y) % 3 == 0
    if mask == 6:
        return ((x * y) % 2 + (x * y) % 3) % 2 == 0
    return ((x + y) % 2 + (x * y) % 3) % 2 == 0


def place_data(matrix, reserved, codewords, mask):
    bits = []
    for byte in codewords:
        bits += [(byte >> i) & 1 for i in range(7, -1, -1)]
    index = 0
    upward = True
    x = SIZE - 1
    while x > 0:
        if x == 6:
            x -= 1
        for offset in range(SIZE):
            y = SIZE - 1 - offset if upward else offset
            for xx in (x, x - 1):
                if reserved[y][xx]:
                    continue
                bit = bits[index] if index < len(bits) else 0
                if mask_bit(mask, xx, y):
                    bit ^= 1
                set_module(matrix, reserved, xx, y, bit, False)
                index += 1
        upward = not upward
        x -= 2


def format_bits(mask):
    value = (1 << 3) | mask
    data = value << 10
    generator = 0x537
    for i in range(14, 9, -1):
        if (data >> i) & 1:
            data ^= generator << (i - 10)
    return ((value << 10) | data) ^ 0x5412


def draw_format(matrix, reserved, mask):
    value = format_bits(mask)
    for i in range(6):
        set_module(matrix, reserved, 8, i, (value >> i) & 1)
    set_module(matrix, reserved, 8, 7, (value >> 6) & 1)
    set_module(matrix, reserved, 8, 8, (value >> 7) & 1)
    set_module(matrix, reserved, 7, 8, (value >> 8) & 1)
    for i in range(9, 15):
        set_module(matrix, reserved, 14 - i, 8, (value >> i) & 1)
    for i in range(8):
        set_module(matrix, reserved, SIZE - 1 - i, 8, (value >> i) & 1)
    for i in range(8, 15):
        set_module(matrix, reserved, 8, SIZE - 15 + i, (value >> i) & 1)


def penalty(matrix):
    total = 0
    for rows in (matrix, list(zip(*matrix))):
        for row in rows:
            run_color = row[0]
            run_len = 1
            for value in row[1:]:
                if value == run_color:
                    run_len += 1
                else:
                    if run_len >= 5:
                        total += 3 + run_len - 5
                    run_color = value
                    run_len = 1
            if run_len >= 5:
                total += 3 + run_len - 5
    for y in range(SIZE - 1):
        for x in range(SIZE - 1):
            c = matrix[y][x]
            if matrix[y][x + 1] == c and matrix[y + 1][x] == c and matrix[y + 1][x + 1] == c:
                total += 3
    dark = sum(1 for row in matrix for value in row if value)
    percent = dark * 100 // (SIZE * SIZE)
    total += abs(percent - 50) // 5 * 10
    return total


def make_qr(text):
    codewords = make_data(text)
    best = None
    for mask in range(8):
        matrix, reserved = blank_matrix()
        draw_function_patterns(matrix, reserved)
        place_data(matrix, reserved, codewords, mask)
        draw_format(matrix, reserved, mask)
        score = penalty(matrix)
        if best is None or score < best[0]:
            best = (score, matrix)
    return best[1]


def save_png(matrix, path):
    border = 4
    scale = 10
    size = (SIZE + border * 2) * scale
    image = Image.new("RGB", (size, size), "white")
    pixels = image.load()
    for y, row in enumerate(matrix):
        for x, value in enumerate(row):
            if value:
                for yy in range((y + border) * scale, (y + border + 1) * scale):
                    for xx in range((x + border) * scale, (x + border + 1) * scale):
                        pixels[xx, yy] = (0, 0, 0)
    image.save(path)


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_qr.py TEXT OUTPUT.png")
    save_png(make_qr(sys.argv[1]), sys.argv[2])


if __name__ == "__main__":
    main()
