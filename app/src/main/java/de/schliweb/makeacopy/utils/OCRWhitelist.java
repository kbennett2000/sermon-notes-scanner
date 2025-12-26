package de.schliweb.makeacopy.utils;

/**
 * A utility class that provides predefined whitelists for characters based on specific languages,
 * commonly used in Optical Character Recognition (OCR) processes. This class helps restrict
 * the character set to improve OCR accuracy and reduce errors.
 * <p>
 * This class is not intended to be instantiated.
 */
public class OCRWhitelist {

    // German (includes common symbols: %, €, §, +, =, <, >, &, @, #, *, _, |, \, {, }, ~)
    public static final String DE = "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜabcdefghijklmnopqrstuvwxyzäöüß0123456789.,:;-?!()[]/\"' %€§+=<>&@#*_|\\{}~";

    // English
    public static final String EN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:;-?!()[]/\"' ";

    // Spanish
    public static final String ES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzáéíóúüñÁÉÍÓÚÜÑ0123456789.,:;-?!()[]/\"' ";

    // French
    public static final String FR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ0123456789.,:;-?!()[]/\"' ";

    // Italian
    public static final String IT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzàèéìíîòóùúÀÈÉÌÍÎÒÓÙÚ0123456789.,:;-?!()[]/\"' ";

    // Portuguese
    public static final String PT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzáàâãçéêíóôõúüÁÀÂÃÇÉÊÍÓÔÕÚÜ0123456789.,:;-?!()[]/\"' ";

    // Dutch
    public static final String NL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÁÀÂÄÉÈÊËÍÌÎÏÓÒÔÖÚÙÛÜáàâäéèêëíìîïóòôöúùûüÿŸ0123456789.,:;-?!()[]/\"' ";

    // Polish
    public static final String PL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzĄĆĘŁŃÓŚŹŻąćęłńóśźż0123456789.,:;-?!()[]/\"' ";

    // Czech
    public static final String CS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÁČĎÉĚÍŇÓŘŠŤÚŮÝŽáčďéěíňóřšťúůýž0123456789.,:;-?!()[]/\"' ";

    // Slovak
    public static final String SK = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÁÄČĎÉÍĹĽŇÓÔŔŠŤÚÝŽáäčďéíĺľňóôŕšťúýž0123456789.,:;-?!()[]/\"' ";

    // Hungarian
    public static final String HU = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÁÉÍÓÖŐÚÜŰáéíóöőúüű0123456789.,:;-?!()[]/\"' ";

    // Romanian
    public static final String RO = "ABCDEFGHIJKLMNOPQRSTUVWXYZĂÂÎȘȚŞŢabcdefghijklmnopqrstuvwxyzăâîșțşţ0123456789.,:;-?!()[]/\"' ";

    // Danish
    public static final String DA = "ABCDEFGHIJKLMNOPQRSTUVWXYZÆØÅabcdefghijklmnopqrstuvwxyzæøå0123456789.,:;-?!()[]/\"' ";

    // Norwegian
    public static final String NO = "ABCDEFGHIJKLMNOPQRSTUVWXYZÆØÅabcdefghijklmnopqrstuvwxyzæøå0123456789.,:;-?!()[]/\"' ";

    // Swedish
    public static final String SV = "ABCDEFGHIJKLMNOPQRSTUVWXYZÅÄÖabcdefghijklmnopqrstuvwxyzåäö0123456789.,:;-?!()[]/\"' ";

    // Russian (Cyrillic incl. Ё/ё)
    public static final String RU = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789.,:;-?!()[]/\"' ";

    // Default: Superset
    public static final String DEFAULT = (DE + EN + ES + FR + IT + PT + NL + PL + CS + SK + HU + RO + DA + NO + SV + RU);

    /**
     * Returns a predefined whitelist of allowed characters for a given language code.
     * The whitelist is used to improve processing accuracy by restricting the character set.
     *
     * @param languageCode The ISO language code (e.g., "deu" for German, "eng" for English).
     *                     When null or an unsupported code is provided, a default whitelist is returned.
     * @return A string containing the whitelist of allowed characters for the specified language,
     * or the default whitelist if the language code is null or unsupported.
     */
    public static String getWhitelistForLanguage(String languageCode) {
        if (languageCode == null) return DEFAULT;
        return switch (languageCode) {
            case "deu" -> DE;
            case "eng" -> EN;
            case "spa" -> ES;
            case "fra" -> FR;
            case "ita" -> IT;
            case "por" -> PT;
            case "nld" -> NL;
            case "pol" -> PL;
            case "ces" -> CS;
            case "slk" -> SK;
            case "hun" -> HU;
            case "ron" -> RO;
            case "dan" -> DA;
            case "nor" -> NO;
            case "swe" -> SV;
            case "rus" -> RU;
            default -> DEFAULT;
        };
    }

    /**
     * Generates a composite whitelist of allowed characters for a given language specification.
     * The language specification can consist of multiple language codes separated by a "+".
     * Each language code will correspond to a predefined whitelist, and their characters
     * are combined into a single whitelist, ensuring no duplicate characters are present.
     *
     * @param langSpec A string containing one or more ISO language codes separated by "+"
     *                 (e.g., "eng+deu+fra"). If null or empty, a default whitelist is returned.
     * @return A string containing the combined whitelist of allowed characters for the specified
     * language specification, or the default whitelist if the input is null or empty.
     */
    public static String getWhitelistForLangSpec(String langSpec) {
        if (langSpec == null || langSpec.trim().isEmpty()) return DEFAULT;
        StringBuilder sb = new StringBuilder();
        for (String part : langSpec.split("\\+")) {
            String lang = part.trim();
            if (!lang.isEmpty()) sb.append(getWhitelistForLanguage(lang));
        }
        // cleanup duplicates
        return sb.chars().distinct()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
