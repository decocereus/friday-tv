package home.friday.wallpaper;

import java.nio.charset.StandardCharsets;

/**
 * A QR code, just enough of one: byte mode, error correction level M, versions 1 to 10. That
 * covers a Steam sign-in challenge URL with room to spare, and keeps the module free of
 * dependencies. Follows ISO/IEC 18004: codewords, Reed-Solomon blocks, the fixed patterns, the
 * zigzag placement, then the lowest-penalty of the eight masks.
 */
final class Qr {

    private static final int ECC_M = 0;
    /** Error-correction codewords per block, for level M, versions 1..6. */
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    /** Number of RS blocks, level M, versions 1..6. */
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    /** Total codewords (data + ecc) in the symbol, versions 1..6. */
    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172};
    /** Where alignment patterns are centred, versions 1..6. */
    private static final int[][] ALIGNMENT = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}};
    /** Version 6 holds 106 bytes, far more than a sign-in URL, and needs no version block. */
    private static final int MAX_VERSION = 6;

    private final int size;
    private final boolean[][] dark, fixed;

    private Qr(int version) {
        size = version * 4 + 17;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];
    }

    int size() {
        return size;
    }

    boolean dark(int x, int y) {
        return dark[y][x];
    }

    /** The smallest symbol that holds {@code text}, or null when it does not fit version 6. */
    static Qr encode(String text) {
        return encode(text, -1);
    }

    /** {@code mask} of -1 picks the lowest-penalty mask; 0..7 forces one, which the tests use. */
    static Qr encode(String text, int mask) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (bytes.length <= dataCodewords(v) - 2) {
                version = v;
                break;
            }
        }
        if (version == 0) return null;
        Qr qr = new Qr(version);
        qr.draw(version, codewords(version, bytes), mask);
        return qr;
    }

    private static int dataCodewords(int version) {
        return TOTAL_CODEWORDS[version] - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    // ---------------------------------------------------------------- bits

    /** Mode indicator, length, payload, terminator and padding, as one data codeword stream. */
    private static byte[] codewords(int version, byte[] bytes) {
        int capacity = dataCodewords(version);
        Bits bits = new Bits(capacity);
        bits.add(0b0100, 4);
        bits.add(bytes.length, 8);
        for (byte b : bytes) bits.add(b & 255, 8);
        bits.add(0, Math.min(4, capacity * 8 - bits.length));
        bits.add(0, (8 - bits.length % 8) % 8);
        for (int pad = 0xEC; bits.length < capacity * 8; pad ^= 0xEC ^ 0x11) bits.add(pad, 8);
        return interleave(version, bits.bytes);
    }

    /** Splits the data into RS blocks, appends each block's ECC, and interleaves both halves. */
    private static byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ecc = ECC_PER_BLOCK[version];
        int shortLength = dataCodewords(version) / blocks;
        int longBlocks = dataCodewords(version) % blocks;
        byte[][] chunks = new byte[blocks][], parity = new byte[blocks][];
        for (int b = 0, at = 0; b < blocks; b++) {
            int length = shortLength + (b >= blocks - longBlocks ? 1 : 0);
            chunks[b] = new byte[length];
            System.arraycopy(data, at, chunks[b], 0, length);
            at += length;
            parity[b] = reedSolomon(chunks[b], ecc);
        }
        byte[] out = new byte[TOTAL_CODEWORDS[version]];
        int at = 0;
        for (int i = 0; i <= shortLength; i++) {
            for (byte[] chunk : chunks) if (i < chunk.length) out[at++] = chunk[i];
        }
        for (int i = 0; i < ecc; i++) {
            for (byte[] block : parity) out[at++] = block[i];
        }
        return out;
    }

    private static final class Bits {
        final byte[] bytes;
        int length;

        Bits(int capacity) {
            bytes = new byte[capacity];
        }

        void add(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) bytes[length >> 3] |= (byte) (0x80 >>> (length & 7));
                length++;
            }
        }
    }

    // ---------------------------------------------------------------- GF(256)

    private static final int[] EXP = new int[512], LOG = new int[256];

    static {
        for (int i = 0, x = 1; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int multiply(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static byte[] reedSolomon(byte[] data, int count) {
        int[] generator = new int[count + 1];
        generator[0] = 1;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j > 0; j--) {
                generator[j] = generator[j - 1] ^ multiply(generator[j], EXP[i]);
            }
            generator[0] = multiply(generator[0], EXP[i]);
        }
        int[] remainder = new int[count];
        for (byte b : data) {
            int factor = (b & 255) ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, count - 1);
            remainder[count - 1] = 0;
            for (int i = 0; i < count; i++) remainder[i] ^= multiply(generator[count - 1 - i], factor);
        }
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) out[i] = (byte) remainder[i];
        return out;
    }

    // ---------------------------------------------------------------- the symbol

    private void draw(int version, byte[] codewords, int forced) {
        finder(0, 0);
        finder(size - 7, 0);
        finder(0, size - 7);
        timing();
        alignment(version);
        set(8, size - 8, true);          // the one module that is always dark
        fixed[size - 8][8] = true;
        reserveFormat();
        place(codewords);

        int best = forced, bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; forced < 0 && mask < 8; mask++) {
            applyMask(mask);
            format(mask);
            int penalty = penalty();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = mask;
            }
            applyMask(mask);
        }
        applyMask(best);
        format(best);
    }

    private void set(int x, int y, boolean on) {
        dark[y][x] = on;
        fixed[y][x] = true;
    }

    private void finder(int x0, int y0) {
        for (int y = -1; y <= 7; y++) {
            for (int x = -1; x <= 7; x++) {
                int px = x0 + x, py = y0 + y;
                if (px < 0 || py < 0 || px >= size || py >= size) continue;
                boolean ring = Math.max(Math.abs(x - 3), Math.abs(y - 3)) != 2
                        && x >= 0 && x <= 6 && y >= 0 && y <= 6;
                set(px, py, ring);
            }
        }
    }

    private void timing() {
        for (int i = 8; i < size - 8; i++) {
            set(i, 6, i % 2 == 0);
            set(6, i, i % 2 == 0);
        }
    }

    /** One 5x5 at each pair of centres, except the three that would sit on a finder pattern. */
    private void alignment(int version) {
        int[] centres = ALIGNMENT[version];
        if (centres.length == 0) return;
        int first = 0, last = centres.length - 1;
        for (int i = 0; i < centres.length; i++) {
            for (int j = 0; j < centres.length; j++) {
                if ((i == first && j == first) || (i == first && j == last)
                        || (i == last && j == first)) continue;
                int a = centres[i], b = centres[j];
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        set(a + x, b + y, Math.max(Math.abs(x), Math.abs(y)) != 1);
                    }
                }
            }
        }
    }

    private void reserveFormat() {
        for (int i = 0; i < 9; i++) {
            if (!fixed[8][i]) fixed[8][i] = true;
            if (!fixed[i][8]) fixed[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            fixed[8][size - 1 - i] = true;
            fixed[size - 1 - i][8] = true;
        }
    }

    /** The zigzag: two columns at a time from the right, skipping the timing column. */
    private void place(byte[] codewords) {
        int bit = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int step = 0; step < size; step++) {
                boolean upward = ((right + 1) & 2) == 0;
                int y = upward ? size - 1 - step : step;
                for (int c = 0; c < 2; c++) {
                    int x = right - c;
                    if (fixed[y][x]) continue;
                    boolean on = bit < codewords.length * 8
                            && ((codewords[bit >> 3] >>> (7 - (bit & 7))) & 1) != 0;
                    dark[y][x] = on;
                    bit++;
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (fixed[y][x] || !masked(mask, x, y)) continue;
                dark[y][x] = !dark[y][x];
            }
        }
    }

    private static boolean masked(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    /** The 15-bit format string: level and mask, BCH(15,5) coded, then XORed with the mask. */
    private void format(int mask) {
        int value = (ECC_M << 3 | mask) << 10;
        int rest = value;
        for (int i = 4; i >= 0; i--) {
            if (((rest >>> (10 + i)) & 1) != 0) rest ^= 0b10100110111 << i;
        }
        int bits = (value | rest) ^ 0b101010000010010;
        for (int i = 0; i <= 5; i++) dark[i][8] = ((bits >>> i) & 1) != 0;
        dark[7][8] = ((bits >>> 6) & 1) != 0;
        dark[8][8] = ((bits >>> 7) & 1) != 0;
        dark[8][7] = ((bits >>> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) dark[8][14 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 0; i < 8; i++) dark[8][size - 1 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 8; i < 15; i++) dark[size - 15 + i][8] = ((bits >>> i) & 1) != 0;
    }

    // ---------------------------------------------------------------- penalties

    int penalty() {
        return runs() + boxes() + finderLike() + balance();
    }

    private int runs() {
        int score = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean here = pass == 0 ? dark[a][b] : dark[b][a];
                    boolean last = pass == 0 ? dark[a][b - 1] : dark[b - 1][a];
                    if (here == last) {
                        run++;
                        if (run == 5) score += 3;
                        else if (run > 5) score++;
                    } else {
                        run = 1;
                    }
                }
            }
        }
        return score;
    }

    private int boxes() {
        int score = 0;
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = dark[y][x];
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) score += 3;
            }
        }
        return score;
    }

    /** The 1:1:3:1:1 run with four light modules beside it, which a reader reads as a finder. */
    private int finderLike() {
        int score = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                for (int b = 0; b + 10 < size; b++) {
                    int window = 0;
                    for (int i = 0; i < 11; i++) {
                        boolean on = pass == 0 ? dark[a][b + i] : dark[b + i][a];
                        window = window << 1 | (on ? 1 : 0);
                    }
                    if (window == 0b10111010000 || window == 0b00001011101) score += 40;
                }
            }
        }
        return score;
    }

    private int balance() {
        int on = 0;
        for (boolean[] row : dark) for (boolean b : row) if (b) on++;
        int percent = on * 100 / (size * size);
        return Math.abs(percent - 50) / 5 * 10;
    }
}
