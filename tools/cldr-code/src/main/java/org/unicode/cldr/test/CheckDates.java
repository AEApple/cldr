package org.unicode.cldr.test;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.ibm.icu.impl.Relation;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.DateIntervalInfo;
import com.ibm.icu.text.DateIntervalInfo.PatternInfo;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.text.DateTimePatternGenerator.VariableField;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.Output;
import com.ibm.icu.util.ULocale;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.unicode.cldr.test.CheckCLDR.CheckStatus.Subtype;
import org.unicode.cldr.tool.LikelySubtags;
import org.unicode.cldr.util.ApproximateWidth;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRFile.Status;
import org.unicode.cldr.util.CLDRLocale;
import org.unicode.cldr.util.CLDRURLS;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.DateTimeCanonicalizer.DateTimePatternType;
import org.unicode.cldr.util.DayPeriodInfo;
import org.unicode.cldr.util.DayPeriodInfo.DayPeriod;
import org.unicode.cldr.util.DayPeriodInfo.Type;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.ICUServiceBuilder;
import org.unicode.cldr.util.Level;
import org.unicode.cldr.util.LocaleIDParser;
import org.unicode.cldr.util.LocaleNames;
import org.unicode.cldr.util.LogicalGrouping;
import org.unicode.cldr.util.PathHeader;
import org.unicode.cldr.util.PatternCache;
import org.unicode.cldr.util.PreferredAndAllowedHour;
import org.unicode.cldr.util.RegexUtilities;
import org.unicode.cldr.util.StandardCodes;
import org.unicode.cldr.util.SupplementalDataInfo;
import org.unicode.cldr.util.XPathParts;

public class CheckDates extends FactoryCheckCLDR {
    private static final boolean DEBUG = false;
    private static final boolean DISABLE_DATE_ORDER = true;

    static boolean GREGORIAN_ONLY = CldrUtility.getProperty("GREGORIAN", false);
    private static final Set<String> CALENDARS_FOR_CORES = Set.of("gregorian", "iso8601");

    ICUServiceBuilder icuServiceBuilder = new ICUServiceBuilder();
    DateTimePatternGenerator.FormatParser formatParser =
            new DateTimePatternGenerator.FormatParser();
    DateTimePatternGenerator dateTimePatternGenerator = DateTimePatternGenerator.getEmptyInstance();
    private CoverageLevel2 coverageLevel;
    private final SupplementalDataInfo sdi = SupplementalDataInfo.getInstance();
    // Ordered list of this CLDRFile and parent CLDRFiles up to root
    List<CLDRFile> parentCLDRFiles = new ArrayList<>();
    // Map from calendar type (i.e. "gregorian", "generic", "chinese") to DateTimePatternGenerator
    // instance for that type
    Map<String, DateTimePatternGenerator> dtpgForType = new HashMap<>();

    // Use the width of the character "0" as the basic unit for checking widths
    // It's not perfect, but I'm not sure that anything can be. This helps us
    // weed out some false positives in width checking, like 10月 vs. 十月
    // in Chinese, which although technically longer, shouldn't trigger an
    // error.
    private static final int REFCHAR = ApproximateWidth.getWidth("0");

    private Level requiredLevel;
    private String language;
    private String territory;

    private DayPeriodInfo dateFormatInfoFormat;

    private static final String DECIMAL_XPATH =
            "//ldml/numbers/symbols[@numberSystem='latn']/decimal";
    private static final Pattern HOUR_SYMBOL = PatternCache.get("H{1,2}");
    private static final Pattern MINUTE_SYMBOL = PatternCache.get("mm");
    private static final Pattern YEAR_FIELDS = PatternCache.get("(y|Y|u|U|r){1,5}");

    private static final String CALENDAR_ID_PREFIX = "/calendar[@type=\"";

    private static final String TIME_FORMAT_CHECK_PATH =
            "//ldml/dates/calendars/calendar[@type=\"gregorian\"]/timeFormats/timeFormatLength[@type=\"short\"]/timeFormat[@type=\"standard\"]/pattern[@type=\"standard\"]";

    // The following calendar symbol sets need not have distinct values
    // "/months/monthContext[@type=\"format\"]/monthWidth[@type=\"narrow\"]/month",
    // "/months/monthContext[@type=\"stand-alone\"]/monthWidth[@type=\"abbreviated\"]/month",
    // "/months/monthContext[@type=\"stand-alone\"]/monthWidth[@type=\"narrow\"]/month",
    // "/months/monthContext[@type=\"stand-alone\"]/monthWidth[@type=\"wide\"]/month",
    // "/days/dayContext[@type=\"format\"]/dayWidth[@type=\"narrow\"]/day",
    // "/days/dayContext[@type=\"stand-alone\"]/dayWidth[@type=\"abbreviated\"]/day",
    // "/days/dayContext[@type=\"stand-alone\"]/dayWidth[@type=\"narrow\"]/day",
    // "/days/dayContext[@type=\"stand-alone\"]/dayWidth[@type=\"wide\"]/day",
    // "/quarters/quarterContext[@type=\"format\"]/quarterWidth[@type=\"narrow\"]/quarter",
    // "/quarters/quarterContext[@type=\"stand-alone\"]/quarterWidth[@type=\"abbreviated\"]/quarter",
    // "/quarters/quarterContext[@type=\"stand-alone\"]/quarterWidth[@type=\"narrow\"]/quarter",
    // "/quarters/quarterContext[@type=\"stand-alone\"]/quarterWidth[@type=\"wide\"]/quarter",

    // The above are followed by trailing pieces such as
    // "[@type=\"am\"]",
    // "[@type=\"sun\"]",
    // "[@type=\"0\"]",
    // "[@type=\"1\"]",
    // "[@type=\"12\"]",

    public CheckDates(Factory factory) {
        super(factory);
    }

    @Override
    public CheckCLDR handleSetCldrFileToCheck(
            CLDRFile cldrFileToCheck, Options options, List<CheckStatus> possibleErrors) {
        if (cldrFileToCheck == null) return this;
        super.handleSetCldrFileToCheck(cldrFileToCheck, options, possibleErrors);

        icuServiceBuilder.setCldrFile(getResolvedCldrFileToCheck());
        // the following is a hack to work around a bug in ICU4J (the snapshot, not the released
        // version).
        try {
            bi = BreakIterator.getCharacterInstance(new ULocale(cldrFileToCheck.getLocaleID()));
        } catch (RuntimeException e) {
            bi = BreakIterator.getCharacterInstance(new ULocale(""));
        }
        CLDRFile resolved = getResolvedCldrFileToCheck();
        flexInfo = new FlexibleDateFromCLDR(); // ought to just clear(), but not available.
        flexInfo.set(resolved);

        // load decimal path specially
        String decimal = resolved.getWinningValue(DECIMAL_XPATH);
        if (decimal != null) {
            flexInfo.checkFlexibles(DECIMAL_XPATH, decimal, DECIMAL_XPATH);
        }

        String localeID = cldrFileToCheck.getLocaleID();
        LocaleIDParser lp = new LocaleIDParser();
        territory = lp.set(localeID).getRegion();
        language = lp.getLanguage();
        if (territory == null || territory.isEmpty()) {
            if (language.equals("root")) {
                territory = "001";
            } else {
                CLDRLocale loc = CLDRLocale.getInstance(localeID);
                CLDRLocale defContent = sdi.getDefaultContentFromBase(loc);
                if (defContent == null) {
                    territory = "001";
                } else {
                    territory = defContent.getCountry();
                }
                // Set territory for 12/24 hour clock to Egypt (12 hr) for ar_001
                // instead of 24 hour (exception).
                if ("001".equals(territory) && "ar".equals(language)) {
                    territory = "EG";
                }
            }
        }
        coverageLevel = CoverageLevel2.getInstance(sdi, localeID);
        requiredLevel = options.getRequiredLevel(localeID);

        // load gregorian appendItems
        for (Iterator<String> it =
                        resolved.iterator("//ldml/dates/calendars/calendar[@type=\"gregorian\"]");
                it.hasNext(); ) {
            String path = it.next();
            String value = resolved.getWinningValue(path);
            String fullPath = resolved.getFullXPath(path);
            try {
                flexInfo.checkFlexibles(path, value, fullPath);
            } catch (Exception e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                final String message = e.getMessage();
                CheckStatus item =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(
                                        message.contains("Conflicting fields")
                                                ? Subtype.dateSymbolCollision
                                                : Subtype.internalError)
                                .setMessage(message);
                possibleErrors.add(item);
            }
            // possibleErrors.add(flexInfo.getFailurePath(path));
        }
        redundants.clear();
        /*
         * TODO: NullPointerException may be thrown in ICU here during cldr-unittest TestAll
         */
        flexInfo.getRedundants(redundants);

        if (!DISABLE_DATE_ORDER) {
            pathsWithConflictingOrder2sample =
                    DateOrder.getOrderingInfo(cldrFileToCheck, resolved, flexInfo.fp);
            if (pathsWithConflictingOrder2sample == null) {
                CheckStatus item =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.internalError)
                                .setMessage("DateOrder.getOrderingInfo fails");
                possibleErrors.add(item);
            }
        }

