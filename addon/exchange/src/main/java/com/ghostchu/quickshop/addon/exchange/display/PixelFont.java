package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Map;

/** Tiny 3x5 ASCII font for map charts without AWT. */
final class PixelFont {
  private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
      glyph('0', "111", "101", "101", "101", "111"),
      glyph('1', "010", "110", "010", "010", "111"),
      glyph('2', "111", "001", "111", "100", "111"),
      glyph('3', "111", "001", "111", "001", "111"),
      glyph('4', "101", "101", "111", "001", "001"),
      glyph('5', "111", "100", "111", "001", "111"),
      glyph('6', "111", "100", "111", "101", "111"),
      glyph('7', "111", "001", "010", "010", "010"),
      glyph('8', "111", "101", "111", "101", "111"),
      glyph('9', "111", "101", "111", "001", "111"),
      glyph('.', "000", "000", "000", "000", "010"),
      glyph('-', "000", "000", "111", "000", "000"),
      glyph('+', "000", "010", "111", "010", "000"),
      glyph('%', "101", "001", "010", "100", "101"),
      glyph(':', "000", "010", "000", "010", "000"),
      glyph('/', "001", "001", "010", "100", "100"),
      glyph('H', "101", "101", "111", "101", "101"),
      glyph('K', "101", "110", "100", "110", "101"),
      glyph('L', "100", "100", "100", "100", "111"),
      glyph('I', "111", "010", "010", "010", "111"),
      glyph('N', "101", "111", "111", "111", "101"),
      glyph('E', "111", "100", "110", "100", "111"),
      glyph('D', "110", "101", "101", "101", "110"),
      glyph('A', "010", "101", "111", "101", "101"),
      glyph('Y', "101", "101", "010", "010", "010"),
      glyph('M', "101", "111", "111", "101", "101"),
      glyph('R', "110", "101", "110", "101", "101"),
      glyph('T', "111", "010", "010", "010", "010"));

  private PixelFont() {}

  static void draw(Surface surface, String text, int x, int y, byte color) {
    int cursor = x;
    for (char raw : text.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
      String[] rows = GLYPHS.get(raw);
      if (rows != null) {
        for (int row = 0; row < rows.length; row++) {
          for (int column = 0; column < 3; column++) {
            if (rows[row].charAt(column) == '1') {
              surface.set(cursor + column, y + row, color);
            }
          }
        }
      }
      cursor += 4;
    }
  }

  static int width(String text) {
    return Math.max(0, text.length() * 4 - 1);
  }

  private static Map.Entry<Character, String[]> glyph(char value, String... rows) {
    return Map.entry(value, rows);
  }

  @FunctionalInterface
  interface Surface {
    void set(int x, int y, byte color);
  }
}
