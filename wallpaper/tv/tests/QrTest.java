package home.friday.wallpaper;

/**
 * The encoder against symbols from a reference implementation (python-qrcode, byte mode, level M),
 * module for module, with the mask forced so the comparison is of the parts the spec pins down:
 * the codewords, the Reed-Solomon parity, the fixed patterns, the placement and the format bits.
 * Mask choice is checked separately, as the lowest of the four penalty rules.
 *
 * <p>To regenerate a reference row: {@code pip install qrcode}, then
 * {@code QRCode(error_correction=ERROR_CORRECT_M); add_data(QRData(text, mode=MODE_8BIT_BYTE));
 * version = N; makeImpl(False, mask); get_matrix()}.
 */
public final class QrTest {

    private static int checks;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    /** Reference matrix, row-major, '1' for a dark module, no quiet zone. */
    private static void same(String text, int size, int mask, String expected) {
        Qr qr = Qr.encode(text, mask);
        check(qr != null, text + ": did not encode");
        check(qr.size() == size, text + ": size " + qr.size() + ", want " + size);
        StringBuilder got = new StringBuilder(size * size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) got.append(qr.dark(x, y) ? '1' : '0');
        }
        for (int y = 0; y < size; y++) {
            String want = expected.substring(y * size, y * size + size);
            String mine = got.substring(y * size, y * size + size);
            if (!want.equals(mine)) {
                throw new AssertionError(text + " mask " + mask + ": row " + y
                        + "\n want " + want + "\n  got " + mine);
            }
        }
        check(true, text + " mask " + mask);
    }

    /** encode() without a mask must land on the mask with the lowest penalty score. */
    private static void picksBestMask(String text) {
        int best = -1, bestScore = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            int score = Qr.encode(text, mask).penalty();
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
        }
        Qr chosen = Qr.encode(text), wanted = Qr.encode(text, best);
        for (int y = 0; y < chosen.size(); y++) {
            for (int x = 0; x < chosen.size(); x++) {
                check(chosen.dark(x, y) == wanted.dark(x, y),
                        text + ": encode() did not pick mask " + best);
            }
        }
    }

    public static void main(String[] args) {
        same("hello friday", 21, 0, SHORT_TEXT);
        same("https://s.team/q/1/1", 25, 4, SHORT_URL);
        same("https://s.team/q/1/13256497499741025039", 29, 7, STEAM_URL);
        picksBestMask("https://s.team/q/1/13256497499741025039");

        check(Qr.encode("") != null, "empty string still encodes");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('x');
        check(Qr.encode(big.toString()) == null, "too long for version 6 returns null");

        System.out.println(checks + " QR module, mask, size and overflow checks passed");
    }

    private static final String SHORT_TEXT =
            "111111100111001111111100000101100101000001101110100110001011101101110100010101011101"
            + "101110101111101011101100000100111001000001111111101010101111111000000000100000000000"
            + "101010100010100010010001000000011010010011011100100101000111111001100011101100110010"
            + "010001110001000110000000000001111011110111111111100110111110111100000100100000100010"
            + "101110101100000010010101110100000100110110101110101101101010101100000100100000010010"
            + "111111101000100100011";

    private static final String SHORT_URL =
            "111111101011110100111111110000010010011101010000011011101000001010001011101101110101"
            + "000110010101110110111010101010110010111011000001011110000101000001111111101010101010"
            + "111111100000000100000111000000001000101111110010111111001100000001001010110011101000"
            + "101011010010010011111000001110011001010011000110100001100111011101100111110011000101"
            + "010110100100100010111111011111000111100001000010001010101011011011100011110011011111"
            + "111000000000011100110100010000111111101110000010101000010000010001010101000111101011"
            + "101011010110111111111101110100110100101110011110111010001111111110010101000001001110"
            + "1010001111101111111011101101011000111";

    private static final String STEAM_URL =
            "111111100101000110011011111111000001000000001011000100000110111010000100000101001011"
            + "101101110100100000110011010111011011101000011101000010101110110000010110000110111001"
            + "000001111111101010101010101011111110000000000000010110100000000010010110101101000010"
            + "110100000011100011100001001100110010010000011100010110000100011111010000100010001010"
            + "001100100110000110100100001001100110010111101100110100100101101000000001000011100111"
            + "100110110111111001011000000100101101000010101111001010111100111010010001001011101111"
            + "001100010111101001100111100111101011000100000110011110010101011011000001001110011110"
            + "111000000010111110100000000001110101001101000101111111111000000000100010101001010000"
            + "010100100101001100011101101110100110100001101111100111011101010000100101110111111010"
            + "111010010100100111110111101100000100101110101111100100101111111011101111111111001001"
            + "0";
}
