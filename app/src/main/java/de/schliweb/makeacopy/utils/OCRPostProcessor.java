package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.util.Log;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Post-processor for OCR results that corrects common OCR errors.
 * This class applies context-aware corrections to improve OCR accuracy,
 * particularly for words with low confidence scores.
 * <p>
 * Common OCR confusions handled:
 * - 0 ↔ O (zero vs. letter O)
 * - 1 ↔ l ↔ I (one vs. lowercase L vs. uppercase I)
 * - rn ↔ m (rn ligature confused with m)
 * - cl ↔ d (cl confused with d)
 * - vv ↔ w (double v confused with w)
 * - 5 ↔ S (five vs. letter S)
 * - 8 ↔ B (eight vs. letter B)
 */
public class OCRPostProcessor {

    private static final String TAG = "OCRPostProcessor";

    /**
     * Confidence threshold below which words are considered for correction.
     * Words with confidence >= this value are assumed to be correct.
     */
    private static final float LOW_CONFIDENCE_THRESHOLD = 70.0f;

    /**
     * Minimum word length for applying pattern-based corrections.
     * Very short words are more prone to false corrections.
     */
    private static final int MIN_WORD_LENGTH_FOR_PATTERN_CORRECTION = 3;

    // Common OCR error patterns (context-dependent)
    private static final Map<String, String> LETTER_IN_NUMBER_CONTEXT = new HashMap<>();
    private static final Map<String, String> NUMBER_IN_LETTER_CONTEXT = new HashMap<>();
    private static final Map<String, String> LIGATURE_CORRECTIONS = new HashMap<>();

    // Patterns for context detection
    private static final Pattern MOSTLY_DIGITS = Pattern.compile("^[0-9.,:\\-/]+$");
    private static final Pattern MOSTLY_LETTERS = Pattern.compile("^[a-zA-ZäöüÄÖÜßàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆáéíóúüñÁÉÍÓÚÜÑàèéìíîòóùúÀÈÉÌÍÎÒÓÙÚáàâãçéêíóôõúüÁÀÂÃÇÉÊÍÓÔÕÚÜ]+$");
    private static final Pattern CONTAINS_DIGITS = Pattern.compile(".*[0-9].*");
    private static final Pattern CONTAINS_LETTERS = Pattern.compile(".*[a-zA-Z].*");
    /**
     * Common OCR character confusions used for generating correction candidates.
     * Maps characters to their commonly confused alternatives.
     */
    private static final Map<Character, char[]> OCR_CONFUSIONS = new HashMap<>();

    static {
        // Letters that should be digits in numeric context
        LETTER_IN_NUMBER_CONTEXT.put("O", "0");
        LETTER_IN_NUMBER_CONTEXT.put("o", "0");
        LETTER_IN_NUMBER_CONTEXT.put("l", "1");
        LETTER_IN_NUMBER_CONTEXT.put("I", "1");
        LETTER_IN_NUMBER_CONTEXT.put("i", "1");
        LETTER_IN_NUMBER_CONTEXT.put("S", "5");
        LETTER_IN_NUMBER_CONTEXT.put("s", "5");
        LETTER_IN_NUMBER_CONTEXT.put("B", "8");
        LETTER_IN_NUMBER_CONTEXT.put("Z", "2");
        LETTER_IN_NUMBER_CONTEXT.put("z", "2");
        LETTER_IN_NUMBER_CONTEXT.put("G", "6");
        LETTER_IN_NUMBER_CONTEXT.put("g", "9");

        // Digits that should be letters in text context
        NUMBER_IN_LETTER_CONTEXT.put("0", "O");
        NUMBER_IN_LETTER_CONTEXT.put("1", "l");
        NUMBER_IN_LETTER_CONTEXT.put("5", "S");
        NUMBER_IN_LETTER_CONTEXT.put("8", "B");

        // Common ligature/character sequence confusions
        LIGATURE_CORRECTIONS.put("rn", "m");
        LIGATURE_CORRECTIONS.put("cl", "d");
        LIGATURE_CORRECTIONS.put("vv", "w");
        LIGATURE_CORRECTIONS.put("li", "h");  // sometimes li looks like h
        LIGATURE_CORRECTIONS.put("cI", "d");  // uppercase I confused
        LIGATURE_CORRECTIONS.put("c1", "d");  // digit 1 confused
    }

