package org.unicode.cldr.unittest;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.ibm.icu.impl.Relation;
import com.ibm.icu.impl.Row;
import com.ibm.icu.impl.Row.R2;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ICUUncheckedIOException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.xml.xpath.XPathException;
import org.unicode.cldr.test.ExampleGenerator;
import org.unicode.cldr.util.AttributeValueValidity;
import org.unicode.cldr.util.AttributeValueValidity.MatcherPattern;
import org.unicode.cldr.util.CLDRConfig;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRFile.DraftStatus;
import org.unicode.cldr.util.CLDRPaths;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.Iso639Data;
import org.unicode.cldr.util.Iso639Data.Scope;
import org.unicode.cldr.util.Iso639Data.Type;
import org.unicode.cldr.util.LanguageTagCanonicalizer;
import org.unicode.cldr.util.LanguageTagParser;
import org.unicode.cldr.util.LanguageTagParser.Format;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.LocaleValidator;
import org.unicode.cldr.util.LocaleValidator.AllowedMatch;
import org.unicode.cldr.util.LocaleValidator.AllowedValid;
import org.unicode.cldr.util.NameGetter;
import org.unicode.cldr.util.NameType;
import org.unicode.cldr.util.SimpleFactory;
import org.unicode.cldr.util.SimpleXMLSource;
import org.unicode.cldr.util.StandardCodes;
import org.unicode.cldr.util.StandardCodes.CodeType;
import org.unicode.cldr.util.StandardCodes.LstrType;
import org.unicode.cldr.util.SupplementalDataInfo;
import org.unicode.cldr.util.TransliteratorUtilities;
import org.unicode.cldr.util.XPathExpressionParser;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class TestLocale extends TestFmwkPlus {
    static CLDRConfig testInfo = CLDRConfig.getInstance();
    private static final SupplementalDataInfo SUPPLEMENTAL_DATA_INFO =
            testInfo.getSupplementalDataInfo();
    public static Splitter AT_AND_SEMI = Splitter.on(CharMatcher.anyOf(";@"));

    public static void main(String[] args) {
        new TestLocale().run(args);
    }

    static Set<Type> ALLOWED_LANGUAGE_TYPES =
            EnumSet.of(
                    Type.Ancient,
                    Type.Living,
                    Type.Constructed,
                    Type.Historical,
                    Type.Extinct,
                    Type.Special);
    static Set<Scope> ALLOWED_LANGUAGE_SCOPES =
            EnumSet.of(
                    Scope.Individual,
                    Scope.Macrolanguage,
                    Scope.Special); // , Special, Collection, PrivateUse, Unknown
    static Set<String> ALLOWED_SCRIPTS =
            StandardCodes.make().getGoodAvailableCodes(CodeType.script);
    static Set<String> ALLOWED_REGIONS =
            StandardCodes.make().getGoodAvailableCodes(CodeType.territory);

    /** XPath expression that will find all alias tags */
    static String XPATH_ALIAS_STRING = "//alias";

    public void TestLanguageRegions() {
        Set<String> missingLanguageRegion = new LinkedHashSet<>();
        // TODO This should be derived from metadata: CLDR-11224
        Set<String> knownMultiScriptLanguages =
                new HashSet<>(
                        Arrays.asList(
                                "az", "ff", "bs", "hi", "kk", "ks", "mni", "ms", "pa", "sat", "sd",
                                "shi", "sr", "su", "vai", "uz", "yue", "zh"));
        Set<String> available = testInfo.getCldrFactory().getAvailable();
        LanguageTagParser ltp = new LanguageTagParser();
        Set<String> defaultContents = SUPPLEMENTAL_DATA_INFO.getDefaultContentLocales();
        for (String locale : available) {
            if (!StandardCodes.isLocaleAtLeastBasic(locale)) {
                continue;
            }
            String base = ltp.set(locale).getLanguage();
            String script = ltp.getScript();
            String region = ltp.getRegion();
            if (script.isEmpty()) {
                continue;
            }
            ltp.setRegion("");
            String baseScript = ltp.toString();
            if (!knownMultiScriptLanguages.contains(base)) {
                assertFalse("Should NOT have " + locale, defaultContents.contains(baseScript));
            }
            if (region.isEmpty()) {
                continue;
            }
            ltp.setScript("");
            ltp.setRegion(region);
            String baseRegion = ltp.toString();
            if (knownMultiScriptLanguages.contains(base)) {
                continue;
            }
            if (!missingLanguageRegion.contains(baseRegion)
                    && !assertTrue("Should have " + baseRegion, available.contains(baseRegion))) {
                missingLanguageRegion.add(baseRegion);
            }
        }
    }

    /**
     * Determine whether the file should be checked for aliases; this is currently not done for
     * Keyboard definitions or DTD's
     *
     * @param f the file to check
     * @return
     */
    protected boolean shouldCheckForAliases(File f) {
        if (!f.canRead()) {
            return false;
        }
        String absPath = f.getAbsolutePath();
        return absPath.endsWith("xml")
                && !absPath.contains("dtd")
                && !absPath.contains("keyboard")
                && !absPath.contains("Keyboard");
    }

    /**
     * Check a single file for aliases, on a content level, the only check that is done is that the
     * one for readability.
     *
     * @param localeName - the localename
     * @param file - the file to check
     * @param localesWithAliases - a set of locale strings the files of which contain aliases
     */
    private void checkForAliases(
            final String localeName, File file, final Set<String> localesWithAliases) {
        try {
            if (file.canRead()) {
                XPathExpressionParser parser = new XPathExpressionParser(file);
                parser.iterateThroughNodeSet(
                        XPATH_ALIAS_STRING,
                        new XPathExpressionParser.NodeHandlingInterface() {

                            // Handle gets called for every node of the node set
                            @Override
                            public void handle(Node result) {
                                if (result instanceof Element) {
                                    Element el = (Element) result;
                                    // this node likely has an attribute source
                                    if (el.hasAttributes()) {
                                        String sourceAttr = el.getAttribute("source");
                                        if (sourceAttr != null && !sourceAttr.isEmpty()) {
                                            localesWithAliases.add(localeName);
                                        }
                                    }
                                }
                            }
                        });
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (XPathException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Tests the validity of the file names and of the English localeDisplayName types. Also tests
     * for aliases outside root
     */
    public void TestLocalePartsValidity() {
        LanguageTagParser ltp = new LanguageTagParser();
        final Set<String> localesWithAliases = new HashSet<>();
        for (File file : CLDRConfig.getInstance().getAllCLDRFilesEndingWith(".xml")) {
            String parent = file.getParent();
            if (parent.contains("transform")
                    || parent.contains("bcp47")
                    || parent.contains("supplemental")
                    || parent.contains("validity")
                    || parent.contains(
                            "keyboards/import") // exclude keyboard imports: not locale IDs
                    || parent.contains(
                            "keyboards/test")) { // exclude keyboard test files: not locale IDs
                continue;
            }
            String localeName = file.getName();
            localeName = localeName.substring(0, localeName.length() - 4); // remove
            // .xml
            if (localeName.equals("root") || localeName.equals("_platform")) {
                continue;
            }
            String fileString = file.toString();
            checkLocale(fileString, localeName, ltp);
            // check for aliases
            if (shouldCheckForAliases(file)) {
                checkForAliases(localeName, file, localesWithAliases);
            }
        }
        // we ran through all of them
        if (!localesWithAliases.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\r\n");
            sb.append("The following locales have aliases, but must not: ");
            Iterator<String> lIter = localesWithAliases.iterator();
            while (lIter.hasNext()) {
                sb.append(lIter.next());
                sb.append(" ");
            }
            System.out.println(sb.toString());
        }
        // now check English-resolved
        CLDRFile english = testInfo.getEnglish();
        for (String xpath : english) {
            if (!xpath.startsWith("//ldml/localeDisplayNames/")) {
                continue;
            }
            switch (NameType.fromPath(xpath)) {
                case LANGUAGE:
                    checkLocale("English xpath", NameType.getCode(xpath), ltp);
                    break;
                case SCRIPT:
                    checkScript("English xpath", NameType.getCode(xpath));
                    break;
                case TERRITORY:
                    checkRegion("English xpath", NameType.getCode(xpath));
                    break;
            }
        }
    }

    public void checkLocale(String fileString, String localeName, LanguageTagParser ltp) {
        ltp.set(localeName);
        checkLanguage(fileString, ltp.getLanguage());
        checkScript(fileString, ltp.getScript());
        checkRegion(fileString, ltp.getRegion());
    }

    public void checkRegion(String file, String region) {
        if (!region.isEmpty()
                && !region.equals("AN")
                && !region.equals("XA")
                && !region.equals("XB")) {
            assertRelation(
                    "Region ok? " + region + " in " + file,
                    true,
                    ALLOWED_REGIONS,
                    TestFmwkPlus.CONTAINS,
                    region);
        }
    }

    final MatcherPattern SCRIPT_NON_UNICODE =
            AttributeValueValidity.getMatcherPattern("$scriptNonUnicode");

    public void checkScript(String file, String script) {
        if (!script.isEmpty()) {
            if (!ALLOWED_SCRIPTS.contains(script) && SCRIPT_NON_UNICODE.matches(script, null)) {
                return;
            }
            assertRelation(
                    "Script ok? " + script + " in " + file,
                    true,
                    ALLOWED_SCRIPTS,
                    TestFmwkPlus.CONTAINS,
                    script);
        }
    }

    public void checkLanguage(String file, String language) {
        if (!language.equals("root")) {
            Scope scope = Iso639Data.getScope(language);
            if (assertRelation(
                    "Language ok? " + language + " in " + file,
                    true,
                    ALLOWED_LANGUAGE_SCOPES,
                    TestFmwkPlus.CONTAINS,
                    scope)) {
                Type type = Iso639Data.getType(language);
                assertRelation(
                        "Language ok? " + language + " in " + file,
                        true,
                        ALLOWED_LANGUAGE_TYPES,
                        TestFmwkPlus.CONTAINS,
                        type);
            }
        }
    }

    // Locale exceptions to rule that parentIsRoot != isDefaultContent
    static final Set<String> ROOT_PARENT_DEFAULT_CONTENT_EXCEPTIONS = ImmutableSet.of("hi_Latn");

    public void TestConsistency() {
        LanguageTagParser ltp = new LanguageTagParser();
        SupplementalDataInfo supplementalDataInfo = SUPPLEMENTAL_DATA_INFO;
        Set<String> defaultContentLocales = supplementalDataInfo.getDefaultContentLocales();
        Map<String, String> likelySubtags = supplementalDataInfo.getLikelySubtags();

        for (String locale : testInfo.getCldrFactory().getAvailable()) {
            if (locale.equals("root")) {
                continue;
            }
            ltp.set(locale);
            boolean isDefaultContent = defaultContentLocales.contains(locale);
            boolean hasScript = !ltp.getScript().isEmpty();
            boolean hasRegion = !ltp.getRegion().isEmpty();
            String language = ltp.getLanguage();
            String maximized = likelySubtags.get(language);
            boolean hasLikelySubtag = maximized != null;

            // verify that the parent locales are consistent with the default
            // locales, for scripts
            // that is, if zh-Hant has a parent of root, then it is not the
            // default content locale, and vice versa
            // Locales that are known exceptions to this condition are in
            // ROOT_PARENT_DEFAULT_CONTENT_EXCEPTIONS.

            if (hasScript && !hasRegion) {
                boolean parentIsRoot = "root".equals(LocaleIDParser.getParent(locale));
                if (parentIsRoot == isDefaultContent
                        && !ROOT_PARENT_DEFAULT_CONTENT_EXCEPTIONS.contains(locale)) {
                    errln(
                            "Inconsistency between parentLocales and defaultContents: "
                                    + locale
                                    + (parentIsRoot ? " +" : " -")
                                    + "parentIsRoot"
                                    + (isDefaultContent ? " +" : " -")
                                    + "isDefaultContent");
                }

                // we'd better have a separate likelySubtag
                if (parentIsRoot && !hasLikelySubtag) {
                    errln(
                            "Missing likely subtags for: "
                                    + locale
                                    + " "
                                    + TestInheritance.suggestLikelySubtagFor(locale));
                }
            }

            // verify that likelySubtags has all the languages

            if (!hasScript && !hasRegion) {
                if (!hasLikelySubtag) {
                    errln(
                            "Missing likely subtags for: "
                                    + locale
                                    + " "
                                    + TestInheritance.suggestLikelySubtagFor(locale));
                }
            }
        }
    }

    public void TestCanonicalizer() {
        LanguageTagCanonicalizer canonicalizer = new LanguageTagCanonicalizer();
        String[][] tests = {
            {"iw", "he"},
            {"nb-YU", "nb_RS"},
            {"no-YU", "no_RS"},
            {"nb", "nb"},
            {"no", "no"},
            {"eng-833", "en_IM"},
            {"mo", "ro"},
            {"mo_Cyrl", "ro_Cyrl"},
            {"mo_US", "ro_US"},
            {"mo_Cyrl_US", "ro_Cyrl_US"},
            {"sh", "sr_Latn"},
            {"sh_US", "sr_Latn_US"},
            {"sh_Cyrl", "sr"},
            {"sh_Cyrl_US", "sr_US"},
            {"hy_SU", "hy"},
            {"hy_AM", "hy"},
            {"en_SU", "en_RU"},
            {"rO-cYrl-aQ", "ro_Cyrl_AQ"},
        };
        for (String[] pair : tests) {
            String actual = canonicalizer.transform(pair[0]);
            assertEquals("Canonical", pair[1], actual);
        }
    }

    public void TestBrackets() {
        String[][] tests = {
            {
                NameType.LANGUAGE.toString(),
                "en",
                "Anglish (abc)",
                "en",
                "Anglish [abc]",
                "〖?Anglish [abc]?❬ (U.S. [ghi])❭〗〖?Anglish [abc]?❬ (Latine [def])❭〗〖?Anglish [abc]?❬ (Latine [def], U.S. [ghi])❭〗〖❬Langue: ❭?Anglish (abc)?〗"
            },
            {
                NameType.SCRIPT.toString(),
                "Latn",
                "Latine (def)",
                "en_Latn",
                "Anglish [abc] (Latine [def])",
                "〖❬Anglish [abc] (❭?Latine [def]?❬)❭〗〖❬Anglish [abc] (❭?Latine [def]?❬, U.S. [ghi])❭〗〖❬Scripte: ❭?Latine (def)?〗"
            },
            {
                NameType.TERRITORY.toString(),
                "US",
                "U.S. (ghi)",
                "en_Latn_US",
                "Anglish [abc] (Latine [def], U.S. [ghi])",
                "〖❬Anglish [abc] (❭?U.S. [ghi]?❬)❭〗〖❬Anglish [abc] (Latine [def], ❭?U.S. [ghi]?❬)❭〗〖❬Territorie: ❭?U.S. (ghi)?〗"
            },
            {NameType.NONE.toString(), null, null, "en_US", "Anglish [abc] (U.S. [ghi])", null},
            {
                NameType.VARIANT.toString(),
                "FOOBAR",
                "foo (jkl)",
                "en_foobar",
                "Anglish [abc] (foo [jkl])",
                null
            },
            {
                NameType.KEY.toString(),
                "co",
                "sort (mno)",
                "en_foobar@co=FOO",
                "Anglish [abc] (foo [jkl], sort [mno]=foo)",
                null
            },
            {
                NameType.KEY_TYPE.toString(),
                "co|fii",
                "sortfii (mno)",
                "en_foobar@co=FII",
                "Anglish [abc] (foo [jkl], sortfii [mno])",
                null
            },
        };
        // load up a dummy source
        SimpleXMLSource dxs = new SimpleXMLSource("xx");
        for (String[] row : tests) {
            NameType nameType = NameType.valueOf(row[0]);
            if (nameType == NameType.NONE) {
                continue;
            }
            String path = nameType.getKeyPath(row[1]);
            dxs.putValueAtDPath(path, row[2]);
        }
        // create a cldrfile from it and test
        SimpleXMLSource root = new SimpleXMLSource("root");
        root.putValueAtDPath(
                "//ldml/localeDisplayNames/localeDisplayPattern/localePattern", "{0} ({1})");
        root.putValueAtDPath(
                "//ldml/localeDisplayNames/localeDisplayPattern/localeSeparator", "{0}, {1}");
        root.putValueAtDPath(
                "//ldml/localeDisplayNames/codePatterns/codePattern[@type=\"language\"]",
                "Langue: {0}");
        root.putValueAtDPath(
                "//ldml/localeDisplayNames/codePatterns/codePattern[@type=\"script\"]",
                "Scripte: {0}");
        root.putValueAtDPath(
                "//ldml/localeDisplayNames/codePatterns/codePattern[@type=\"territory\"]",
                "Territorie: {0}");
        CLDRFile f = new CLDRFile(dxs, root);
        ExampleGenerator eg = new ExampleGenerator(f, testInfo.getEnglish());
        NameGetter nameGetter = f.nameGetter();
        for (String[] row : tests) {
            NameType nameType = NameType.valueOf(row[0]);
            if (nameType != NameType.NONE) {
                String standAlone = nameGetter.getNameFromTypeEnumCode(nameType, row[1]);
                logln(nameType + ": " + standAlone);
                assertEquals("stand-alone " + row[3], row[2], standAlone);

                if (row[5] != null) {
                    String path = nameType.getKeyPath(row[1]);
                    String example = eg.getExampleHtml(path, "?" + row[2] + "?");
                    assertEquals("example " + row[3], row[5], ExampleGenerator.simplify(example));
                }
            }
            String displayName =
                    nameGetter.getNameFromIdentifierEtc(
                            row[3],
                            NameGetter.NameOpt.COMPOUND_ONLY,
                            "{0}={1}",
                            "{0} ({1})",
                            "{0}, {1}");
            assertEquals("locale " + row[3], row[4], displayName);
        }
    }

    public void TestLocaleNamePattern() {
        NameGetter englishNameGetter = testInfo.getEnglish().nameGetter();
        assertEquals("Locale name", "Chinese", englishNameGetter.getNameFromIdentifier("zh"));
        assertEquals(
                "Locale name",
                "Chinese (United States)",
                englishNameGetter.getNameFromIdentifier("zh-US"));
        assertEquals(
                "Locale name",
                "Chinese (Arabic, United States)",
                englishNameGetter.getNameFromIdentifier("zh-Arab-US"));
        CLDRFile japanese = testInfo.getCLDRFile("ja", true);
        NameGetter japaneseNameGetter = japanese.nameGetter();
        assertEquals("Locale name", "中国語", japaneseNameGetter.getNameFromIdentifier("zh"));
        assertEquals(
                "Locale name", "中国語 (アメリカ合衆国)", japaneseNameGetter.getNameFromIdentifier("zh-US"));
        assertEquals(
                "Locale name",
                "中国語 (アラビア文字\u3001アメリカ合衆国)",
                japaneseNameGetter.getNameFromIdentifier("zh-Arab-US"));
    }

    public void TestLocaleDisplay() {
        if (!isVerbose()) {
            warnln("\nUse -v to get samples for tests");
        }
        String fileName = CLDRPaths.TEST_DATA + "localeIdentifiers/localeDisplayName.txt";
        LanguageTagCanonicalizer canonicalizer = new LanguageTagCanonicalizer(LstrType.redundant);

        CLDRFile cldrFile = null;
        NameGetter.NameOpt nameOpt = NameGetter.NameOpt.COMPOUND_ONLY;
        StringBuilder formattedExamplesForSpec = new StringBuilder("\nformattedExamplesForSpec\n");
        File[] paths = {
            new File(CLDRPaths.MAIN_DIRECTORY), new File(CLDRPaths.SUBDIVISIONS_DIRECTORY),
        };
        Factory factory = SimpleFactory.make(paths, ".*");
        Set<String> seen = new HashSet<>();

        try {
            for (String line : Files.readLines(new File(fileName), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                if (line.startsWith("@")) {
                    String[] parts = line.split("=");
                    switch (parts[0]) {
                        case "@locale":
                            cldrFile = factory.make(parts[1], true, DraftStatus.contributed);
                            break;
                        case "@languageDisplay":
                            switch (parts[1]) {
                                case "standard":
                                    nameOpt = NameGetter.NameOpt.COMPOUND_ONLY;
                                    break;
                                case "dialect":
                                    nameOpt = NameGetter.NameOpt.DEFAULT;
                                    break;
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("Bad line: " + line);
                    }
                    continue;
                }
                int semi = line.indexOf(';');
                String localeId = line;
                String expected = "";
                if (semi >= 0) {
                    localeId = line.substring(0, semi).trim();
                    expected = line.substring(semi + 1).trim();
                }
                LanguageTagParser ltp = new LanguageTagParser().set(localeId);
                seen.add(localeId);

                //                ULocale forComparison = ULocale.forLanguageTag(localeId);
                //                String uLocaleAsBcp47 = forComparison.toLanguageTag();
                //                assertEquals("ICU roundtrips", localeId, uLocaleAsBcp47);

                //                String bcp47 = ltp.toString(OutputOption.BCP47);
                //                String icuFormat = ltp.toString(OutputOption.ICU);

                //                // check that the icuFormat is ok except for order
                //                Set<String> icuComponents = new
                // TreeSet<>(AT_AND_SEMI.splitToList(forComparison.toString().toLowerCase(Locale.ROOT)));
                //                Set<String> icuFormatComponents = new
                // TreeSet<>(AT_AND_SEMI.splitToList(icuFormat.toLowerCase(Locale.ROOT)));
                //                assertEquals("ICU vs LTP", icuComponents, icuFormatComponents);

                //                // check that the icuFormat roundtrips
                //                LanguageTagParser ltp2 = new LanguageTagParser()
                //                    .set(icuFormat);
                //                String roundTripId = ltp2.toString(OutputOption.BCP47);

                //                // check that the format roundtrips
                //                assertEquals("LTP(BCP47)=>ICU=>BCP47", bcp47, roundTripId);

                canonicalizer.transform(ltp);
                String name = cldrFile.nameGetter().getNameFromParserOpt(ltp, nameOpt);
                if (assertEquals(cldrFile.getLocaleID() + "; " + localeId, expected, name)) {
                    formattedExamplesForSpec
                            .append("<tr><td>")
                            .append(TransliteratorUtilities.toHTML.transform(localeId))
                            .append("</td><td>")
                            .append(TransliteratorUtilities.toHTML.transform(expected))
                            .append("</td><tr>\n");
                }
            }
            if (isVerbose()) {
                System.out.println(formattedExamplesForSpec.toString());
            }
        } catch (IOException e) {
            throw new ICUUncheckedIOException(e);
        }
        // generate forms
        Map<R2<String, String>, String> deprecatedMap = SUPPLEMENTAL_DATA_INFO.getBcp47Deprecated();
        keyLoop:
        for (Entry<String, Set<String>> keyValues :
                SUPPLEMENTAL_DATA_INFO.getBcp47Keys().keyValuesSet()) {
            String key = keyValues.getKey();
            if ("true".equals(deprecatedMap.get(Row.of(key, "")))) {
                continue;
            }
            String localeBase = "en-" + (LanguageTagParser.isTKey(key) ? "t-" : "u-") + key + "-";
            // abbreviate some values
            switch (key) {
                case "cu":
                    showName(cldrFile, seen, localeBase, "eur", "jpy", "usd", "chf");
                    continue keyLoop;
                case "tz":
                    showName(cldrFile, seen, localeBase, "uslax", "gblon", "chzrh");
                    continue keyLoop;
                case "dx":
                    // skip for now, probably need to fix something in CLDRFile
                    continue keyLoop;
            }
            for (String value : keyValues.getValue()) {
                if ("true".equals(deprecatedMap.get(Row.of(key, value)))) {
                    continue;
                }
                if (isSpecialBcp47Value(value)) {
                    showName(cldrFile, seen, localeBase, getSpecialBcp47ValueSamples(value));
                } else {
                    showName(cldrFile, seen, localeBase, value);
                }
            }
        }
    }

    private void showName(
            CLDRFile cldrFile,
            Set<String> skipLocales,
            String localeBase,
            Collection<String> samples) {
        for (String sample : samples) {
            showName(cldrFile, skipLocales, localeBase, sample);
        }
    }

    private void showName(
            CLDRFile cldrFile, Set<String> skipLocales, String localeBase, String... samples) {
        for (String sample : samples) {
            showName(cldrFile, skipLocales, localeBase, sample);
        }
    }

    static final UnicodeSet LOCALIZED = new UnicodeSet("[A-Z€$¥${foobar2}]");

    private void showName(
            CLDRFile cldrFile, Set<String> skipLocales, String localeBase, String value) {
        String locale = localeBase + value;
        if (skipLocales.contains(locale)) {
            return;
        }
        if (locale.equals("en-t-d0-accents")) {
            int debug = 0;
        }
        String name =
                cldrFile.nameGetter()
                        .getNameFromIdentifierOptAlt(
                                locale, NameGetter.NameOpt.COMPOUND_ONLY, null);
        if (isVerbose()) {
            System.out.println(locale + "; " + name);
        }
        // rough check of name to ensure
        int parenPos = name.indexOf('(');
        if (parenPos > 0 && cldrFile.getLocaleID().equals("en")) {
            for (String part1 : name.substring(parenPos).split(",")) {
                String[] part2s = part1.split(":");
                if (part2s.length > 1 && !LOCALIZED.containsSome(part2s[1])) {
                    errln(locale + "; " + name);
                }
            }
        }
    }

    public void TestExtendedLanguage() {
        NameGetter englishNameGetter = testInfo.getEnglish().nameGetter();
        assertEquals(
                "Extended language translation",
                "Simplified Chinese",
                englishNameGetter.getNameFromIdentifier("zh_Hans"));
        assertEquals(
                "Extended language translation",
                "Simplified Chinese (Singapore)",
                englishNameGetter.getNameFromIdentifier("zh_Hans_SG"));
        assertEquals(
                "Extended language translation",
                "American English",
                englishNameGetter.getNameFromIdentifier("en-US"));
        assertEquals(
                "Extended language translation",
                "American English (Arabic)",
                englishNameGetter.getNameFromIdentifier("en-Arab-US"));
    }

    public void testAllVariants() {
        Relation<String, String> extensionToKeys = SUPPLEMENTAL_DATA_INFO.getBcp47Extension2Keys();
        Relation<String, String> keyToValues = SUPPLEMENTAL_DATA_INFO.getBcp47Keys();
        Map<R2<String, String>, String> extKeyToDeprecated =
                SUPPLEMENTAL_DATA_INFO.getBcp47Deprecated();
        Map<String, String> keyToValueType = SUPPLEMENTAL_DATA_INFO.getBcp47ValueType();
        LanguageTagParser ltp = new LanguageTagParser();
        String lastKey = "";
        CLDRFile english = testInfo.getEnglish();

        String extName = english.getKeyName("t"); // special case where we need name
        assertNotNull("Name of extension: " + "t", extName);

        Set<String> allowedNoKeyValueNameSet = ImmutableSet.of("cu", "tz");

        main:
        for (Entry<String, String> entry : extensionToKeys.entrySet()) {
            String extension = entry.getKey();
            String key = entry.getValue();

            String dep = extKeyToDeprecated.get(Row.of(key, ""));
            if ("true".equals(dep)) {
                logln("# Deprecated: " + Row.of(extension, key));
                // # MULTIPLE: [u, vt, CODEPOINTS]
                continue;
            }

            boolean allowedNoKeyValueName = allowedNoKeyValueNameSet.contains(key);

            String kname = english.getKeyName(key);
            assertNotNull("Name of key: " + key, kname);

            // System.out.println("\n#Key: " + key + (kname == null ? "" : " (" + kname + ")"));

            //            if (extension.equals("t")) {
            //                System.out.println("skipping -t- for now: " + key);
            //                continue;
            //            }
            boolean isMultiple =
                    "multiple"
                            .equals(
                                    keyToValueType.get(
                                            key)); // single | multiple | incremental | any

            Set<String> values = keyToValues.get(key);
            String lastValue = null;
            int count = 0;
            for (String value : values) {

                dep = extKeyToDeprecated.get(Row.of(key, value));
                if ("true".equals(dep)) {
                    logln("# Deprecated: " + Row.of(extension, key));
                    // # MULTIPLE: [u, vt, CODEPOINTS]
                    continue;
                }

                boolean specialValue = isSpecialBcp47Value(value);

                String kvname = english.getKeyValueName(key, value);
                if (!allowedNoKeyValueName && !specialValue) {
                    assertNotNull("Name of <" + key + "," + value + ">", kvname);
                } else {
                    // logln("Name of <" + key + "," + value + ">" + " = " + kvname);
                }

                // System.out.println("\n#Value: " + value + (kname == null ? "" : " (" + kvname +
                // ")"));

                String gorp =
                        key.equals(lastKey)
                                ? ""
                                : (key.equals("t") ? "-u-ca-persian" : "-t-hi")
                                        + "-a-AA-v-VV-y-YY-x-foobar";

                lastKey = key;
                if (++count > 4) {
                    continue;
                }

                if (specialValue) {
                    Set<String> valuesSet = getSpecialBcp47ValueSamples(value);
                    showItem(
                            ltp,
                            extension,
                            key,
                            gorp,
                            valuesSet.toArray(new String[valuesSet.size()]));

                    continue;
                }
                showItem(ltp, extension, key, gorp, value);
                if (isMultiple) {
                    if (lastValue != null) {
                        showItem(ltp, extension, key, gorp, value, lastValue);
                    } else {
                        lastValue = value;
                    }
                }
            }
        }
    }

    public static Set<String> getSpecialBcp47ValueSamples(String value) {
        Set<String> valuesSet;
        switch (value) {
            case "PRIVATE_USE": // [t, x0, PRIVATE_USE]
                valuesSet = ImmutableSet.of("foobar2");
                break;
            case "REORDER_CODE": // [u, kr, REORDER_CODE]
                valuesSet = ImmutableSet.of("arab", "digit-deva-latn");
                break;
            case "SCRIPT_CODE": // [u, dx, SCRIPT_CODE]
                valuesSet = ImmutableSet.of("thai", "thai-laoo");
                break;
            case "RG_KEY_VALUE": // [u, rg, RG_KEY_VALUE]
                valuesSet = ImmutableSet.of("ustx", "gbeng");
                break;
            case "SUBDIVISION_CODE": // [u, sd, SUBDIVISION_CODE]
                valuesSet = ImmutableSet.of("usca", "gbsct", "frnor");
                break;
            default:
                throw new IllegalArgumentException();
        }
        return valuesSet;
    }

    public static boolean isSpecialBcp47Value(String value) {
        return value.equals(value.toUpperCase(Locale.ROOT));
    }

    private void showItem(
            LanguageTagParser ltp, String extension, String key, String gorp, String... values) {

        String locale =
                "en-GB-"
                        + extension
                        + (extension.equals("t") ? "-hi" : "")
                        + "-"
                        + key
                        + "-"
                        + String.join("-", values)
                        + gorp;
        ltp.set(locale);

        logln(
                ltp.toString(Format.bcp47)
                        + " == "
                        + ltp.toString(Format.icu)
                        + "\n\t\tstructure:\t"
                        + ltp.toString(Format.structure));
        try {
            String name = testInfo.getEnglish().nameGetter().getNameFromIdentifier(locale);
            logln("\tname:\t" + name);
        } catch (Exception e) {
            errln("Name for " + locale + "; " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void testLanguageTagParserIsValid() {
        String[][] tests = {
            {"en_u_ca-buddhist", ""},
            {"en_u_ca-islamicc", "Disallowed extension=ca=islamicc, status=deprecated"},
            {
                "en_t_foobar-QAAA",
                "Disallowed language=foobar, status=invalid; Disallowed script=Qaaa, status=reserved"
            },
            {"en_u_ca-foo", "Disallowed extension=ca=foo, status=invalid"},
            {"en_Latn_US", ""},
            {"en_Latn_EZ", "Disallowed region=EZ, status=macroregion"},
            {"fri", "Disallowed language=fri, status=invalid"},
            {"iw", "Disallowed language=iw, status=deprecated"},
            {"en_Qaaa", "Disallowed script=Qaaa, status=reserved"},
            {"en_SU", "Disallowed region=SU, status=deprecated"},
            {"en_fonipa", ""},
            {
                "en_fonipa3-fonipa4",
                "Disallowed variant=fonipa3, status=invalid; Disallowed variant=fonipa4, status=invalid"
            },
            {"en_t_en", ""},
            {"en_t_h0_boo_bar", "Disallowed extension=h0=boo-bar, status=invalid"},
            {"en_x_foo2_foo3", "Disallowed extension=x, status=private_use"},
            {
                "fartoolong",
                "Disallowed extension=x, status=private_use; Illegal language tag: fartoolong"
            },
        };

        LanguageTagParser ltp = new LanguageTagParser();
        Set<String> errors = new LinkedHashSet<>();
        for (String[] test : tests) {
            String localeId = test[0];
            String expected = test[1];
            try {
                LocaleValidator.isValid(ltp.set(localeId), null, errors);
            } catch (Exception e) {
                errors.add(e.getMessage());
            }
            assertEquals(localeId, expected, Joiner.on("; ").join(errors));
        }

        // likely subtags

        Map<String, String> exceptions =
                Map.of(
                        //                "und_QO", "Disallowed region=QO, status=macroregion"
                        );

        for (Entry<String, String> entry : SUPPLEMENTAL_DATA_INFO.getLikelySubtags().entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();

            String expected = CldrUtility.ifNull(exceptions.get(key), "");
            LocaleValidator.isValid(ltp.set(key), LocaleValidator.ALLOW_IN_LIKELY, errors);
            assertEquals(key, expected, Joiner.on("; ").join(errors));
            if (!expected.isEmpty()) {
                warnln("Likely subtags, skipping " + ltp + ", " + expected);
            }

            LocaleValidator.isValid(ltp.set(value), LocaleValidator.ALLOW_IN_LIKELY, errors);
            assertEquals(value, "", Joiner.on("; ").join(errors));
        }

        Set<String> exceptionSet = Set.of("und_AQ", "und_GS", "und_HM", "und_BV", "und_CP");

        for (String language : SUPPLEMENTAL_DATA_INFO.getLanguagesForTerritoriesPopulationData()) {
            ltp.set(language);
            for (String territory :
                    SUPPLEMENTAL_DATA_INFO.getTerritoriesForPopulationData(language)) {
                LocaleValidator.isValid(ltp.setRegion(territory), null, errors);
                String expected =
                        exceptionSet.contains(ltp.toString())
                                ? "Disallowed language=und, status=unknown"
                                : "";
                assertEquals(ltp.toString(), expected, Joiner.on("; ").join(errors));
                if (!expected.isEmpty()) {
                    warnln("Language population data, skipping " + ltp + ", " + expected);
                }
            }
        }

        AllowedValid bcp47 =
                new AllowedValid(
                        null,
                        LstrType.extension,
                        new AllowedMatch("ca", "islamicc"),
                        LstrType.extension,
                        new AllowedMatch("co", "direct|reformed|gb2312|big5han"),
                        LstrType.extension,
                        new AllowedMatch("rg", "unknown"),
                        LstrType.extension,
                        new AllowedMatch(
                                "tz",
                                "aqams|aukns|caffs|camtr|canpg|capnt|cathu|cayzf|cnckg|cnhrb|cnkhg|gaza|mncoq|mxstis|uaozh|uauzh|umjon|usnavajo|est5edt|cst6cdt|mst7mdt|pst8pdt"));

        for (Entry<String, String> entry :
                SUPPLEMENTAL_DATA_INFO.getBcp47Extension2Keys().entrySet()) {
            String extension = entry.getKey();
            String key = entry.getValue();
            for (String value : SUPPLEMENTAL_DATA_INFO.getBcp47Keys().get(key)) {
                String expected = "";
                switch (value) {
                    case "PRIVATE_USE": // x0, valueType="any"
                        value = "foobar-snafu";
                        expected = "Disallowed extension=x0=foobar-snafu, status=deprecated";
                        break;
                    case "SCRIPT_CODE": // dx, valueType="multiple"
                        value = "latn-grek";
                        break;
                    case "REORDER_CODE": // kr, valueType="multiple"
                        value = "latn-grek-symbol";
                        break;
                    case "RG_KEY_VALUE": // rg, single
                        value = "dezzzz";
                        break;
                    case "SUBDIVISION_CODE": // sd, single
                        value = "usca";
                        break;
                    case "CODEPOINTS": // vt, valueType="multiple"
                        value = "00A0-300b";
                        break;
                }
                final String composite = "en-" + extension + "-" + key + "-" + value;
                try {
                    ltp.set(composite); // clears other fields
                    LocaleValidator.isValid(ltp, bcp47, errors);
                    if (!assertEquals(composite, expected, Joiner.on("; ").join(errors))) {
                        LocaleValidator.isValid(ltp, bcp47, errors);
                    }
                } catch (Exception e) {
                    assertEquals(composite, "", e.getMessage());
                }
            }
        }
    }
}
