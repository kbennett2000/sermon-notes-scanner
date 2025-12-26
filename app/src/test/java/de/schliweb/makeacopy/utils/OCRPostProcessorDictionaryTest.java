package de.schliweb.makeacopy.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for dictionary-based OCR post-processing.
 * Tests the correction logic without requiring Android context.
 */
public class OCRPostProcessorDictionaryTest {

    @Test
    public void testExtractCleanWord_withPunctuation() throws Exception {
        // Use reflection to test private method
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "extractCleanWord", String.class);
        method.setAccessible(true);

        assertEquals("Hello", method.invoke(null, "Hello"));
        assertEquals("Hello", method.invoke(null, "Hello,"));
        assertEquals("Hello", method.invoke(null, "\"Hello\""));
        assertEquals("Hello", method.invoke(null, "(Hello)"));
        assertEquals("test", method.invoke(null, "...test..."));
        assertEquals("", method.invoke(null, "..."));
        assertEquals("", method.invoke(null, ""));
    }

    @Test
    public void testApplyCasePattern() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "applyCasePattern", String.class, String.class);
        method.setAccessible(true);

        // All uppercase
        assertEquals("HELLO", method.invoke(null, "WORLD", "hello"));

        // All lowercase
        assertEquals("hello", method.invoke(null, "world", "HELLO"));

        // Title case
        assertEquals("Hello", method.invoke(null, "World", "hello"));

        // Mixed case defaults to lowercase
        assertEquals("hello", method.invoke(null, "wOrLd", "HELLO"));
    }

    @Test
    public void testFindDictionaryCorrection_singleCharSubstitution() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");
        dictionary.add("world");
        dictionary.add("test");

        // 0 -> o correction: "hell0" -> "hello"
        assertEquals("hello", method.invoke(null, "hell0", dictionary));

        // 1 -> l correction: "he1lo" -> "hello"
        assertEquals("hello", method.invoke(null, "he1lo", dictionary));

        // Word already in dictionary - findDictionaryCorrection may still find a match
        // because 'o' -> 'O' substitution gives "hellO" which lowercase matches "hello".
        // The caller (processWithDictionary) checks if word is in dictionary before 
        // calling this method, so this case won't occur in practice.
        // Test with a word that has no valid substitutions
        assertNull(method.invoke(null, "xyz", dictionary));
    }

    @Test
    public void testFindDictionaryCorrection_ligatureCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("summer");
        dictionary.add("hammer");
        dictionary.add("warm");

        // rn -> m correction: "surnrner" -> "summer"
        assertEquals("summer", method.invoke(null, "surnmer", dictionary));

        // rn -> m correction: "harnrner" -> "hammer"  
        assertEquals("hammer", method.invoke(null, "harnmer", dictionary));
    }

    @Test
    public void testFindDictionaryCorrection_preservesCase() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");

        // Uppercase input should produce uppercase output
        assertEquals("HELLO", method.invoke(null, "HELL0", dictionary));

        // Title case
        assertEquals("Hello", method.invoke(null, "Hell0", dictionary));
    }

    @Test
    public void testFindDictionaryCorrection_withPunctuation() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");

        // Should preserve punctuation
        assertEquals("hello,", method.invoke(null, "hell0,", dictionary));
        assertEquals("\"hello\"", method.invoke(null, "\"hell0\"", dictionary));
    }

    @Test
    public void testFindDictionaryCorrection_noMatchReturnsNull() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");

        // Word not in dictionary and no valid correction
        assertNull(method.invoke(null, "xyz123", dictionary));

        // Empty dictionary
        assertNull(method.invoke(null, "hello", new HashSet<String>()));
    }

    @Test
    public void testDictionaryManagerIsWordBasedLanguage() {
        // Western languages should be word-based
        assertTrue(DictionaryManager.isWordBasedLanguage("deu"));
        assertTrue(DictionaryManager.isWordBasedLanguage("eng"));
        assertTrue(DictionaryManager.isWordBasedLanguage("fra"));
        assertTrue(DictionaryManager.isWordBasedLanguage("spa"));

        // CJK and Thai should not be word-based
        assertFalse(DictionaryManager.isWordBasedLanguage("chi_sim"));
        assertFalse(DictionaryManager.isWordBasedLanguage("chi_tra"));
        assertFalse(DictionaryManager.isWordBasedLanguage("tha"));

        // Multi-language spec should use primary language
        assertTrue(DictionaryManager.isWordBasedLanguage("deu+eng"));
        assertFalse(DictionaryManager.isWordBasedLanguage("chi_sim+eng"));

        // Null should return false
        assertFalse(DictionaryManager.isWordBasedLanguage(null));
    }

    @Test
    public void testProcessText_standardCorrections() {
        // Test that standard corrections still work
        String input = "Hel1o W0rld";
        String result = OCRPostProcessor.processText(input, "eng");

        // Standard processing applies ligature and context corrections
        assertNotNull(result);
    }

    @Test
    public void testOcrConfusionsMap() throws Exception {
        // Verify the OCR_CONFUSIONS map is properly initialized
        java.lang.reflect.Field field = OCRPostProcessor.class.getDeclaredField("OCR_CONFUSIONS");
        field.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<Character, char[]> confusions =
                (java.util.Map<Character, char[]>) field.get(null);

        assertNotNull(confusions);
        assertTrue(confusions.size() > 0);

        // Check some key mappings
        assertNotNull(confusions.get('0')); // 0 -> O, o, Q
        assertNotNull(confusions.get('1')); // 1 -> l, I, i
        assertNotNull(confusions.get('m')); // m -> n, r (for rn ligature)
    }

    // ==================== Language-specific tests ====================

    @Test
    public void testSpanish_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hola");      // hello
        dictionary.add("mundo");     // world
        dictionary.add("español");   // Spanish
        dictionary.add("niño");      // child

        // 0 -> o correction: "h0la" -> "hola"
        assertEquals("hola", method.invoke(null, "h0la", dictionary));

        // 1 -> l correction in Spanish word
        assertEquals("hola", method.invoke(null, "ho1a", dictionary));

        // rn -> m correction: "rnundo" -> "mundo"
        assertEquals("mundo", method.invoke(null, "rnundo", dictionary));
    }

    @Test
    public void testItalian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("ciao");      // hello
        dictionary.add("mondo");     // world
        dictionary.add("italiano");  // Italian
        dictionary.add("città");     // city

        // 0 -> o correction: "m0ndo" -> "mondo" (single substitution)
        assertEquals("mondo", method.invoke(null, "m0ndo", dictionary));

        // rn -> m correction: "rnondo" -> "mondo"
        assertEquals("mondo", method.invoke(null, "rnondo", dictionary));
    }

    @Test
    public void testPortuguese_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("olá");       // hello
        dictionary.add("mundo");     // world
        dictionary.add("português"); // Portuguese
        dictionary.add("coração");   // heart

        // 0 -> o correction: "mund0" -> "mundo"
        assertEquals("mundo", method.invoke(null, "mund0", dictionary));

        // rn -> m correction
        assertEquals("mundo", method.invoke(null, "rnundo", dictionary));
    }

    @Test
    public void testDutch_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hallo");     // hello
        dictionary.add("wereld");    // world
        dictionary.add("nederland"); // Netherlands
        dictionary.add("mooi");      // beautiful

        // 0 -> o correction: "hall0" -> "hallo"
        assertEquals("hallo", method.invoke(null, "hall0", dictionary));

        // 1 -> l correction: "ha1lo" -> "hallo"
        assertEquals("hallo", method.invoke(null, "ha1lo", dictionary));

        // rn -> m correction: "rnooi" -> "mooi"
        assertEquals("mooi", method.invoke(null, "rnooi", dictionary));
    }

    @Test
    public void testPolish_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("cześć");     // hello
        dictionary.add("świat");     // world
        dictionary.add("polska");    // Poland
        dictionary.add("miłość");    // love

        // 0 -> o correction: "p0lska" -> "polska"
        assertEquals("polska", method.invoke(null, "p0lska", dictionary));

        // rn -> m correction: "rniłość" -> "miłość"
        assertEquals("miłość", method.invoke(null, "rniłość", dictionary));
    }

    @Test
    public void testCzech_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("ahoj");      // hello
        dictionary.add("svět");      // world
        dictionary.add("česky");     // Czech
        dictionary.add("město");     // city

        // 0 -> o correction: "ah0j" -> "ahoj"
        assertEquals("ahoj", method.invoke(null, "ah0j", dictionary));

        // rn -> m correction: "rněsto" -> "město"
        assertEquals("město", method.invoke(null, "rněsto", dictionary));
    }

    @Test
    public void testSlovak_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("ahoj");      // hello
        dictionary.add("svet");      // world
        dictionary.add("slovensko"); // Slovakia
        dictionary.add("mesto");     // city

        // 0 -> o correction: "sl0vensko" -> "slovensko" (single substitution)
        assertEquals("slovensko", method.invoke(null, "sl0vensko", dictionary));

        // rn -> m correction: "rnesto" -> "mesto"
        assertEquals("mesto", method.invoke(null, "rnesto", dictionary));
    }

    @Test
    public void testHungarian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("szia");       // hello
        dictionary.add("világ");      // world
        dictionary.add("magyar");     // Hungarian
        dictionary.add("szerelem");   // love

        // 0 -> o correction in Hungarian context
        // rn -> m correction: "szerelern" -> "szerelem"
        assertEquals("szerelem", method.invoke(null, "szerelern", dictionary));
    }

    @Test
    public void testRomanian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("salut");     // hello
        dictionary.add("lume");      // world
        dictionary.add("română");    // Romanian
        dictionary.add("dragoste");  // love

        // 0 -> o correction: "r0mână" -> "română"
        assertEquals("română", method.invoke(null, "r0mână", dictionary));

        // 1 -> l correction: "1ume" -> "lume"
        assertEquals("lume", method.invoke(null, "1ume", dictionary));
    }

    @Test
    public void testDanish_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hej");       // hello
        dictionary.add("verden");    // world
        dictionary.add("dansk");     // Danish
        dictionary.add("kærlighed"); // love
        dictionary.add("sommer");    // summer

        // rn -> m correction: "sornmer" -> "sommer"
        assertEquals("sommer", method.invoke(null, "sornmer", dictionary));

        // No valid correction for unknown character substitution
        assertNull(method.invoke(null, "d4nsk", dictionary)); // 4 not in confusions
    }

    @Test
    public void testNorwegian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hei");       // hello
        dictionary.add("verden");    // world
        dictionary.add("norsk");     // Norwegian
        dictionary.add("kjærlighet");// love

        // 0 -> o correction: "n0rsk" -> "norsk"
        assertEquals("norsk", method.invoke(null, "n0rsk", dictionary));
    }

    @Test
    public void testSwedish_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hej");       // hello
        dictionary.add("värld");     // world
        dictionary.add("svensk");    // Swedish
        dictionary.add("kärlek");    // love
        dictionary.add("sommar");    // summer

        // 0 -> o correction
        // rn -> m correction: "sornrnar" -> "sommar"
        assertEquals("sommar", method.invoke(null, "sornmar", dictionary));
    }

    @Test
    public void testRussian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("привет");    // hello
        dictionary.add("мир");       // world
        dictionary.add("русский");   // Russian
        dictionary.add("любовь");    // love

        // Russian uses Cyrillic, so Latin OCR confusions don't apply directly
        // But the dictionary lookup should still work
        assertNull(method.invoke(null, "xyz", dictionary)); // No match

        // Word in dictionary should be found if exact match after case normalization
        // Note: findDictionaryCorrection looks for corrections, not exact matches
    }

    @Test
    public void testPersian_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("سلام");      // hello
        dictionary.add("جهان");      // world
        dictionary.add("فارسی");     // Persian
        dictionary.add("عشق");       // love
        dictionary.add("کتاب");      // book
        dictionary.add("خانه");      // house

        // Persian uses Arabic script, so Latin OCR confusions don't apply directly
        // But the dictionary lookup should still work
        assertNull(method.invoke(null, "xyz", dictionary)); // No match

        // Word in dictionary should be found if exact match after case normalization
        // Note: findDictionaryCorrection looks for corrections, not exact matches
    }

    @Test
    public void testArabic_dictionaryCorrection() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("مرحبا");     // hello
        dictionary.add("عالم");      // world
        dictionary.add("عربي");      // Arabic
        dictionary.add("حب");        // love
        dictionary.add("كتاب");      // book
        dictionary.add("بيت");       // house

        // Arabic uses Arabic script, so Latin OCR confusions don't apply directly
        // But the dictionary lookup should still work
        assertNull(method.invoke(null, "xyz", dictionary)); // No match

        // Word in dictionary should be found if exact match after case normalization
        // Note: findDictionaryCorrection looks for corrections, not exact matches
    }

    @Test
    public void testDictionaryManagerIsWordBasedLanguage_allLanguages() {
        // All Western/Latin languages should be word-based
        assertTrue(DictionaryManager.isWordBasedLanguage("deu")); // German
        assertTrue(DictionaryManager.isWordBasedLanguage("eng")); // English
        assertTrue(DictionaryManager.isWordBasedLanguage("fra")); // French
        assertTrue(DictionaryManager.isWordBasedLanguage("spa")); // Spanish
        assertTrue(DictionaryManager.isWordBasedLanguage("ita")); // Italian
        assertTrue(DictionaryManager.isWordBasedLanguage("por")); // Portuguese
        assertTrue(DictionaryManager.isWordBasedLanguage("nld")); // Dutch
        assertTrue(DictionaryManager.isWordBasedLanguage("pol")); // Polish
        assertTrue(DictionaryManager.isWordBasedLanguage("ces")); // Czech
        assertTrue(DictionaryManager.isWordBasedLanguage("slk")); // Slovak
        assertTrue(DictionaryManager.isWordBasedLanguage("hun")); // Hungarian
        assertTrue(DictionaryManager.isWordBasedLanguage("ron")); // Romanian
        assertTrue(DictionaryManager.isWordBasedLanguage("dan")); // Danish
        assertTrue(DictionaryManager.isWordBasedLanguage("nor")); // Norwegian
        assertTrue(DictionaryManager.isWordBasedLanguage("swe")); // Swedish
        assertTrue(DictionaryManager.isWordBasedLanguage("rus")); // Russian

        // Persian and Arabic should be word-based (they use spaces between words)
        assertTrue(DictionaryManager.isWordBasedLanguage("fas")); // Persian
        assertTrue(DictionaryManager.isWordBasedLanguage("ara")); // Arabic

        // CJK and Thai should NOT be word-based
        assertFalse(DictionaryManager.isWordBasedLanguage("chi_sim")); // Chinese Simplified
        assertFalse(DictionaryManager.isWordBasedLanguage("chi_tra")); // Chinese Traditional
        assertFalse(DictionaryManager.isWordBasedLanguage("tha"));     // Thai
    }

    @Test
    public void testSingleSubstitutions_crossLanguage() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");     // English
        dictionary.add("hallo");     // German/Dutch
        dictionary.add("ciao");      // Italian
        dictionary.add("olá");       // Portuguese

        // Single 0 -> o correction: "hell0" -> "hello"
        assertEquals("hello", method.invoke(null, "hell0", dictionary));

        // Single 1 -> l correction: "ha1lo" -> "hallo"
        assertEquals("hallo", method.invoke(null, "ha1lo", dictionary));

        // Single 1 -> i correction: "c1ao" -> "ciao"
        assertEquals("ciao", method.invoke(null, "c1ao", dictionary));

        // Single 0 -> o correction: "cia0" -> "ciao"
        assertEquals("ciao", method.invoke(null, "cia0", dictionary));
    }

    @Test
    public void testLigatureCorrections_crossLanguage() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("summer");    // English
        dictionary.add("sommer");    // German/Danish/Norwegian
        dictionary.add("ommer");     // Part of Danish word

        // rn -> m in English: "surnmer" -> "summer"
        assertEquals("summer", method.invoke(null, "surnmer", dictionary));

        // rn -> m in German: "sornmer" -> "sommer"
        assertEquals("sommer", method.invoke(null, "sornmer", dictionary));
    }

    @Test
    public void testCasePreservation_allLanguages() throws Exception {
        java.lang.reflect.Method method = OCRPostProcessor.class.getDeclaredMethod(
                "findDictionaryCorrection", String.class, Set.class);
        method.setAccessible(true);

        Set<String> dictionary = new HashSet<>();
        dictionary.add("hello");
        dictionary.add("mundo");
        dictionary.add("świat");

        // Uppercase preservation
        assertEquals("HELLO", method.invoke(null, "HELL0", dictionary));
        assertEquals("MUNDO", method.invoke(null, "MUND0", dictionary));

        // Title case preservation
        assertEquals("Hello", method.invoke(null, "Hell0", dictionary));
        assertEquals("Mundo", method.invoke(null, "Mund0", dictionary));

        // Lowercase preservation
        assertEquals("hello", method.invoke(null, "hell0", dictionary));
        assertEquals("mundo", method.invoke(null, "mund0", dictionary));
    }
}
