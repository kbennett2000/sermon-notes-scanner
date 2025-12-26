package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Manages dictionaries for OCR post-processing correction.
 * Dictionaries are loaded lazily from assets and cached in memory.
 * <p>
 * Supported languages correspond to Tesseract language codes:
 * deu, eng, fra, spa, ita, por, nld, pol, ces, slk, hun, ron, dan, nor, swe, rus, chi_sim, chi_tra, tha
 */
public class DictionaryManager {

    private static final String TAG = "DictionaryManager";
    private static final String DICT_DIR = "dictionaries";
    private static final String DICT_EXTENSION_GZ = ".txt.gz";
    private static final String DICT_EXTENSION_TXT = ".txt";
    // Languages that don't use word-based dictionaries (CJK, Thai)
    private static final Set<String> NON_WORD_BASED_LANGUAGES = new HashSet<>();
    private static DictionaryManager instance;

    static {
        NON_WORD_BASED_LANGUAGES.add("chi_sim");
        NON_WORD_BASED_LANGUAGES.add("chi_tra");
        NON_WORD_BASED_LANGUAGES.add("tha");
    }

    private final Context context;
    private final Map<String, Set<String>> loadedDictionaries = new HashMap<>();

    private DictionaryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Gets the singleton instance of DictionaryManager.
     *
     * @param context Application context
     * @return DictionaryManager instance
     */
    public static synchronized DictionaryManager getInstance(Context context) {
        if (instance == null) {
            instance = new DictionaryManager(context);
        }
        return instance;
    }

    /**
     * Checks if the language uses word-based dictionary correction.
     * CJK and Thai languages don't use word boundaries like Western languages.
     *
     * @param language Tesseract language code
     * @return true if word-based correction is applicable
     */
    public static boolean isWordBasedLanguage(String language) {
        if (language == null) {
            return false;
        }
        String primaryLang = language.contains("+") ? language.split("\\+")[0] : language;
        return !NON_WORD_BASED_LANGUAGES.contains(primaryLang);
    }

    /**
     * Checks if a word exists in the dictionary for the given language.
     *
     * @param word     The word to check (case-insensitive)
     * @param language Tesseract language code (e.g., "deu", "eng")
     * @return true if the word is in the dictionary, false otherwise
     */
    public boolean isValidWord(String word, String language) {
        if (word == null || word.isEmpty() || language == null) {
            return false;
        }

        // Skip dictionary check for non-word-based languages
        if (NON_WORD_BASED_LANGUAGES.contains(language)) {
            return true;
        }

        Set<String> dictionary = getDictionary(language);
        if (dictionary.isEmpty()) {
            return true; // No dictionary available, assume valid
        }

        return dictionary.contains(word.toLowerCase());
    }

    /**
     * Gets the dictionary for a language, loading it if necessary.
     *
     * @param language Tesseract language code
     * @return Set of words in the dictionary (lowercase), or empty set if not available
     */
    public Set<String> getDictionary(String language) {
        if (language == null) {
            return Collections.emptySet();
        }

        // Handle multi-language specs (e.g., "deu+eng")
        String primaryLang = language.contains("+") ? language.split("\\+")[0] : language;

        // Check cache
        if (loadedDictionaries.containsKey(primaryLang)) {
            return loadedDictionaries.get(primaryLang);
        }

        // Load dictionary
        Set<String> dictionary = loadDictionary(primaryLang);
        loadedDictionaries.put(primaryLang, dictionary);
        return dictionary;
    }

    /**
     * Loads a dictionary from assets.
     * Tries uncompressed .txt first (Android may decompress .gz during APK packaging),
     * then falls back to .txt.gz if not found.
     *
     * @param language Tesseract language code
     * @return Set of words (lowercase)
     */
    private Set<String> loadDictionary(String language) {
        Set<String> words = new HashSet<>();

        // Try uncompressed .txt first (Android decompresses .gz files during APK packaging)
        String filenameTxt = DICT_DIR + "/" + language + DICT_EXTENSION_TXT;
        try (InputStream is = context.getAssets().open(filenameTxt);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word);
                }
            }

            Log.i(TAG, "Loaded dictionary for " + language + ": " + words.size() + " words (uncompressed)");
            return words;

        } catch (IOException e) {
            // Try compressed .gz version as fallback
            Log.d(TAG, "Uncompressed dictionary not found for " + language + ", trying .gz");
        }

        // Fallback: try .txt.gz (original compressed format)
        String filenameGz = DICT_DIR + "/" + language + DICT_EXTENSION_GZ;
        try (InputStream is = context.getAssets().open(filenameGz);
             GZIPInputStream gis = new GZIPInputStream(is);
             BufferedReader br = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word);
                }
            }

            Log.i(TAG, "Loaded dictionary for " + language + ": " + words.size() + " words (compressed)");

        } catch (IOException e) {
            Log.w(TAG, "Dictionary not available for " + language + ": " + e.getMessage());
            // Return empty set - dictionary not available
        }

        return words;
    }

    /**
     * Checks if a dictionary is available for the given language.
     * Checks for both uncompressed .txt and compressed .txt.gz files.
     *
     * @param language Tesseract language code
     * @return true if dictionary exists
     */
    public boolean hasDictionary(String language) {
        if (language == null) {
            return false;
        }

        String primaryLang = language.contains("+") ? language.split("\\+")[0] : language;
        String targetFileTxt = primaryLang + DICT_EXTENSION_TXT;
        String targetFileGz = primaryLang + DICT_EXTENSION_GZ;

        try {
            String[] files = context.getAssets().list(DICT_DIR);
            if (files != null) {
                for (String file : files) {
                    if (file.equals(targetFileTxt) || file.equals(targetFileGz)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Error checking dictionary availability", e);
        }

        return false;
    }

    /**
     * Preloads dictionaries for the specified languages.
     * Call this in background to avoid loading delay during OCR.
     *
     * @param languages Array of Tesseract language codes
     */
    public void preloadDictionaries(String... languages) {
        for (String lang : languages) {
            getDictionary(lang);
        }
    }

    /**
     * Clears all cached dictionaries to free memory.
     */
    public void clearCache() {
        loadedDictionaries.clear();
        Log.i(TAG, "Dictionary cache cleared");
    }

    /**
     * Gets the number of words in the dictionary for a language.
     *
     * @param language Tesseract language code
     * @return Number of words, or 0 if not loaded/available
     */
    public int getDictionarySize(String language) {
        return getDictionary(language).size();
    }
}
