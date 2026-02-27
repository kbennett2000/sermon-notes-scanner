package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OCRWhitelistFilterTest {

  @Test
  public void filterByWhitelist_removesNonWhitelistedChars() {
    String whitelist = "ABCabc123";
    assertEquals("Abc123", OCRWhitelist.filterByWhitelist("Abc123™©®", whitelist));
  }

  @Test
  public void filterByWhitelist_preservesWhitespace() {
    String whitelist = "ABCabcdef";
    assertEquals("Abc def", OCRWhitelist.filterByWhitelist("Abc def", whitelist));
    assertEquals("A\nB\tC", OCRWhitelist.filterByWhitelist("A\nB\tC", whitelist));
  }

  @Test
  public void filterByWhitelist_emptyWhitelistReturnsOriginal() {
    assertEquals("Hello™", OCRWhitelist.filterByWhitelist("Hello™", ""));
    assertEquals("Hello™", OCRWhitelist.filterByWhitelist("Hello™", null));
  }

  @Test
  public void filterByWhitelist_nullOrEmptyTextReturnsAsIs() {
    assertNull(OCRWhitelist.filterByWhitelist(null, "abc"));
    assertEquals("", OCRWhitelist.filterByWhitelist("", "abc"));
  }

  @Test
  public void filterByWhitelist_germanWhitelistKeepsUmlauts() {
    String text = "Über™ Straße© 42§";
    String filtered = OCRWhitelist.filterByWhitelist(text, OCRWhitelist.DE);
    assertEquals("Über Straße 42§", filtered);
  }

  @Test
  public void filterByWhitelist_englishWhitelistRemovesSpecialChars() {
    String text = "Hello© World™ 2024";
    String filtered = OCRWhitelist.filterByWhitelist(text, OCRWhitelist.EN);
    assertEquals("Hello World 2024", filtered);
  }
}