    static {
        // Digit-letter confusions
        OCR_CONFUSIONS.put('0', new char[]{'O', 'o', 'Q'});
        OCR_CONFUSIONS.put('O', new char[]{'0', 'Q', 'o'});
        OCR_CONFUSIONS.put('o', new char[]{'0', 'O'});
        OCR_CONFUSIONS.put('1', new char[]{'l', 'I', 'i', '|'});
        OCR_CONFUSIONS.put('l', new char[]{'1', 'I', 'i', '|'});
        OCR_CONFUSIONS.put('I', new char[]{'1', 'l', 'i', '|'});
        OCR_CONFUSIONS.put('i', new char[]{'1', 'l', 'I', 'j'});
        OCR_CONFUSIONS.put('5', new char[]{'S', 's'});
        OCR_CONFUSIONS.put('S', new char[]{'5', 's'});
        OCR_CONFUSIONS.put('s', new char[]{'5', 'S'});
        OCR_CONFUSIONS.put('8', new char[]{'B'});
        OCR_CONFUSIONS.put('B', new char[]{'8', 'R'});
        OCR_CONFUSIONS.put('2', new char[]{'Z', 'z'});
        OCR_CONFUSIONS.put('Z', new char[]{'2', 'z'});
        OCR_CONFUSIONS.put('z', new char[]{'2', 'Z'});
        OCR_CONFUSIONS.put('6', new char[]{'G', 'b'});
        OCR_CONFUSIONS.put('G', new char[]{'6', 'C'});
        OCR_CONFUSIONS.put('9', new char[]{'g', 'q'});
        OCR_CONFUSIONS.put('g', new char[]{'9', 'q'});
        // Letter confusions
        OCR_CONFUSIONS.put('m', new char[]{'n', 'r'});  // rn -> m
        OCR_CONFUSIONS.put('n', new char[]{'r', 'h'});
        OCR_CONFUSIONS.put('r', new char[]{'n'});
        OCR_CONFUSIONS.put('d', new char[]{'c', 'a'});  // cl -> d
        OCR_CONFUSIONS.put('c', new char[]{'e', 'o'});
        OCR_CONFUSIONS.put('e', new char[]{'c', 'o'});
        OCR_CONFUSIONS.put('w', new char[]{'v'});       // vv -> w
        OCR_CONFUSIONS.put('v', new char[]{'u', 'w'});
        OCR_CONFUSIONS.put('u', new char[]{'v', 'n'});
        OCR_CONFUSIONS.put('h', new char[]{'b', 'n'});
        OCR_CONFUSIONS.put('b', new char[]{'h', 'd', '6'});
        OCR_CONFUSIONS.put('a', new char[]{'o', 'e', 'ä'});
        OCR_CONFUSIONS.put('ä', new char[]{'a'});
        OCR_CONFUSIONS.put('ö', new char[]{'o'});
        OCR_CONFUSIONS.put('ü', new char[]{'u'});
        OCR_CONFUSIONS.put('ß', new char[]{'B', 's'});
    }

    private OCRPostProcessor() {
        // Utility class
    }

    /**
     * Processes a list of recognized words and applies corrections to improve accuracy.
     * Words with low confidence are prioritized for correction.
     *
     * @param words    The list of recognized words to process
     * @param language The language code (e.g., "deu", "eng") for language-specific corrections
     * @return A new list with corrected words (original list is not modified)
     */
    public static List<RecognizedWord> process(List<RecognizedWord> words, String language) {
        if (words == null || words.isEmpty()) {
            return words;
        }

        List<RecognizedWord> result = new ArrayList<>(words.size());
        int correctionCount = 0;

        for (RecognizedWord word : words) {
            String originalText = word.getText();
            String correctedText = correctWord(originalText, word.getConfidence(), language);

            if (!originalText.equals(correctedText)) {
                // Create new word with corrected text (RecognizedWord has setText)
                word.setText(correctedText);
                correctionCount++;
                Log.d(TAG, "Corrected: '" + originalText + "' -> '" + correctedText +
                        "' (conf=" + word.getConfidence() + ")");
            }
            result.add(word);
        }

        if (correctionCount > 0) {
            Log.i(TAG, "Post-processing corrected " + correctionCount + " words");
        }

        return result;
    }