        dateFormatInfoFormat = sdi.getDayPeriods(Type.format, cldrFileToCheck.getLocaleID());

        // Make new list of parent CLDRFiles
        parentCLDRFiles.clear();
        parentCLDRFiles.add(cldrFileToCheck);
        while ((localeID = LocaleIDParser.getParent(localeID)) != null) {
            CLDRFile resolvedParentCLDRFile = getFactory().make(localeID, true);
            parentCLDRFiles.add(resolvedParentCLDRFile);
        }
        // Clear out map of DateTimePatternGenerators for calendarType
        dtpgForType.clear();

        return this;
    }

    Map<String, Map<DateOrder, String>> pathsWithConflictingOrder2sample;

    /**
     * hour+minute, hour+minute+second (12 & 24) year+month, year+month+day (numeric & string)
     * month+day (numeric & string) year+quarter
     */
    BreakIterator bi;

    FlexibleDateFromCLDR flexInfo;
    Collection<String> redundants = new HashSet<>();
    Status status = new Status();

    private String stripPrefix(String s) {
        if (s != null) {
            int prefEnd = s.lastIndexOf(" ");
            if (prefEnd < 0 || prefEnd >= 3) {
                prefEnd = s.lastIndexOf("\u2019"); // as in d’
            }
            if (prefEnd >= 0 && prefEnd < 3) {
                return s.substring(prefEnd + 1);
            }
        }
        return s;
    }

    @Override
    public CheckCLDR handleCheck(
            String path, String fullPath, String value, Options options, List<CheckStatus> result) {

        if (fullPath == null) {
            return this; // skip paths that we don't have
        }

        if (value == null) {
            return this;
        }

        if (!path.contains("/dates") || path.endsWith("/default") || path.endsWith("/alias")) {
            return this;
        }

        if (!accept(result)) return this;

        if (TIME_FORMAT_CHECK_PATH.equals(fullPath)) {
            checkTimeFormatMatchesRegion(value, result);
        }

        String sourceLocale = getCldrFileToCheck().getSourceLocaleID(path, status);

        if (!path.equals(status.pathWhereFound)
                || !sourceLocale.equals(getCldrFileToCheck().getLocaleID())) {
            return this;
        }

        if (pathsWithConflictingOrder2sample != null) {
            Map<DateOrder, String> problem = pathsWithConflictingOrder2sample.get(path);
            if (problem != null) {
                CheckStatus item =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.warningType)
                                .setSubtype(Subtype.incorrectDatePattern)
                                .setMessage(
                                        "The ordering of date fields is inconsistent with others: {0}",
                                        getValues(getResolvedCldrFileToCheck(), problem.values()));
                result.add(item);
            }
        }

        String errorMessage = checkIso8601(path, value);
        if (errorMessage != null) {
            CheckStatus item =
                    new CheckStatus()
                            .setCause(this)
                            .setMainType(CheckStatus.errorType)
                            .setSubtype(Subtype.incorrectDatePattern)
                            .setMessage(errorMessage);
            result.add(item);
        }

        try {
            if (path.contains("[@type=\"abbreviated\"]")) {
                // ensures abbreviated <= wide for quarters, months, days, dayPeriods
                String pathToWide = path.replace("[@type=\"abbreviated\"]", "[@type=\"wide\"]");
                String wideValue = getCldrFileToCheck().getWinningValueWithBailey(pathToWide);
                if (wideValue != null && isTooMuchWiderThan(value, wideValue)) {
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(errorOrIfBuildWarning())
                                    .setSubtype(Subtype.abbreviatedDateFieldTooWide)
                                    .setMessage(
                                            "Abbreviated value \"{0}\" can't be longer than the corresponding wide value \"{1}\"",
                                            value, wideValue);
                    result.add(item);
                }
                Set<String> grouping = LogicalGrouping.getPaths(getCldrFileToCheck(), path);
                if (grouping != null) {
                    for (String lgPath : grouping) {
                        String lgPathValue = getCldrFileToCheck().getWinningValueWithBailey(lgPath);
                        if (lgPathValue == null) {
                            continue;
                        }
                        String lgPathToWide =
                                lgPath.replace("[@type=\"abbreviated\"]", "[@type=\"wide\"]");
                        String lgPathWideValue =
                                getCldrFileToCheck().getWinningValueWithBailey(lgPathToWide);
                        // This helps us get around things like "de març" vs. "març" in Catalan
                        String thisValueStripped = stripPrefix(value);
                        String wideValueStripped = stripPrefix(wideValue);
                        String lgPathValueStripped = stripPrefix(lgPathValue);
                        String lgPathWideValueStripped = stripPrefix(lgPathWideValue);
                        boolean thisPathHasPeriod = value.contains(".");
                        boolean lgPathHasPeriod = lgPathValue.contains(".");
                        if (!thisValueStripped.equalsIgnoreCase(wideValueStripped)
                                && !lgPathValueStripped.equalsIgnoreCase(lgPathWideValueStripped)
                                && thisPathHasPeriod != lgPathHasPeriod) {
                            CheckStatus.Type et = CheckStatus.errorType;
                            if (path.contains("dayPeriod")) {
                                et = CheckStatus.warningType;
                            }
                            CheckStatus item =
                                    new CheckStatus()
                                            .setCause(this)
                                            .setMainType(et)
                                            .setSubtype(Subtype.inconsistentPeriods)
                                            .setMessage(
                                                    "Inconsistent use of periods in abbreviations for this section.");
                            result.add(item);
                            break;
                        }
                    }
                }
            } else if (path.contains("[@type=\"narrow\"]")) {
                // ensures narrow <= abbreviated for quarters, months, days, dayPeriods
                String pathToAbbr = path.replace("[@type=\"narrow\"]", "[@type=\"abbreviated\"]");
                String abbrValue = getCldrFileToCheck().getWinningValueWithBailey(pathToAbbr);
                if (abbrValue != null && isTooMuchWiderThan(value, abbrValue)) {
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(
                                            CheckStatus.warningType) // Making this just a warning,
                                    // because there are some oddball
                                    // cases.
                                    .setSubtype(Subtype.narrowDateFieldTooWide)
                                    .setMessage(
                                            "Narrow value \"{0}\" shouldn't be longer than the corresponding abbreviated value \"{1}\"",
                                            value, abbrValue);
                    result.add(item);
                }
            } else if (path.contains("[@type=\"short\"]")) {
                // ensures short <= abbreviated and short >= narrow for days
                String pathToAbbr = path.replace("[@type=\"short\"]", "[@type=\"abbreviated\"]");
                String abbrValue = getCldrFileToCheck().getWinningValueWithBailey(pathToAbbr);
                String pathToNarrow = path.replace("[@type=\"short\"]", "[@type=\"narrow\"]");
                String narrowValue = getCldrFileToCheck().getWinningValueWithBailey(pathToNarrow);
                if ((abbrValue != null
                                && isTooMuchWiderThan(value, abbrValue)
                                && value.length() > abbrValue.length())
                        || (narrowValue != null
                                && isTooMuchWiderThan(narrowValue, value)
                                && narrowValue.length() > value.length())) {
                    // Without the additional check on length() above, the test is too sensitive
                    // and flags reasonable things like lettercase differences
                    String message;
                    String compareValue;
                    if (abbrValue != null
                            && isTooMuchWiderThan(value, abbrValue)
                            && value.length() > abbrValue.length()) {
                        message =
                                "Short value \"{0}\" can't be longer than the corresponding abbreviated value \"{1}\"";
                        compareValue = abbrValue;
                    } else {
                        message =
                                "Short value \"{0}\" can't be shorter than the corresponding narrow value \"{1}\"";
                        compareValue = narrowValue;
                    }
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(errorOrIfBuildWarning())
                                    .setSubtype(Subtype.shortDateFieldInconsistentLength)
                                    .setMessage(message, value, compareValue);
                    result.add(item);
                }
            } else if (path.contains("/eraNarrow")) {
                // ensures eraNarrow <= eraAbbr for eras
                String pathToAbbr = path.replace("/eraNarrow", "/eraAbbr");
                String abbrValue = getCldrFileToCheck().getWinningValueWithBailey(pathToAbbr);
                if (abbrValue != null && isTooMuchWiderThan(value, abbrValue)) {
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(errorOrIfBuildWarning())
                                    .setSubtype(Subtype.narrowDateFieldTooWide)
                                    .setMessage(
                                            "Narrow value \"{0}\" can't be longer than the corresponding abbreviated value \"{1}\"",
                                            value, abbrValue);
                    result.add(item);
                }
            } else if (path.contains("/eraAbbr")) {
                // ensures eraAbbr <= eraNames for eras
                String pathToWide = path.replace("/eraAbbr", "/eraNames");
                String wideValue = getCldrFileToCheck().getWinningValueWithBailey(pathToWide);
                if (wideValue != null && isTooMuchWiderThan(value, wideValue)) {
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(errorOrIfBuildWarning())
                                    .setSubtype(Subtype.abbreviatedDateFieldTooWide)
                                    .setMessage(
                                            "Abbreviated value \"{0}\" can't be longer than the corresponding wide value \"{1}\"",
                                            value, wideValue);
                    result.add(item);
                }
            }

            String failure = flexInfo.checkValueAgainstSkeleton(path, value);
            if (failure != null) {
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(errorOrIfBuildWarning())
                                .setSubtype(Subtype.illegalDatePattern)
                                .setMessage(failure));
            }

            final String collisionPrefix = "//ldml/dates/calendars/calendar";
            main:
            if (path.startsWith(collisionPrefix)) {
                int pos = path.indexOf("\"]"); // end of first type
                if (pos < 0 || skipPath(path)) { // skip narrow, no-calendar
                    break main;
                }
                pos += 2;
                String myType = getLastType(path);
                if (myType == null) {
                    break main;
                }
                String myMainType = getMainType(path);

                String calendarPrefix = path.substring(0, pos);
                boolean endsWithDisplayName =
                        path.endsWith("displayName"); // special hack, these shouldn't be in
                // calendar.

                Set<String> retrievedPaths = new HashSet<>();
                getResolvedCldrFileToCheck()
                        .getPathsWithValue(value, calendarPrefix, null, retrievedPaths);
                if (retrievedPaths.size() < 2) {
                    break main;
                }
                // ldml/dates/calendars/calendar[@type="gregorian"]/eras/eraAbbr/era[@type="0"],
                // ldml/dates/calendars/calendar[@type="gregorian"]/eras/eraNames/era[@type="0"],
                // ldml/dates/calendars/calendar[@type="gregorian"]/eras/eraNarrow/era[@type="0"]]
                Type type = null;
                DayPeriod dayPeriod = null;
                final boolean isDayPeriod = path.contains("dayPeriod");
                if (isDayPeriod) {
                    XPathParts parts = XPathParts.getFrozenInstance(fullPath);
                    type =
                            Type.fromString(
                                    parts.getAttributeValue(5, "type")); // format, stand-alone
                    dayPeriod = DayPeriod.valueOf(parts.getAttributeValue(-1, "type"));
                }

                // TODO redo above and below in terms of parts instead of searching strings

                Set<String> filteredPaths = new HashSet<>();
                Output<Integer> sampleError = new Output<>();

                for (String item : retrievedPaths) {
                    XPathParts itemParts = XPathParts.getFrozenInstance(item);
                    if (item.equals(path)
                            || skipPath(item)
                            || endsWithDisplayName != item.endsWith("displayName")
                            || itemParts.containsElement("alias")) {
                        continue;
                    }
                    String otherType = getLastType(item);
                    if (myType.equals(
                            otherType)) { // we don't care about items with the same type value
                        continue;
                    }
                    String mainType = getMainType(item);
                    if (!myMainType.equals(
                            mainType)) { // we *only* care about items with the same type value
                        continue;
                    }
                    if (isDayPeriod) {
                        // ldml/dates/calendars/calendar[@type="gregorian"]/dayPeriods/dayPeriodContext[@type="format"]/dayPeriodWidth[@type="wide"]/dayPeriod[@type="am"]
                        Type itemType =
                                Type.fromString(
                                        itemParts.getAttributeValue(
                                                5, "type")); // format, stand-alone
                        DayPeriod itemDayPeriod =
                                DayPeriod.valueOf(itemParts.getAttributeValue(-1, "type"));

                        if (!dateFormatInfoFormat.collisionIsError(
                                type, dayPeriod, itemType, itemDayPeriod, sampleError)) {
                            continue;
                        }
                    }
                    filteredPaths.add(item);
                }
                if (filteredPaths.isEmpty()) {
                    break main;
                }
                Set<String> others = new TreeSet<>();
                for (String path2 : filteredPaths) {
                    PathHeader pathHeader = getPathHeaderFactory().fromPath(path2);
                    others.add(pathHeader.getHeaderCode());
                }
                CheckStatus.Type statusType =
                        getPhase() == Phase.SUBMISSION || getPhase() == Phase.BUILD
                                ? CheckStatus.warningType
                                : CheckStatus.errorType;
                final CheckStatus checkStatus =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(statusType)
                                .setSubtype(Subtype.dateSymbolCollision);
                if (sampleError.value == null) {
                    checkStatus.setMessage(
                            "The date value “{0}” is the same as what is used for a different item: {1}",
                            value, others.toString());
                } else {
                    checkStatus.setMessage(
                            "The date value “{0}” is the same as what is used for a different item: {1}. Sample problem: {2}",
                            value, others.toString(), sampleError.value / DayPeriodInfo.HOUR);
                }
                result.add(checkStatus);
            }
            DateTimePatternType dateTypePatternType = DateTimePatternType.fromPath(path);
            if (DateTimePatternType.STOCK_AVAILABLE_INTERVAL_PATTERNS.contains(
                    dateTypePatternType)) {
                boolean patternBasicallyOk = false;
                try {
                    formatParser.set(value);
                    patternBasicallyOk = true;
                } catch (RuntimeException e) {
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                    String message = e.getMessage();
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.illegalDatePattern);
                    if (message.contains("Illegal datetime field:")) {
                        item.setMessage(message);
                    } else {
                        item.setMessage("Illegal date format pattern {0}", e);
                    }
                    result.add(item);
                }
                if (patternBasicallyOk) {
                    checkPattern(dateTypePatternType, path, value, result);
                }
            } else if (path.contains("datetimeSkeleton")
                    && !path.contains("[@alt=")) { // cannot test any alt skeletons
                // Get calendar type from //ldml/dates/calendars/calendar[@type="..."]/
                int startIndex = path.indexOf(CALENDAR_ID_PREFIX);
                if (startIndex > 0) {
                    startIndex += CALENDAR_ID_PREFIX.length();
                    int endIndex = path.indexOf("\"]", startIndex);
                    String calendarType = path.substring(startIndex, endIndex);
                    // Get pattern generated from datetimeSkeleton
                    DateTimePatternGenerator dtpg = getDTPGForCalendarType(calendarType);
                    String patternFromSkeleton = dtpg.getBestPattern(value);
                    // Get actual stock pattern
                    String patternPath =
                            path.replace("/datetimeSkeleton", "/pattern[@type=\"standard\"]");
                    String patternStock = getCldrFileToCheck().getWinningValue(patternPath);
                    // Compare and flag error if mismatch
                    if (!patternFromSkeleton.equals(patternStock)) {
                        CheckStatus item =
                                new CheckStatus()
                                        .setCause(this)
                                        .setMainType(CheckStatus.warningType)
                                        .setSubtype(Subtype.inconsistentDatePattern)
                                        .setMessage(
                                                "Pattern \"{0}\" from datetimeSkeleton should match corresponding standard pattern \"{1}\", adjust availableFormats to fix.",
                                                patternFromSkeleton, patternStock);
                        result.add(item);
                    }
                }
            } else if (path.contains("hourFormat")) {
                int semicolonPos = value.indexOf(';');
                if (semicolonPos < 0) {
                    CheckStatus item =
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.illegalDatePattern)
                                    .setMessage(
                                            "Value should contain a positive hour format and a negative hour format separated by a semicolon.");
                    result.add(item);
                } else {
                    String[] formats = value.split(";");
                    if (formats[0].equals(formats[1])) {
                        CheckStatus item =
                                new CheckStatus()
                                        .setCause(this)
                                        .setMainType(CheckStatus.errorType)
                                        .setSubtype(Subtype.illegalDatePattern)
                                        .setMessage("The hour formats should not be the same.");
                        result.add(item);
                    } else {
                        checkHasHourMinuteSymbols(formats[0], result);
                        checkHasHourMinuteSymbols(formats[1], result);
                    }
                }
            }
        } catch (ParseException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
            CheckStatus item =
                    new CheckStatus()
                            .setCause(this)
                            .setMainType(CheckStatus.errorType)
                            .setSubtype(Subtype.illegalDatePattern)
                            .setMessage("ParseException in creating date format {0}", e);
            result.add(item);
        } catch (Exception e) {
            if (DEBUG) {
                e.printStackTrace();
            }
            String msg = e.getMessage();
            if (msg == null || !HACK_CONFLICTING.matcher(msg).find()) {
                CheckStatus item =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.illegalDatePattern)
                                .setMessage("Error in creating date format {0}", e);
                result.add(item);
            }
        }
        return this;
    }

    private void checkTimeFormatMatchesRegion(String value, List<CheckStatus> result) {
        String localeID = getResolvedCldrFileToCheck().getLocaleID();
        if (LocaleNames.ROOT.equals(localeID)) {
            return;
        }
        DateTimePatternGenerator dtpg = DateTimePatternGenerator.getEmptyInstance();
        Map<String /* region */, PreferredAndAllowedHour> timeData = sdi.getTimeData();
        Map<String, String> likelySubtags = sdi.getLikelySubtags();
        String jPattern = getRegionHourFormat(timeData, localeID, likelySubtags);
        if (jPattern == null) {
            CheckStatus item =
                    new CheckStatus()
                            .setCause(this)
                            .setMainType(CheckStatus.errorType)
                            .setSubtype(Subtype.inconsistentTimePattern)
                            .setMessage("No hour format found");
            result.add(item);
            return;
        }
        String shortPatSkeleton = dtpg.getBaseSkeleton(value); // e.g., "ahm" or "Hm"
        String jPatSkeleton = dtpg.getBaseSkeleton(jPattern); // e.g., "ah" or "H"
        final char[] timeCycleChars = {'H', 'h', 'K', 'k'};
        for (char timeCycleChar : timeCycleChars) {
            if (jPatSkeleton.indexOf(timeCycleChar) >= 0
                    && shortPatSkeleton.indexOf(timeCycleChar) < 0) {
                String message =
                        "Time format does not match region; expected "
                                + timeCycleChar
                                + " in the value "
                                + value;
                CheckStatus item =
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.warningType)
                                .setSubtype(Subtype.inconsistentTimePattern)
                                .setMessage(message);
                result.add(item);
                return;
            }
        }
    }

    private String getRegionHourFormat(
            Map<String, PreferredAndAllowedHour> timeData,
            String localeID,
            Map<String, String> likelySubtags) {
        PreferredAndAllowedHour prefAndAllowedHr = timeData.get(localeID);
        if (prefAndAllowedHr == null) {
            LocaleIDParser lp = new LocaleIDParser();
            String region = lp.set(localeID).getRegion();
            if (region == null || region.isEmpty()) {
                String loc2 = likelySubtags.get(localeID);
                if (loc2 != null && !loc2.isEmpty()) {
                    region = lp.set(loc2).getRegion();
                }
                if (region == null || region.isEmpty()) {
                    // If localeID has a script but not a region, likelySubtags may
                    // not have an entry for that combination of language and script.
                    // Use LikelySubtags.maximize. Examples: bal_Latn to bal_Latn_PK, kok_Latn to
                    // kok_Latn_IN, ks_Deva to ks_Deva_IN, kxv_Deva to kxv_Deva_IN, ms_Arab to
                    // ms_Arab_MY, and vai_Latn to vai_Latn_LR.
                    String locMax = new LikelySubtags().maximize(localeID);
                    region = lp.set(locMax).getRegion();
                }
            }
            prefAndAllowedHr = timeData.get(region);
            if (prefAndAllowedHr == null) {
                prefAndAllowedHr = timeData.get(StandardCodes.NO_COUNTRY /* 001, world */);
                if (prefAndAllowedHr == null) {
                    return null;
                }
            }
        }
        return prefAndAllowedHr.preferred.base.name();
    }

    // ORDERED SET (the ordering is used in toOrder)

    static final Set<Integer> expectedField =
            ImmutableSet.of(
                    DateTimePatternGenerator.ERA,
                    DateTimePatternGenerator.YEAR,
                    DateTimePatternGenerator.QUARTER,
                    DateTimePatternGenerator.MONTH,
                    DateTimePatternGenerator.WEEK_OF_MONTH,
                    DateTimePatternGenerator.WEEK_OF_YEAR,
                    DateTimePatternGenerator.DAY,
                    DateTimePatternGenerator.WEEKDAY,
                    DateTimePatternGenerator.HOUR,
                    DateTimePatternGenerator.MINUTE,
                    DateTimePatternGenerator.SECOND,
                    DateTimePatternGenerator.DAYPERIOD,
                    DateTimePatternGenerator.ZONE);
    static final List<Integer> toOrder = Lists.reverse(List.copyOf(expectedField));

    /**
     * Returns null if the path is not a calendar path for iso8601, or if it is ok for iso8601.<br>
     * Otherwise returns a string with the error.
     *
     * @param path
     * @param value
     * @return
     */
    // This is public for testing

    public static String checkIso8601(String path, String value) {
        // ldml/dates/calendars/calendars/dateFormats/dateFormatLength
        XPathParts parts = XPathParts.getFrozenInstance(path);
        if (!"iso8601".equals(parts.getAttributeValue(3, "type"))) {
            return null;
        }
        String key = parts.getElement(5);
        boolean isInterval = false;
        switch (key) {
            case "dateTimeFormatLength":
                {
                    // should be something like
                    // ldml/dates/calendars/calendar[@type="gregorian"]/dateTimeFormats/dateTimeFormatLength[@type="full"]/dateTimeFormat[@type="standard"]/pattern[@type="standard"]
                    // {1}, {0}

                    int index0 = value.indexOf("{0}");
                    int index1 = value.indexOf("{1}");
                    if (index0 < index1) {
                        return "Put the {1} field (the date) before the {1} field (the time), in a YMD (Year-First) calendar.";
                    }
                    return null;
                }
            case "appendItem":
            case "dateFormatLength":
            case "timeFormatLength":
            case "availableFormats":
                break;
            case "intervalFormats":
                isInterval = true;
                break;
            default:
                return null;
        }

        String intervalPosition = "1st";
        // verify
        //  the order is the same as in expectedField
        //  there is no other field
        //  time is 24 hour (0..23)
        DateTimePatternGenerator.FormatParser parser = new DateTimePatternGenerator.FormatParser();
        VariableField lastField = null;
        Set<Integer> fieldTypesSoFar = new LinkedHashSet<>();

        for (Object p : parser.set(value).getItems()) {
            if (!(p instanceof VariableField)) {
                continue;
            }
            VariableField field = (VariableField) p;
            int type = field.getType();
            if (!expectedField.contains(type)) {
                return "Field " + field + " is not allowed in a YMD (Year-First) calendar.";
            }
            // The two parts of an interval are identified by when you hit the same type of field
            // twice
            // like y - y, or M d - M
            if (fieldTypesSoFar.contains(type)) {
                if (isInterval && intervalPosition.equals("1st")) { // so one freebe for intervals
                    intervalPosition = "2nd";
                    fieldTypesSoFar.clear(); // entering second part of interval
                    lastField = null;
                } else {
                    return "Field " + field + " is the same type as a previous field.";
                }
            }

            // No year truncation

            if (type == DateTimePatternGenerator.YEAR) {
                if (field.toString().length() == 2) {
                    return "Field "
                            + field
                            + " is incorrect. For a YMD (Year-First) calendar, the year field cannot be truncated to 2 digits.";
                }
            }

            // the type values are out of order if lastType < type (using toOrder for the ordering)

            if (lastField != null) {
                int lastType = lastField.getType();
                if (toOrder.indexOf(lastType) < toOrder.indexOf(type)) {
                    return "Field "
                            + lastField
                            + " cannot come before field "
                            + field
                            + (isInterval
                                    ? " in the " + intervalPosition + " part of the range"
                                    : "")
                            + ". A YMD (Year-First) calendar is special: bigger fields must come before smaller ones even when it feels unnatural in your language. "
                            + " Change the text separating the fields as best you can.";
                }
            }
            fieldTypesSoFar.add(type);
            lastField = field;
        }
        return null;
    }

    public CheckStatus.Type errorOrIfBuildWarning() {
        return getPhase() != Phase.BUILD ? CheckStatus.errorType : CheckStatus.warningType;
    }

    private boolean isTooMuchWiderThan(String shortString, String longString) {
        // We all 1/3 the width of the reference character as a "fudge factor" in determining the
        // allowable width
        return ApproximateWidth.getWidth(shortString)
                > ApproximateWidth.getWidth(longString) + REFCHAR / 3;
    }

    /**
     * Check for the presence of hour and minute symbols.
     *
     * @param value the value to be checked
     * @param result the list to add any errors to.
     */
    private void checkHasHourMinuteSymbols(String value, List<CheckStatus> result) {
        boolean hasHourSymbol = HOUR_SYMBOL.matcher(value).find();
        boolean hasMinuteSymbol = MINUTE_SYMBOL.matcher(value).find();
        if (!hasHourSymbol && !hasMinuteSymbol) {
            result.add(
                    createErrorCheckStatus()
                            .setMessage(
                                    "The hour and minute symbols are missing from {0}.", value));
        } else if (!hasHourSymbol) {
            result.add(
                    createErrorCheckStatus()
                            .setMessage(
                                    "The hour symbol (H or HH) should be present in {0}.", value));
        } else if (!hasMinuteSymbol) {
            result.add(
                    createErrorCheckStatus()
                            .setMessage("The minute symbol (mm) should be present in {0}.", value));
        }
    }

    /**
     * Convenience method for creating errors.
     *
     * @return
     */
    private CheckStatus createErrorCheckStatus() {
        return new CheckStatus()
                .setCause(this)
                .setMainType(CheckStatus.errorType)
                .setSubtype(Subtype.illegalDatePattern);
    }

    public boolean skipPath(String path) {
        return path.contains("arrow")
                || path.contains("/availableFormats")
                || path.contains("/interval")
                || path.contains("/dateTimeFormat")
        //            || path.contains("/dayPeriod[")
        //            && !path.endsWith("=\"pm\"]")
        //            && !path.endsWith("=\"am\"]")
        ;
    }

    public String getLastType(String path) {
        int secondType = path.lastIndexOf("[@type=\"");
        if (secondType < 0) {
            return null;
        }
        secondType += 8;
        int secondEnd = path.indexOf("\"]", secondType);
        if (secondEnd < 0) {
            return null;
        }
        return path.substring(secondType, secondEnd);
    }

    public String getMainType(String path) {
        int secondType = path.indexOf("\"]/");
        if (secondType < 0) {
            return null;
        }
        secondType += 3;
        int secondEnd = path.indexOf("/", secondType);
        if (secondEnd < 0) {
            return null;
        }
        return path.substring(secondType, secondEnd);
    }

    private String getValues(CLDRFile resolvedCldrFileToCheck, Collection<String> values) {
        Set<String> results = new TreeSet<>();
        for (String path : values) {
            final String stringValue = resolvedCldrFileToCheck.getStringValue(path);
            if (stringValue != null) {
                results.add(stringValue);
            }
        }
        return "{" + Joiner.on("},{").join(results) + "}";
    }

    static final Pattern HACK_CONFLICTING = PatternCache.get("Conflicting fields:\\s+M+,\\s+l");

    @Override
    public CheckCLDR handleGetExamples(
            String path, String fullPath, String value, Options options, List<CheckStatus> result) {
        if (!path.contains("/dates") || !path.contains("gregorian")) return this;
        try {
            if (path.contains("/pattern") && !path.contains("/dateTimeFormat")
                    || path.contains("/dateFormatItem")) {
                checkPattern2(path, value, result);
            }
        } catch (Exception e) {
            // don't worry about errors
        }
        return this;
    }

    static final SimpleDateFormat neutralFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", ULocale.ENGLISH);

    static {
        neutralFormat.setTimeZone(ExampleGenerator.ZONE_SAMPLE);
    }

    // We extend VariableField to implement a proper equals() method so we can use
    // List methods remove() and get().
    private static class MyVariableField extends VariableField {
        public MyVariableField(String string) {
            super(string);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof VariableField)) {
                return false;
            }
            return (this.toString().equals(object.toString()));
        }

        @Override
        public int hashCode() {
            return this.toString().hashCode();
        }
    }

    // In a List, replace DateTimePatternGenerator.VariableField items with MyVariableField
    private List<Object> updateVariableFieldInList(List<Object> items) {
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Object object = items.get(itemIndex);
            if (object instanceof VariableField) {
                items.set(itemIndex, new MyVariableField(object.toString()));
            }
        }
        return items;
    }

    private void checkPattern(
            DateTimePatternType dateTypePatternType,
            String path,
            String value,
            List<CheckStatus> result)
            throws ParseException {
        // Map to skeleton including mapping to canonical pattern chars e.g. LLL -> MMM
        // (ICU internal, for CLDR?)
        String skeleton = dateTimePatternGenerator.getSkeletonAllowingDuplicates(value);
        String skeletonCanonical =
                dateTimePatternGenerator.getCanonicalSkeletonAllowingDuplicates(value);

        if (value.contains("MMM.")
                || value.contains("LLL.")
                || value.contains("E.")
                || value.contains("eee.")
                || value.contains("ccc.")
                || value.contains("QQQ.")
                || value.contains("qqq.")) {
            result.add(
                    new CheckStatus()
                            .setCause(this)
                            .setMainType(CheckStatus.warningType)
                            .setSubtype(Subtype.incorrectDatePattern)
                            .setMessage(
                                    "Your pattern ({0}) is probably incorrect; abbreviated month/weekday/quarter names that need a period should include it in the name, rather than adding it to the pattern.",
                                    value));
        }
        XPathParts pathParts = XPathParts.getFrozenInstance(path);
        String calendar = pathParts.findAttributeValue("calendar", "type");
        String id;
        switch (dateTypePatternType) {
            case AVAILABLE:
                id = pathParts.getAttributeValue(-1, "id");
                break;
            case INTERVAL:
                id = pathParts.getAttributeValue(-2, "id");
                break;
            case STOCK:
                id = pathParts.getAttributeValue(-3, "type");
                break;
            default:
                throw new IllegalArgumentException();
        }

        if (dateTypePatternType == DateTimePatternType.AVAILABLE
                || dateTypePatternType == DateTimePatternType.INTERVAL) {
            // Map to skeleton including mapping to canonical pattern chars e.g. LLL -> MMM
            // (ICU internal, for CLDR?)
            String idCanonical =
                    dateTimePatternGenerator.getCanonicalSkeletonAllowingDuplicates(id);
            if (skeleton.isEmpty()) {
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.incorrectDatePattern)
                                // "Internal ID ({0}) doesn't match generated ID ({1}) for pattern
                                // ({2}). " +
                                .setMessage(
                                        "Your pattern ({1}) is incorrect for ID ({0}). "
                                                + "You need to supply a pattern according to "
                                                + CLDRURLS.DATE_TIME_PATTERNS_URL
                                                + ".",
                                        id,
                                        value));
            } else if (!dateTimePatternGenerator.skeletonsAreSimilar( // ICU internal for CLDR
                    idCanonical, skeletonCanonical)) {
                // Adjust pattern to match skeleton, but only width and subtype within
                // canonical categories e.g. MMM -> LLLL, H -> HH. Will not change across
                // canonical categories e.g. m -> M
                String fixedValue = dateTimePatternGenerator.replaceFieldTypes(value, id);
                // check to see if that was enough; if not, may need to do more work.
                String fixedValueCanonical =
                        dateTimePatternGenerator.getCanonicalSkeletonAllowingDuplicates(fixedValue);
                String valueFromId = null;
                if (!dateTimePatternGenerator.skeletonsAreSimilar(
                        idCanonical, fixedValueCanonical)) {
                    // Need to do more work. Try two things to get a reasonable suggestion:
                    // - Getting the winning pattern (valueFromId) from availableFormats for id,
                    // if it is not the same as the bad value we already have.
                    // - Replace a pattern field in fixedValue twhose type does not match the
                    // corresponding field from id.
                    //
                    // Here is the first thing, getting the winning pattern (valueFromId) from
                    // availableFormats for id.
                    String availableFormatPath =
                            "//ldml/dates/calendars/calendar[@type=\""
                                    + calendar
                                    + "\"]/dateTimeFormats/availableFormats/dateFormatItem[@id=\""
                                    + id
                                    + "\"]";
                    valueFromId =
                            getCldrFileToCheck().getWinningValueWithBailey(availableFormatPath);
                    if (valueFromId != null
                            && (valueFromId.equals(value) || valueFromId.equals(fixedValue))) {
                        valueFromId = null; // not useful in this case
                    }
                    //
                    // Here is the second thing, replacing a pattern field that does not match.
                    // We compare FormatParser Lists for idCanonical and fixedValueCanonical
                    // and if a mismatch we update the FormatParser list for fixedValue and
                    // generate an updated string from the FormatParser.
                    DateTimePatternGenerator.FormatParser idCanonFormat =
                            new DateTimePatternGenerator.FormatParser();
                    idCanonFormat.set(idCanonical);
                    List<Object> idCanonItems = updateVariableFieldInList(idCanonFormat.getItems());
                    DateTimePatternGenerator.FormatParser fixedValueCanonFormat =
                            new DateTimePatternGenerator.FormatParser();
                    fixedValueCanonFormat.set(fixedValueCanonical);
                    List<Object> fixedValueCanonItems =
                            updateVariableFieldInList(fixedValueCanonFormat.getItems());
                    DateTimePatternGenerator.FormatParser fixedValueFormat =
                            new DateTimePatternGenerator.FormatParser();
                    fixedValueFormat.set(fixedValue);
                    List<Object> fixedValueItems =
                            updateVariableFieldInList(fixedValueFormat.getItems());
                    // For idCanonFormat and fixedValueCanonFormat we started with skeletons (no
                    // literal text), so the items we are comparing will all be MyVariableField. We
                    // iterate over idCanonItems stripping matching items from fixedValueCanonItems
                    // until we hopefully have one remaining item in each that do not match each
                    // other. Then in fixedValueItems we replace the mismatched item with the one
                    // from idCanonItems.
                    int itemIndex = idCanonItems.size();
                    while (--itemIndex >= 0) {
                        Object idCanonItem = idCanonItems.get(itemIndex);
                        if (fixedValueCanonItems.remove(idCanonItem)) {
                            // we have a match, removed it from fixedValueCanonItems, now remove
                            // it from idCanonItems (ok since we are iterating backwards).
                            idCanonItems.remove(itemIndex);
                        }
                    }
                    // Hopefully this leaves us with one item in each list, the mismatch to fix.
                    if (idCanonItems.size() == 1 && fixedValueCanonItems.size() == 1) {
                        // In fixedValueItems, replace all occurrences of the single item in
                        // fixedValueCanonItems (bad value) with the item in idCanonItems.
                        // There might be more than one for e.g. intervalFormats.
                        Object fixedValueCanonItem = fixedValueCanonItems.get(0); // replace this
                        Object idCanonItem = idCanonItems.get(0); // with this
                        boolean didUpdate = false;
                        while ((itemIndex = fixedValueItems.indexOf(fixedValueCanonItem)) >= 0) {
                            fixedValueItems.set(itemIndex, idCanonItem);
                            didUpdate = true;
                        }
                        if (didUpdate) {
                            // Now get the updated fixedValue with this replacement
                            fixedValue = fixedValueFormat.toString();
                            fixedValueCanonical =
                                    dateTimePatternGenerator.getCanonicalSkeletonAllowingDuplicates(
                                            fixedValue);
                        }
                    }
                    // If this replacement attempt did not work, we give up on fixedValue
                    if (!dateTimePatternGenerator.skeletonsAreSimilar(
                            idCanonical, fixedValueCanonical)) {
                        fixedValue = null;
                    }
                }
                // Now report problem and suggested fix
                String suggestion = "(no suggestion)";
                if (fixedValue != null) {
                    suggestion = "(" + fixedValue + ")";
                    if (valueFromId != null && !valueFromId.equals(fixedValue)) {
                        suggestion += " or (" + valueFromId + ")";
                    }
                } else if (valueFromId != null) {
                    suggestion = "(" + valueFromId + ")";
                }
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.incorrectDatePattern)
                                // "Internal ID ({0}) doesn't match generated ID ({1}) for pattern
                                // ({2}). " +
                                .setMessage(
                                        "Your pattern ({2}) doesn't correspond to what is asked for. Yours would be right for an ID ({1}) but not for the ID ({0}). "
                                                + "Please change your pattern to match what was asked, such as {3}, with the right punctuation and/or ordering for your language. See "
                                                + CLDRURLS.DATE_TIME_PATTERNS_URL
                                                + ".",
                                        id,
                                        skeletonCanonical,
                                        value,
                                        suggestion));
            }
            if (dateTypePatternType == DateTimePatternType.AVAILABLE) {
                // index y+w+ must correpond to pattern containing only Y+ and w+
                if (idCanonical.matches("y+w+")
                        && !(skeleton.matches("Y+w+") || skeleton.matches("w+Y+"))) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.incorrectDatePattern)
                                    .setMessage(
                                            "For id {0}, the pattern ({1}) must contain fields Y and w, and no others.",
                                            id, value));
                }
                // index M+W msut correspond to pattern containing only M+/L+ and W
                if (idCanonical.matches("M+W")
                        && !(skeletonCanonical.matches("M+W")
                                || skeletonCanonical.matches("WM+"))) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.incorrectDatePattern)
                                    .setMessage(
                                            "For id {0}, the pattern ({1}) must contain fields M or L, plus W, and no others.",
                                            id, value));
                }

                if (CALENDARS_FOR_CORES.contains(calendar)) {
                    Set<String> coreSkeletons = RelatedDatePathValues.getCores(id);
                    if (!coreSkeletons.isEmpty()) {
                        XPathParts parts = XPathParts.getFrozenInstance(path);
                        XPathParts coreParts = parts.cloneAsThawed();
                        for (String coreSkeleton : coreSkeletons) {
                            coreParts.putAttributeValue(-1, "id", coreSkeleton);
                            String coreValue =
                                    getResolvedCldrFileToCheck()
                                            .getStringValue(coreParts.toString());
                            if (coreValue != null
                                    && !RelatedDatePathValues.contains(value, coreValue)) {
                                if (DEBUG && getLocaleID().equals("zu") && id.equals("hmsv")) {
                                    RelatedDatePathValues.contains(value, coreValue);
                                }
                                result.add(
                                        new CheckStatus()
                                                .setCause(this)
                                                .setMainType(CheckStatus.warningType)
                                                .setSubtype(Subtype.inconsistentCoreDatePattern)
                                                .setMessage(
                                                        "“{0}” ⊅ “{1}”: the pattern for {2} should contain the pattern for {3}",
                                                        value, coreValue, id, coreSkeleton));
                            }
                        }
                    }
                }
            }
            String failureMessage = (String) flexInfo.getFailurePath(path);
            if (failureMessage != null) {
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.illegalDatePattern)
                                .setMessage("{0}", failureMessage));
            }
        }
        if (dateTypePatternType == DateTimePatternType.STOCK) {
            int style = 0;
            String len = pathParts.findAttributeValue("timeFormatLength", "type");
            DateOrTime dateOrTime = DateOrTime.time;
            if (len == null) {
                dateOrTime = DateOrTime.date;
                style += 4;
                len = pathParts.findAttributeValue("dateFormatLength", "type");
                if (len == null) {
                    len = pathParts.findAttributeValue("dateTimeFormatLength", "type");
                    dateOrTime = DateOrTime.dateTime;
                }
            }

            DateTimeLengths dateTimeLength =
                    DateTimeLengths.valueOf(len != null ? len.toUpperCase(Locale.ENGLISH) : null);

            if ("gregorian".equals(calendar)
                    && !"root".equals(getCldrFileToCheck().getLocaleID())) {
                checkValue(dateTimeLength, dateOrTime, value, result);
            }
            if (dateOrTime == DateOrTime.dateTime) {
                return; // We don't need to do the rest for date/time combo patterns.
            }
            style += dateTimeLength.ordinal();
            // do regex match with skeletonCanonical but report errors using skeleton; they have
            // corresponding field lengths
            if (!dateTimePatterns[style].matcher(skeletonCanonical).matches()
                    && !"chinese".equals(calendar)
                    && !"hebrew".equals(calendar)) {
                int i = RegexUtilities.findMismatch(dateTimePatterns[style], skeletonCanonical);
                String skeletonPosition = skeleton.substring(0, i) + "☹" + skeleton.substring(i);
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.errorType)
                                .setSubtype(Subtype.missingOrExtraDateField)
                                .setMessage(
                                        "Field is missing, extra, or the wrong length. Expected {0} [Internal: {1} / {2}]",
                                        dateTimeMessage[style],
                                        skeletonPosition,
                                        dateTimePatterns[style].pattern()));
            }
        } else if (dateTypePatternType == DateTimePatternType.INTERVAL) {
            if (id.contains("y")) {
                String greatestDifference =
                        pathParts.findAttributeValue("greatestDifference", "id");
                int requiredYearFieldCount = 1;
                if ("y".equals(greatestDifference)) {
                    requiredYearFieldCount = 2;
                }
                int yearFieldCount = 0;
                Matcher yearFieldMatcher = YEAR_FIELDS.matcher(value);
                while (yearFieldMatcher.find()) {
                    yearFieldCount++;
                }
                if (yearFieldCount < requiredYearFieldCount) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.missingOrExtraDateField)
                                    .setMessage(
                                            "Not enough year fields in interval pattern. Must have {0} but only found {1}",
                                            requiredYearFieldCount, yearFieldCount));
                }
            }
            // check PatternInfo, for CLDR-17827
            // ICU-22835, DateIntervalInfo.genPatternInfo fails for intervals like LLL - MMM (in fa)
            if (!(value.contains("LLL") && value.contains("MMM"))) {
                PatternInfo pattern = DateIntervalInfo.genPatternInfo(value, false);
                try {
                    String first = pattern.getFirstPart();
                    String second = pattern.getSecondPart();
                    if (first == null || second == null) {
                        result.add(
                                new CheckStatus()
                                        .setCause(this)
                                        .setMainType(CheckStatus.errorType)
                                        .setSubtype(Subtype.incorrectDatePattern)
                                        .setMessage(
                                                "DateIntervalInfo.PatternInfo returns null for first or second part"));
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.incorrectDatePattern)
                                    .setMessage("DateIntervalInfo.PatternInfo exception {0}", e));
                }
            }
        }

        if (value.contains("G") && "gregorian".equals(calendar)) {
            GyState actual = GyState.forPattern(value);
            GyState expected = getExpectedGy(getCldrFileToCheck().getLocaleID());
            if (actual != expected) {
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.warningType)
                                .setSubtype(Subtype.unexpectedOrderOfEraYear)
                                .setMessage(
                                        "Unexpected order of era/year. Expected {0}, but got {1} in 〈{2}〉 for {3}/{4}",
                                        expected, actual, value, calendar, id));
            }
        }
    }

    enum DateOrTime {
        date,
        time,
        dateTime
    }

    static final Map<DateOrTime, Relation<DateTimeLengths, String>> STOCK_PATTERNS =
            new EnumMap<>(DateOrTime.class);

    private static void add(DateOrTime dateOrTime, DateTimeLengths dateTimeLength, String... keys) {
        Relation<DateTimeLengths, String> rel = STOCK_PATTERNS.get(dateOrTime);
        if (rel == null) {
            STOCK_PATTERNS.put(
                    dateOrTime,
                    rel = Relation.of(new EnumMap<>(DateTimeLengths.class), LinkedHashSet.class));
        }
        rel.putAll(dateTimeLength, Arrays.asList(keys));
    }

    static {
        add(DateOrTime.time, DateTimeLengths.SHORT, "hm", "Hm");
        add(DateOrTime.time, DateTimeLengths.MEDIUM, "hms", "Hms");
        add(DateOrTime.time, DateTimeLengths.LONG, "hms*z", "Hms*z");
        add(DateOrTime.time, DateTimeLengths.FULL, "hms*zzzz", "Hms*zzzz");
        add(DateOrTime.date, DateTimeLengths.SHORT, "yMd");
        add(DateOrTime.date, DateTimeLengths.MEDIUM, "yMMMd");
        add(DateOrTime.date, DateTimeLengths.LONG, "yMMMMd", "yMMMd");
        add(DateOrTime.date, DateTimeLengths.FULL, "yMMMMEd", "yMMMEd");
    }

    static final String AVAILABLE_PREFIX =
            "//ldml/dates/calendars/calendar[@type=\"gregorian\"]/dateTimeFormats/availableFormats/dateFormatItem[@id=\"";
    static final String AVAILABLE_SUFFIX = "\"]";
    static final String APPEND_TIMEZONE =
            "//ldml/dates/calendars/calendar[@type=\"gregorian\"]/dateTimeFormats/appendItems/appendItem[@request=\"Timezone\"]";

    private void checkValue(
            DateTimeLengths dateTimeLength,
            DateOrTime dateOrTime,
            String value,
            List<CheckStatus> result) {
        // Check consistency of the pattern vs. supplemental wrt 12 vs. 24 hour clock.
        if (dateOrTime == DateOrTime.time) {
            PreferredAndAllowedHour pref = sdi.getTimeData().get(territory);
            if (pref == null) {
                pref = sdi.getTimeData().get("001");
            }
            String checkForHour, clockType;
            if (pref.preferred.equals(PreferredAndAllowedHour.HourStyle.h)) {
                checkForHour = "h";
                clockType = "12";
            } else {
                checkForHour = "H";
                clockType = "24";
            }
            if (!value.contains(checkForHour)) {
                CheckStatus.Type errType = CheckStatus.errorType;
                // French/Canada is strange, they use 24 hr clock while en_CA uses 12.
                if (language.equals("fr") && territory.equals("CA")) {
                    errType = CheckStatus.warningType;
                }

                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(errType)
                                .setSubtype(Subtype.inconsistentTimePattern)
                                .setMessage(
                                        "Time format inconsistent with supplemental time data for territory \""
                                                + territory
                                                + "\"."
                                                + " Use '"
                                                + checkForHour
                                                + "' for "
                                                + clockType
                                                + " hour clock."));
            }
        }
        if (dateOrTime == DateOrTime.dateTime) {
            boolean inQuotes = false;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '\'') {
                    inQuotes = !inQuotes;
                }
                if (!inQuotes && (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.errorType)
                                    .setSubtype(Subtype.patternContainsInvalidCharacters)
                                    .setMessage("Unquoted letter \"{0}\" in dateTime format.", ch));
                }
            }
        } else {
            Set<String> keys = STOCK_PATTERNS.get(dateOrTime).get(dateTimeLength);
            StringBuilder b = new StringBuilder();
            boolean onlyNulls = true;
            int countMismatches = 0;
            boolean errorOnMissing = false;
            String timezonePattern = null;
            Set<String> bases = new LinkedHashSet<>();
            for (String key : keys) {
                int star = key.indexOf('*');
                boolean hasStar = star >= 0;
                String base = !hasStar ? key : key.substring(0, star);
                bases.add(base);
                String xpath = AVAILABLE_PREFIX + base + AVAILABLE_SUFFIX;
                String value1 = getCldrFileToCheck().getStringValue(xpath);
                // String localeFound = getCldrFileToCheck().getSourceLocaleID(xpath, null);  &&
                // !localeFound.equals("root") && !localeFound.equals("code-fallback")
                if (value1 != null) {
                    onlyNulls = false;
                    if (hasStar) {
                        String zone = key.substring(star + 1);
                        timezonePattern =
                                getResolvedCldrFileToCheck().getStringValue(APPEND_TIMEZONE);
                        value1 = MessageFormat.format(timezonePattern, value1, zone);
                    }
                    if (equalsExceptWidth(value, value1)) {
                        return;
                    }
                } else {
                    // Example, if the requiredLevel for the locale is moderate,
                    // and the level for the path is modern, then we'll skip the error,
                    // but if the level for the path is basic, then we won't
                    Level pathLevel = coverageLevel.getLevel(xpath);
                    if (requiredLevel.compareTo(pathLevel) >= 0) {
                        errorOnMissing = true;
                    }
                }
                add(b, base, value1);
                countMismatches++;
            }
            if (!onlyNulls) {
                if (timezonePattern != null) {
                    b.append(" (with appendZonePattern: “").append(timezonePattern).append("”)");
                }
                String msg =
                        countMismatches != 1
                                ? "{1}-{0} → “{2}” didn't match any of the corresponding flexible skeletons: [{3}]. This or the flexible patterns needs to be changed."
                                : "{1}-{0} → “{2}” didn't match the corresponding flexible skeleton: {3}. This or the flexible pattern needs to be changed.";
                result.add(
                        new CheckStatus()
                                .setCause(this)
                                .setMainType(CheckStatus.warningType)
                                .setSubtype(Subtype.inconsistentDatePattern)
                                .setMessage(msg, dateTimeLength, dateOrTime, value, b));
            } else {
                if (errorOnMissing) {
                    String msg =
                            countMismatches != 1
                                    ? "{1}-{0} → “{2}” doesn't have at least one value for a corresponding flexible skeleton {3}, which needs to be added."
                                    : "{1}-{0} → “{2}” doesn't have a value for the corresponding flexible skeleton {3}, which needs to be added.";
                    result.add(
                            new CheckStatus()
                                    .setCause(this)
                                    .setMainType(CheckStatus.warningType)
                                    .setSubtype(Subtype.missingDatePattern)
                                    .setMessage(
                                            msg,
                                            dateTimeLength,
                                            dateOrTime,
                                            value,
                                            Joiner.on(", ").join(bases)));
                }
            }
        }
    }

    private void add(StringBuilder b, String key, String value1) {
        if (value1 == null) {
            return;
        }
        if (b.length() != 0) {
            b.append(" or ");
        }
        b.append(key).append(" → “").append(value1).append("”");
    }

    private boolean equalsExceptWidth(String value1, String value2) {
        if (value1.equals(value2)) {
            return true;
        } else if (value2 == null) {
            return false;
        }

        List<Object> items1 = new ArrayList<>(formatParser.set(value1).getItems()); // clone
        List<Object> items2 = formatParser.set(value2).getItems();
        if (items1.size() != items2.size()) {
            return false;
        }
        Iterator<Object> it2 = items2.iterator();
        for (Object item1 : items1) {
            Object item2 = it2.next();
            if (item1.equals(item2)) {
                continue;
            }
            if (item1 instanceof VariableField && item2 instanceof VariableField) {
                // simple test for now, ignore widths
                if (item1.toString().charAt(0) == item2.toString().charAt(0)) {
                    continue;
                }
            }
            return false;
        }
        return true;
    }

    static final Set<String> YgLanguages =
            new HashSet<>(
                    Arrays.asList(
                            "ar", "cs", "da", "de", "en", "es", "fa", "fi", "fr", "he", "hr", "id",
                            "it", "nl", "no", "pt", "ru", "sv", "tr"));

    private GyState getExpectedGy(String localeID) {
        // hack for now
        int firstBar = localeID.indexOf('_');
        String lang = firstBar < 0 ? localeID : localeID.substring(0, firstBar);
        return YgLanguages.contains(lang) ? GyState.YEAR_ERA : GyState.ERA_YEAR;
    }

    enum GyState {
        YEAR_ERA,
        ERA_YEAR,
        OTHER;
        static DateTimePatternGenerator.FormatParser formatParser =
                new DateTimePatternGenerator.FormatParser();

        static synchronized GyState forPattern(String value) {
            formatParser.set(value);
            int last = -1;
            for (Object x : formatParser.getItems()) {
                if (x instanceof VariableField) {
                    int type = ((VariableField) x).getType();
                    if (type == DateTimePatternGenerator.ERA
                            && last == DateTimePatternGenerator.YEAR) {
                        return GyState.YEAR_ERA;
                    } else if (type == DateTimePatternGenerator.YEAR
                            && last == DateTimePatternGenerator.ERA) {
                        return GyState.ERA_YEAR;
                    }
                    last = type;
                }
            }
            return GyState.OTHER;
        }
    }

    enum DateTimeLengths {
        SHORT,
        MEDIUM,
        LONG,
        FULL
    }

    // The patterns below should only use the *canonical* characters for each field type:
    // y (not Y, u, U)
    // Q (not q)
    // M (not L)
    // E (not e, c)
    // a (not b, B)
    // H or h (not k or K)
    // v (not z, Z, V)
    static final Pattern[] dateTimePatterns = {
        PatternCache.get("a*(h|hh|H|HH)(m|mm)"), // time-short
        PatternCache.get("a*(h|hh|H|HH)(m|mm)(s|ss)"), // time-medium
        PatternCache.get("a*(h|hh|H|HH)(m|mm)(s|ss)(v+)"), // time-long
        PatternCache.get("a*(h|hh|H|HH)(m|mm)(s|ss)(v+)"), // time-full
        PatternCache.get("G*y{1,4}M{1,2}(d|dd)"), // date-short; allow yyy for Minguo/ROC calendar
        PatternCache.get("G*y(yyy)?M{1,3}(d|dd)"), // date-medium
        PatternCache.get("G*y(yyy)?M{1,4}(d|dd)"), // date-long
        PatternCache.get("G*y(yyy)?M{1,4}E*(d|dd)"), // date-full
        PatternCache.get(".*"), // datetime-short
        PatternCache.get(".*"), // datetime-medium
        PatternCache.get(".*"), // datetime-long
        PatternCache.get(".*"), // datetime-full
    };

    static final String[] dateTimeMessage = {
        "hours (H, HH, h, or hh), and minutes (m or mm)", // time-short
        "hours (H, HH, h, or hh), minutes (m or mm), and seconds (s or ss)", // time-medium
        "hours (H, HH, h, or hh), minutes (m or mm), and seconds (s or ss); optionally timezone (z, zzzz, v, vvvv)", // time-long
        "hours (H, HH, h, or hh), minutes (m or mm), seconds (s or ss), and timezone (z, zzzz, v, vvvv)", // time-full
        "year (y, yy, yyyy), month (M or MM), and day (d or dd); optionally era (G)", // date-short
        "year (y), month (M, MM, or MMM), and day (d or dd); optionally era (G)", // date-medium
        "year (y), month (M, ... MMMM), and day (d or dd); optionally era (G)", // date-long
        "year (y), month (M, ... MMMM), and day (d or dd); optionally day of week (EEEE or cccc) or era (G)", // date-full
    };

    public String toString(DateTimePatternGenerator.FormatParser formatParser) {
        StringBuilder result = new StringBuilder();
        for (Object x : formatParser.getItems()) {
            if (x instanceof VariableField) {
                result.append(x);
            } else {
                result.append(formatParser.quoteLiteral(x.toString()));
            }
        }
        return result.toString();
    }

    private void checkPattern2(String path, String value, List<CheckStatus> result) {
        XPathParts pathParts = XPathParts.getFrozenInstance(path);
        String calendar = pathParts.findAttributeValue("calendar", "type");
        SimpleDateFormat x = icuServiceBuilder.getDateFormat(calendar, value);
        x.setTimeZone(ExampleGenerator.ZONE_SAMPLE);
        result.add(
                new MyCheckStatus().setFormat(x).setCause(this).setMainType(CheckStatus.demoType));
    }

    private DateTimePatternGenerator getDTPGForCalendarType(String calendarType) {
        DateTimePatternGenerator dtpg = dtpgForType.get(calendarType);
        if (dtpg == null) {
            dtpg = flexInfo.getDTPGForCalendarType(calendarType, parentCLDRFiles);
            dtpgForType.put(calendarType, dtpg);
        }
        return dtpg;
    }

    public static class MyCheckStatus extends CheckStatus {
        private SimpleDateFormat df;

        public MyCheckStatus setFormat(SimpleDateFormat df) {
            this.df = df;
            return this;
        }

        @Override
        public SimpleDemo getDemo() {
            return new MyDemo().setFormat(df);
        }
    }

    static class MyDemo extends FormatDemo {
        private SimpleDateFormat df;

        @Override
        protected String getPattern() {
            return df.toPattern();
        }

        @Override
        protected String getSampleInput() {
            return neutralFormat.format(ExampleGenerator.DATE_SAMPLE);
        }

        public MyDemo setFormat(SimpleDateFormat df) {
            this.df = df;
            return this;
        }

        @Override
        protected void getArguments(Map<String, String> inout) {
            currentPattern = currentInput = currentFormatted = currentReparsed = "?";
            Date d;
            try {
                currentPattern = inout.get("pattern");
                if (currentPattern != null) df.applyPattern(currentPattern);
                else currentPattern = getPattern();
            } catch (Exception e) {
                currentPattern = "Use format like: ##,###.##";
                return;
            }
            try {
                currentInput = inout.get("input");
                if (currentInput == null) {
                    currentInput = getSampleInput();
                }
                d = neutralFormat.parse(currentInput);
            } catch (Exception e) {
                currentInput = "Use neutral format like: 1993-11-31 13:49:02";
                return;
            }
            try {
                currentFormatted = df.format(d);
            } catch (Exception e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                currentFormatted = "Can't format: " + e.getMessage();
                return;
            }
            try {
                parsePosition.setIndex(0);
                Date n = df.parse(currentFormatted, parsePosition);
                if (parsePosition.getIndex() != currentFormatted.length()) {
                    currentReparsed =
                            "Couldn't parse past: "
                                    + "\u200E"
                                    + currentFormatted.substring(0, parsePosition.getIndex())
                                    + "\u200E";
                } else {
                    currentReparsed = neutralFormat.format(n);
                }
            } catch (Exception e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                currentReparsed = "Can't parse: " + e.getMessage();
            }
        }
    }
}