    /**
     * Processes the full OCR text and applies corrections.
     *
     * @param text     The full OCR text
     * @param language The language code
     * @return Corrected text
     */
    public static String processText(String text, String language) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        // Apply ligature corrections (these are almost always errors)
        result = applyLigatureCorrections(result);

        // Apply context-aware corrections for mixed alphanumeric sequences
        result = applyContextAwareCorrections(result);

        return result;
    }

    /**
     * Corrects a single word based on its confidence and context.
     */
    private static String correctWord(String word, float confidence, String language) {
        if (word == null || word.isEmpty()) {
            return word;
        }

        String result = word;

        // Always apply ligature corrections (high confidence fixes)
        result = applyLigatureCorrections(result);

        // For low-confidence words, apply more aggressive corrections
        if (confidence < LOW_CONFIDENCE_THRESHOLD) {
            result = applyContextAwareCorrections(result);
        }

        return result;
    }

    /**
     * Applies ligature corrections that are almost always OCR errors.
     * These patterns (rn→m, cl→d, vv→w) are very common OCR mistakes.
     */
    private static String applyLigatureCorrections(String text) {
        if (text == null || text.length() < MIN_WORD_LENGTH_FOR_PATTERN_CORRECTION) {
            return text;
        }

        String result = text;

        // Only apply ligature corrections in words that look like text (not numbers/codes)
        if (MOSTLY_LETTERS.matcher(result).matches() ||
                (CONTAINS_LETTERS.matcher(result).matches() && !MOSTLY_DIGITS.matcher(result).matches())) {

            for (Map.Entry<String, String> entry : LIGATURE_CORRECTIONS.entrySet()) {
                String pattern = entry.getKey();
                String replacement = entry.getValue();

                // Be conservative: only replace if it results in a more "word-like" result
                if (result.contains(pattern)) {
                    String candidate = result.replace(pattern, replacement);
                    // Simple heuristic: prefer the shorter result for ligatures
                    // (rn→m makes word shorter, which is usually correct)
                    if (candidate.length() <= result.length()) {
                        result = candidate;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Applies context-aware corrections for characters that are commonly confused.
     * Uses surrounding characters to determine if a character should be a letter or digit.
     */
    private static String applyContextAwareCorrections(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = text.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            char corrected = c;

            // Determine context from surrounding characters
            boolean prevIsDigit = (i > 0) && Character.isDigit(chars[i - 1]);
            boolean nextIsDigit = (i < chars.length - 1) && Character.isDigit(chars[i + 1]);
            boolean prevIsLetter = (i > 0) && Character.isLetter(chars[i - 1]);
            boolean nextIsLetter = (i < chars.length - 1) && Character.isLetter(chars[i + 1]);

            // In numeric context (surrounded by digits), convert letters to digits
            if (prevIsDigit && nextIsDigit) {
                String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
                if (replacement != null) {
                    corrected = replacement.charAt(0);
                }
            }
            // In text context (surrounded by letters), convert digits to letters
            else if (prevIsLetter && nextIsLetter) {
                String replacement = NUMBER_IN_LETTER_CONTEXT.get(String.valueOf(c));
                if (replacement != null) {
                    corrected = replacement.charAt(0);
                }
            }
            // At word boundaries, use majority context
            else if (i == 0 && nextIsDigit && !Character.isDigit(c)) {
                // Start of number sequence
                String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
                if (replacement != null && isLikelyNumericSequence(text)) {
                    corrected = replacement.charAt(0);
                }
            } else if (i == chars.length - 1 && prevIsDigit && !Character.isDigit(c)) {
                // End of number sequence
                String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
                if (replacement != null && isLikelyNumericSequence(text)) {
                    corrected = replacement.charAt(0);
                }
            }

            result.append(corrected);
        }

        return result.toString();
    }

    /**
     * Determines if a string is likely meant to be a numeric sequence
     * (e.g., phone number, date, ID number).
     */
    private static boolean isLikelyNumericSequence(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        int digitCount = 0;
        int letterCount = 0;
        int separatorCount = 0;

        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                digitCount++;
            } else if (Character.isLetter(c)) {
                letterCount++;
            } else if (c == '.' || c == ',' || c == '-' || c == '/' || c == ':' || c == ' ') {
                separatorCount++;
            }
        }

        // Consider it numeric if digits significantly outnumber letters
        return digitCount > letterCount * 2 && digitCount >= 2;
    }

    /**
     * Applies German-specific corrections.
     * German has specific patterns like ß, umlauts, and compound words.
     */
    public static String applyGermanCorrections(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        // Common German OCR errors
        // B often misread as ß in certain fonts
        // But be careful: only in lowercase context
        // "Strabe" -> "Straße" (common error)
        result = result.replaceAll("(?<=[a-zäöü])B(?=[a-zäöü])", "ß");

        // "ss" at end of word after short vowel is often "ß" in old spelling
        // But modern German uses "ss", so we don't change this

        return result;
    }

    // ==================== Dictionary-based Correction ====================

    /**
     * Gets statistics about potential OCR errors in the word list.
     *
     * @param words The list of recognized words
     * @return Statistics about the OCR quality
     */
    public static OcrQualityStats analyzeQuality(List<RecognizedWord> words) {
        if (words == null || words.isEmpty()) {
            return new OcrQualityStats(0, 0, 0, 0, new ArrayList<>());
        }

        float totalConfidence = 0;
        int lowConfidenceCount = 0;
        List<String> suspiciousWords = new ArrayList<>();

        for (RecognizedWord word : words) {
            totalConfidence += word.getConfidence();

            if (word.getConfidence() < LOW_CONFIDENCE_THRESHOLD) {
                lowConfidenceCount++;
                if (suspiciousWords.size() < 20) { // Limit list size
                    suspiciousWords.add(word.getText() + " (" + (int) word.getConfidence() + "%)");
                }
            }
        }

        float meanConfidence = totalConfidence / words.size();

        // Calculate median
        List<Float> confidences = new ArrayList<>();
        for (RecognizedWord w : words) {
            confidences.add(w.getConfidence());
        }
        confidences.sort(Float::compare);
        float medianConfidence = confidences.get(confidences.size() / 2);

        return new OcrQualityStats(
                meanConfidence,
                medianConfidence,
                lowConfidenceCount,
                words.size(),
                suspiciousWords
        );
    }

    /**
     * Processes a list of recognized words with dictionary-based correction.
     * Words not found in the dictionary are corrected by trying common OCR error substitutions.
     *
     * @param words    The list of recognized words to process
     * @param language The language code (e.g., "deu", "eng")
     * @param context  Android context for accessing dictionary assets
     * @return A new list with corrected words
     */
    public static List<RecognizedWord> processWithDictionary(List<RecognizedWord> words,
                                                             String language, Context context) {
        if (words == null || words.isEmpty() || context == null) {
            return process(words, language);
        }

        // Skip dictionary correction for non-word-based languages
        if (!DictionaryManager.isWordBasedLanguage(language)) {
            return process(words, language);
        }

        DictionaryManager dictManager = DictionaryManager.getInstance(context);
        Set<String> dictionary = dictManager.getDictionary(language);

        if (dictionary.isEmpty()) {
            Log.d(TAG, "No dictionary available for " + language + ", using standard correction");
            return process(words, language);
        }

        List<RecognizedWord> result = new ArrayList<>(words.size());
        int correctionCount = 0;
        int dictCorrectionCount = 0;

        for (RecognizedWord word : words) {
            String originalText = word.getText();

            // First apply standard corrections
            String correctedText = correctWord(originalText, word.getConfidence(), language);

            // Then try dictionary-based correction if word is not in dictionary
            String cleanWord = extractCleanWord(correctedText);
            if (!cleanWord.isEmpty() && cleanWord.length() >= 2 &&
                    !dictionary.contains(cleanWord.toLowerCase())) {

                String dictCorrected = findDictionaryCorrection(correctedText, dictionary);
                if (dictCorrected != null && !dictCorrected.equals(correctedText)) {
                    Log.d(TAG, "Dictionary corrected: '" + correctedText + "' -> '" + dictCorrected + "'");
                    correctedText = dictCorrected;
                    dictCorrectionCount++;
                }
            }

            if (!originalText.equals(correctedText)) {
                word.setText(correctedText);
                correctionCount++;
                Log.d(TAG, "Corrected: '" + originalText + "' -> '" + correctedText +
                        "' (conf=" + word.getConfidence() + ")");
            }
            result.add(word);
        }

        if (correctionCount > 0) {
            Log.i(TAG, "Post-processing corrected " + correctionCount + " words (" +
                    dictCorrectionCount + " via dictionary)");
        }

        return result;
    }

    /**
     * Processes the full OCR text with dictionary-based correction.
     *
     * @param text     The full OCR text
     * @param language The language code
     * @param context  Android context for accessing dictionary assets
     * @return Corrected text
     */
    public static String processTextWithDictionary(String text, String language, Context context) {
        if (text == null || text.isEmpty() || context == null) {
            return processText(text, language);
        }

        // Skip dictionary correction for non-word-based languages
        if (!DictionaryManager.isWordBasedLanguage(language)) {
            return processText(text, language);
        }

        DictionaryManager dictManager = DictionaryManager.getInstance(context);
        Set<String> dictionary = dictManager.getDictionary(language);

        if (dictionary.isEmpty()) {
            return processText(text, language);
        }

        // First apply standard corrections
        String result = processText(text, language);

        // Then apply dictionary-based word corrections
        StringBuilder corrected = new StringBuilder();
        StringBuilder currentWord = new StringBuilder();

        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);

            if (Character.isLetterOrDigit(c) || c == '-' || c == '\'') {
                currentWord.append(c);
            } else {
                if (currentWord.length() > 0) {
                    String word = currentWord.toString();
                    String cleanWord = extractCleanWord(word);

                    if (!cleanWord.isEmpty() && cleanWord.length() >= 2 &&
                            !dictionary.contains(cleanWord.toLowerCase())) {
                        String dictCorrected = findDictionaryCorrection(word, dictionary);
                        if (dictCorrected != null) {
                            word = dictCorrected;
                        }
                    }
                    corrected.append(word);
                    currentWord.setLength(0);
                }
                corrected.append(c);
            }
        }

        // Don't forget the last word
        if (currentWord.length() > 0) {
            String word = currentWord.toString();
            String cleanWord = extractCleanWord(word);

            if (!cleanWord.isEmpty() && cleanWord.length() >= 2 &&
                    !dictionary.contains(cleanWord.toLowerCase())) {
                String dictCorrected = findDictionaryCorrection(word, dictionary);
                if (dictCorrected != null) {
                    word = dictCorrected;
                }
            }
            corrected.append(word);
        }

        return corrected.toString();
    }

    /**
     * Extracts a clean word by removing leading/trailing punctuation.
     */
    private static String extractCleanWord(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }

        int start = 0;
        int end = word.length();

        while (start < end && !Character.isLetterOrDigit(word.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(word.charAt(end - 1))) {
            end--;
        }

        return start < end ? word.substring(start, end) : "";
    }

    /**
     * Finds a dictionary correction for a word by trying common OCR error substitutions.
     *
     * @param word       The word to correct
     * @param dictionary The dictionary to check against
     * @return The corrected word, or null if no correction found
     */
    private static String findDictionaryCorrection(String word, Set<String> dictionary) {
        if (word == null || word.length() < 2 || dictionary.isEmpty()) {
            return null;
        }

        String cleanWord = extractCleanWord(word);
        if (cleanWord.isEmpty()) {
            return null;
        }

        // Try single character substitutions
        String singleSubResult = trySingleCharSubstitutions(word, cleanWord, dictionary);
        if (singleSubResult != null) {
            return singleSubResult;
        }

        // Try ligature expansions/contractions
        String ligatureResult = tryLigatureCorrections(word, cleanWord, dictionary);
        return ligatureResult;
    }

    /**
     * Tries single character substitutions based on common OCR confusions.
     */
    private static String trySingleCharSubstitutions(String originalWord, String cleanWord,
                                                     Set<String> dictionary) {
        char[] chars = cleanWord.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char original = chars[i];
            char[] confusions = OCR_CONFUSIONS.get(original);

            if (confusions != null) {
                for (char replacement : confusions) {
                    chars[i] = replacement;
                    String candidate = new String(chars);

                    if (dictionary.contains(candidate.toLowerCase())) {
                        // Preserve original case pattern
                        return preserveCasePattern(originalWord, cleanWord, candidate);
                    }
                }
                chars[i] = original; // Reset for next iteration
            }
        }

        return null;
    }

    /**
     * Tries ligature-based corrections (rn↔m, cl↔d, vv↔w).
     */
    private static String tryLigatureCorrections(String originalWord, String cleanWord,
                                                 Set<String> dictionary) {
        // Try expanding ligatures
        String[][] expansions = {
                {"m", "rn"},
                {"w", "vv"},
                {"d", "cl"}
        };

        for (String[] pair : expansions) {
            String contracted = pair[0];
            String expanded = pair[1];

            // Try contraction (rn -> m)
            if (cleanWord.contains(expanded)) {
                String candidate = cleanWord.replace(expanded, contracted);
                if (dictionary.contains(candidate.toLowerCase())) {
                    return preserveCasePattern(originalWord, cleanWord, candidate);
                }
            }

            // Try expansion (m -> rn)
            if (cleanWord.contains(contracted)) {
                String candidate = cleanWord.replace(contracted, expanded);
                if (dictionary.contains(candidate.toLowerCase())) {
                    return preserveCasePattern(originalWord, cleanWord, candidate);
                }
            }
        }

        return null;
    }

    /**
     * Preserves the case pattern from the original word when applying a correction.
     */
    private static String preserveCasePattern(String originalWord, String cleanWord, String corrected) {
        if (cleanWord.equals(originalWord)) {
            // No punctuation to preserve
            return applyCasePattern(cleanWord, corrected);
        }

        // Find where cleanWord starts in originalWord
        int startIdx = originalWord.indexOf(cleanWord.charAt(0));
        if (startIdx < 0) {
            startIdx = 0;
        }

        StringBuilder result = new StringBuilder();

        // Add leading punctuation
        result.append(originalWord.substring(0, startIdx));

        // Add corrected word with case pattern
        result.append(applyCasePattern(cleanWord, corrected));

        // Add trailing punctuation
        int endIdx = startIdx + cleanWord.length();
        if (endIdx < originalWord.length()) {
            result.append(originalWord.substring(endIdx));
        }

        return result.toString();
    }

    /**
     * Applies the case pattern from the source word to the target word.
     */
    private static String applyCasePattern(String source, String target) {
        if (source.length() == 0 || target.length() == 0) {
            return target;
        }

        // Check if all uppercase
        if (source.equals(source.toUpperCase())) {
            return target.toUpperCase();
        }

        // Check if all lowercase
        if (source.equals(source.toLowerCase())) {
            return target.toLowerCase();
        }

        // Check if title case (first letter uppercase)
        if (Character.isUpperCase(source.charAt(0))) {
            return Character.toUpperCase(target.charAt(0)) +
                    (target.length() > 1 ? target.substring(1).toLowerCase() : "");
        }

        return target.toLowerCase();
    }

    /**
     * Statistics about OCR quality for a set of recognized words.
     */
    public record OcrQualityStats(float meanConfidence, float medianConfidence, int lowConfidenceWordCount,
                                  int totalWordCount, List<String> suspiciousWords) {

        public float getLowConfidenceRatio() {
            return totalWordCount > 0 ? (float) lowConfidenceWordCount / totalWordCount : 0;
        }

        @Override
        public String toString() {
            return "OcrQualityStats{" +
                    "meanConf=" + String.format("%.1f", meanConfidence) +
                    ", medianConf=" + String.format("%.1f", medianConfidence) +
                    ", lowConfWords=" + lowConfidenceWordCount + "/" + totalWordCount +
                    " (" + String.format("%.1f", getLowConfidenceRatio() * 100) + "%)" +
                    '}';
        }
    }
}
