package org.unicode.cldr.tool;

import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.Output;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.unicode.cldr.icu.text.ListFormat;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.CldrUtility.LineHandler;
import org.unicode.cldr.util.Counter2;
import org.unicode.cldr.util.Pair;
import org.unicode.cldr.util.StandardCodes;

public class AddPopulationData {
    static boolean ADD_POP = CldrUtility.getProperty("ADD_POP", false);
    static boolean SHOW_ALTERNATE_NAMES = CldrUtility.getProperty("SHOW_ALTERNATE_NAMES", false);

    enum WBLine {
        // "Afghanistan","AFG","GNI, PPP (current international
        // $)","NY.GNP.MKTP.PP.CD","..","..","13144920451.3325","16509662130.816","18932631964.8727","22408872945.1924","25820670505.2627","30783369469.7509","32116190092.1429","..",

        // Country Name,Country Code,Series Name,Series Code,2000 [YR2000],2001 [YR2001],2002
        // [YR2002],2003 [YR2003],2004 [YR2004],2005 [YR2005],2006 [YR2006],2007 [YR2007],2008
        // [YR2008],2009 [YR2009],2010 [YR2010],2011 [YR2011],2012 [YR2012],2013 [YR2013],2014
        // [YR2014],2015 [YR2015],2016 [YR2016],2017 [YR2017],2018 [YR2018],2019 [YR2019],2020
        // [YR2020]
        Country_Name,
        Country_Code,
        Series_Name,
        Series_Code,
        Year("(\\d+)\\s*\\[YR(\\d+)\\]");

        final Pattern pattern;

        WBLine() {
            this.pattern = Pattern.compile(name().replaceAll("_", " "));
        }

        WBLine(final String regex) {
            this.pattern = Pattern.compile(regex);
        }

        Matcher match(String str) {
            // Skip BOM
            if (str.startsWith("\uFEFF")) {
                str = str.substring("\uFEFF".length());
            }
            return this.pattern.matcher(str);
        }

        static Pair<WBLine, Integer> find(final String str) {
            for (WBLine i : values()) {
                final Matcher m = i.match(str);
                if (m.matches()) {
                    Integer val = 0;
                    if (m.groupCount() > 0) {
                        val = Integer.parseInt(m.group(1));
                    }
                    return Pair.of(i, val);
                }
            }
            return null;
        }

        static ArrayList<Pair<WBLine, Integer>> parseHeader(final String[] pieces) {
            ArrayList<Pair<WBLine, Integer>> columnToTypeAndValue = null;
            columnToTypeAndValue = new ArrayList<>();
            for (int i = 0; i < pieces.length; i++) {
                columnToTypeAndValue.add(i, WBLine.find(pieces[i]));
            }
            return columnToTypeAndValue;
        }
    }

    enum FactbookLine {
        CountryName,
        CountrySlug,
        Value,
        DateOfInformation,
        Ranking,
        Region;

        String get(String[] pieces) {
            return pieces[ordinal()];
        }
    }

    enum FBLiteracy {
        Rank,
        Country,
        Percent;

        String get(String[] pieces) {
            return pieces[ordinal()];
        }
    }

    private static final String GCP = "NY.GNP.MKTP.PP.CD";
    private static final String POP = "SP.POP.TOTL";
    private static final String EMPTY = "..";
    private static Counter2<String> worldbank_gdp = new Counter2<>();
    private static Counter2<String> worldbank_population = new Counter2<>();
    private static Counter2<String> un_literacy = new Counter2<>();

    private static Counter2<String> factbook_gdp = new Counter2<>();
    private static Counter2<String> factbook_population = new Counter2<>();
    private static Counter2<String> factbook_literacy = new Counter2<>();

    private static CountryData other = new CountryData();

    static class CountryData {
        private static Counter2<String> population = new Counter2<>();
        private static Counter2<String> gdp = new Counter2<>();
        private static Counter2<String> literacy = new Counter2<>();
    }

    static final Set<String> missing = new TreeSet<String>();

    public static void main(String[] args) throws IOException {

        System.out.println(
                "Code" + "\t" + "Name" + "\t" + "Pop" + "\t" + "GDP-PPP" + "\t" + "UN Literacy");

        for (String country : StandardCodes.make().getGoodCountries()) {
            showCountryData(country);
        }
        Set<String> outliers = new TreeSet<>();
        outliers.addAll(factbook_population.keySet());
        outliers.addAll(worldbank_population.keySet());
        outliers.addAll(factbook_gdp.keySet());
        outliers.addAll(worldbank_gdp.keySet());
        outliers.addAll(un_literacy.keySet());
        for (Iterator<String> it = outliers.iterator(); it.hasNext(); ) {
            if (StandardCodes.isCountry(it.next())) {
                it.remove();
            }
        }
        // outliers.remove("AN");
        if (outliers.size() != 0) {
            System.out.println("Mistakes: data for non-UN codes");
            for (String country : outliers) {
                showCountryData(country);
            }
            throw new IllegalArgumentException("Mistakes: data for non-country codes");
        }
        Set<String> altNames = new TreeSet<>();
        String oldCode = "";
        for (String display : CountryCodeConverter.names()) {
            String code = CountryCodeConverter.getCodeFromName(display, true, missing);
            String icu = ULocale.getDisplayCountry("und-" + code, "en");
            if (!display.equalsIgnoreCase(icu)) {
                altNames.add(code + "\t" + display + "\t" + icu);
            }
        }
        oldCode = "";
        if (SHOW_ALTERNATE_NAMES) {
            for (String altName : altNames) {
                String[] pieces = altName.split("\t");
                String code = pieces[0];
                if (code.equals("ZZ")) continue;
                if (!code.equals(oldCode)) {
                    oldCode = code;
                    System.out.println();
                }
                System.out.println(code + "; " + pieces[2] + "; " + pieces[1]);
                // System.out.println("<territory type=\"" + code + "\" alt=\"v" + (++alt) + "\">" +
                // pieces[1] +
                // "</territory> <!-- " + pieces[2] + " -->");
            }
        }
        if (!missing.isEmpty()) {
            throw new RuntimeException(
                    "Could not load codes for: "
                            + ListFormat.getInstance(Locale.getDefault()).format(missing));
        }
    }

    private static void showCountryData(String country) {
        number.setMaximumFractionDigits(0);
        System.out.println(
                country
                        + "\t"
                        + ULocale.getDisplayCountry("und-" + country, "en")
                        + "\t"
                        + number.format(getPopulation(country))
                        + "\t"
                        + number.format(getGdp(country))
                        + "\t"
                        + percent.format(getLiteracy(country) / 100));
    }

    /**
     * Gets the percent of people that can read in a particular country. Values are in the range 0
     * to 100
     */
    public static Double getLiteracy(String country) {
        return firstNonZero(
                factbook_literacy.getCount(country),
                un_literacy.getCount(country),
                CountryData.literacy.getCount(country));
    }

    public static Double getGdp(String country) {
        return firstNonZero(
                factbook_gdp.getCount(country),
                worldbank_gdp.getCount(country),
                CountryData.gdp.getCount(country));
    }

    public static Double getPopulation(String country) {
        return firstNonZero(
                factbook_population.getCount(country),
                worldbank_population.getCount(country),
                CountryData.population.getCount(country));
    }

    private static Double firstNonZero(Double... items) {
        for (Double item : items) {
            if (item.doubleValue() != 0) {
                return item;
            }
        }
        return 0.0;
    }

    static String[] splitCommaSeparated(String line) {
        // items are separated by ','
        // each item is of the form abc...
        // or "..." (required if a comma or quote is contained)
        // " in a field is represented by ""
        List<String> result = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); ++i) {
            char ch = line.charAt(i); // don't worry about supplementaries
            switch (ch) {
                case '"':
                    inQuote = !inQuote;
                    // at start or end, that's enough
                    // if get a quote when we are not in a quote, and not at start, then add it and
                    // return to inQuote
                    if (inQuote && item.length() != 0) {
                        item.append('"');
                        inQuote = true;
                    }
                    break;
                case ',':
                    if (!inQuote) {
                        result.add(item.toString());
                        item.setLength(0);
                    } else {
                        item.append(ch);
                    }
                    break;
                default:
                    item.append(ch);
                    break;
            }
        }
        result.add(item.toString());
        return result.toArray(new String[result.size()]);
    }

    private static void loadFactbookInfo(String filename, final Counter2<String> factbookGdp)
            throws IOException {
        CldrUtility.handleFile(
                filename,
                new LineHandler() {
                    @Override
                    public boolean handle(String line) {
                        String[] pieces = splitCommaSeparated(line);
                        String countryName = FactbookLine.CountryName.get(pieces);
                        if (countryName.equals("name")) {
                            return false;
                        }
                        String code =
                                CountryCodeConverter.getCodeFromName(countryName, true, missing);
                        if (code == null) {
                            return false;
                        }
                        if (!StandardCodes.isCountry(code)) {
                            if (ADD_POP) {
                                System.out.println("Skipping factbook info for: " + code);
                            }
                            return false;
                        }
                        code = code.toUpperCase(Locale.ENGLISH);
                        String valueString = FactbookLine.Value.get(pieces).trim();
                        if (valueString.startsWith("$")) {
                            valueString = valueString.substring(1);
                        }
                        valueString = valueString.replace(",", "");
                        double value = Double.parseDouble(valueString.trim());
                        factbookGdp.add(code, value);
                        if (ADD_POP) {
                            System.out.println("Factbook gdp:\t" + code + "\t" + value);
                        }
                        return true;
                    }
                });
    }

    static final NumberFormat dollars = NumberFormat.getCurrencyInstance(ULocale.US);
    static final NumberFormat number = NumberFormat.getNumberInstance(ULocale.US);
    static final NumberFormat percent = NumberFormat.getPercentInstance(ULocale.US);

    static class MyLineHandler implements LineHandler {
        CountryData countryData;

        public MyLineHandler(CountryData countryData) {
            super();
            this.countryData = countryData;
        }

        @Override
        public boolean handle(String line) throws ParseException {
            if (line.startsWith("#")) return true;
            if (line.length() == 0) {
                return true;
            }
            String[] pieces = line.split(";");
            final String code = pieces[0].trim();
            if (code.equals("Code")) {
                return false;
            }
            // Code;Name;Type;Data;Source
            final String typeString = pieces[2].trim();
            final String data = pieces[3].trim();
            if (typeString.equals("gdp-ppp")) {
                if (StandardCodes.isCountry(data)) {
                    Double otherPop = getPopulation(data);
                    Double otherGdp = getGdp(data);
                    Double myPop = getPopulation(code);
                    if (myPop.doubleValue() == 0
                            || otherPop.doubleValue() == 0
                            || otherGdp.doubleValue() == 0) {
                        otherPop = getPopulation(data);
                        otherGdp = getPopulation(data);
                        myPop = getPopulation(code);
                        throw new IllegalArgumentException("Zero population");
                    }
                    CountryData.gdp.add(code, otherGdp * myPop / otherPop);
                } else {
                    CountryData.gdp.add(code, dollars.parse(data).doubleValue());
                }
            } else if (typeString.equals("population")) {
                if (StandardCodes.isCountry(data)) {
                    throw new IllegalArgumentException("Population can't use other country's");
                }
                CountryData.population.add(code, number.parse(data).doubleValue());
            } else if (typeString.equals("literacy")) {
                if (StandardCodes.isCountry(data)) {
                    Double otherPop = getLiteracy(data);
                    CountryData.literacy.add(code, otherPop);
                } else {
                    CountryData.literacy.add(code, number.parse(data).doubleValue());
                }
            } else {
                throw new IllegalArgumentException("Illegal type");
            }
            return true;
        }
    }

    static final UnicodeSet DIGITS = new UnicodeSet("[:Nd:]").freeze();

    private static void loadFactbookLiteracy() throws IOException {
        final String filename = "external/factbook_literacy.txt";
        CldrUtility.handleFile(
                filename,
                new LineHandler() {
                    @Override
                    public boolean handle(String line) {
                        String[] pieces = line.split("\\t");
                        String code =
                                CountryCodeConverter.getCodeFromName(
                                        FBLiteracy.Country.get(pieces), true, missing);
                        if (code == null) {
                            return false;
                        }
                        if (!StandardCodes.isCountry(code)) {
                            if (ADD_POP) {
                                System.out.println("Skipping factbook literacy for: " + code);
                            }
                            return false;
                        }
                        code = code.toUpperCase(Locale.ENGLISH);
                        String valueString =
                                FBLiteracy.Percent.get(pieces)
                                        .trim(); // Values are in the range 0 to 100
                        double percent = Double.parseDouble(valueString);
                        factbook_literacy.put(code, percent);
                        if (ADD_POP) {
                            System.out.println("Factbook literacy:\t" + code + "\t" + percent);
                        }
                        code = null;
                        return true;
                    }
                });
    }

    private static void loadWorldBankInfo() throws IOException {
        final String filename = "external/world_bank_data.csv";

        // List<List<String>> data = SpreadSheet.convert(CldrUtility.getUTF8Data(filename));

        CldrUtility.handleFile(
                filename,
                new LineHandler() {
                    ArrayList<Pair<WBLine, Integer>> columnToTypeAndValue = null;

                    @Override
                    public boolean handle(String line) {
                        String[] pieces = splitCommaSeparated(line);
                        if (columnToTypeAndValue == null) {
                            columnToTypeAndValue = WBLine.parseHeader(pieces);
                            return false;
                        }

                        final HashMap<Pair<WBLine, Integer>, String> lineAsHash = new HashMap<>();
                        for (int i = 0; i < pieces.length; i++) {
                            lineAsHash.put(columnToTypeAndValue.get(i), pieces[i]);
                        }
                        // String[] pieces = line.substring(1, line.length() - 2).split("\"\t\"");
                        final String seriesCode = lineAsHash.get(Pair.of(WBLine.Series_Code, 0));

                        // find the last year
                        String last = null;

                        for (int n = 0; n < columnToTypeAndValue.size(); n++) {
                            // assume the years are in ascending order
                            Pair<WBLine, Integer> i = columnToTypeAndValue.get(n);
                            if (i.getFirst() == WBLine.Year) {
                                String current = pieces[n];
                                if (current.length() != 0 && !current.equals(EMPTY)) {
                                    last = current;
                                }
                            }
                        }
                        if (last == null) {
                            return false;
                        }
                        final String countryName = lineAsHash.get(Pair.of(WBLine.Country_Name, 0));
                        String country =
                                CountryCodeConverter.getCodeFromName(countryName, true, missing);
                        if (country == null) {
                            return false;
                        }
                        if (!StandardCodes.isCountry(country)) {
                            if (ADD_POP) {
                                System.out.println("Skipping worldbank info for: " + country);
                            }
                            return false;
                        }
                        double value;
                        try {
                            value = Double.parseDouble(last);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException(
                                    "File changed format: need to modify code");
                        }
                        if (seriesCode.equals(GCP)) {
                            worldbank_gdp.add(country, value);
                        } else if (seriesCode.equals(POP)) {
                            worldbank_population.add(country, value);
                        } else {
                            throw new IllegalArgumentException();
                        }
                        return true;
                    }
                });
    }

    static void loadUnLiteracy() throws IOException {
        for (final Pair<String, Double> p : getUnLiteracy(null)) {
            un_literacy.add(p.getFirst(), p.getSecond());
        }
    }

    /**
     * @param hadErr on return, true if there were errs
     * @return list of code,percent values
     * @throws IOException
     */
    static List<Pair<String, Double>> getUnLiteracy(Output<Boolean> hadErr) throws IOException {
        List<Pair<String, Double>> result = new LinkedList<>();
        UnLiteracyParser ulp;
        try {
            ulp = new UnLiteracyParser().read();
        } catch (Throwable t) {
            throw new IOException("Could not read UN data " + UnLiteracyParser.UN_LITERACY, t);
        }

        for (final Map.Entry<String, UnLiteracyParser.PerCountry> e : ulp.perCountry.entrySet()) {
            final String country = e.getKey();
            final String latest = e.getValue().latest();
            final UnLiteracyParser.PerYear py = e.getValue().perYear.get(latest);

            Long literate = py.total(UnLiteracyParser.LITERATE);
            Long illiterate = py.total(UnLiteracyParser.ILLITERATE);

            String code = CountryCodeConverter.getCodeFromName(country, true, missing);
            if (code == null) {
                if (hadErr != null) {
                    hadErr.value = true;
                }
                continue;
            }
            if (!StandardCodes.isCountry(code)) {
                if (ADD_POP) {
                    System.out.println("Skipping UN info for: " + code);
                }
                continue;
            }
            double total = literate + illiterate;
            double percent =
                    ((double) literate)
                            * 100
                            / total; // Multiply by 100 to put values in range 0 to 100
            result.add(Pair.of(code, percent));
        }
        if (result.isEmpty()) {
            hadErr.value = true;
        }
        return result;
    }

    static {
        try {
            loadFactbookLiteracy();
            loadUnLiteracy();

            loadFactbookInfo("external/factbook_gdp_ppp.csv", factbook_gdp);
            loadFactbookInfo("external/factbook_population.csv", factbook_population);
            CldrUtility.handleFile("external/other_country_data.txt", new MyLineHandler(other));

            loadWorldBankInfo();
            StandardCodes sc = StandardCodes.make();
            StringBuilder myErrors = new StringBuilder();
            for (String territory : sc.getGoodAvailableCodes("territory")) {
                if (!StandardCodes.isCountry(territory)) {
                    continue;
                }
                double gdp = getGdp(territory);
                double literacy = getLiteracy(territory);
                double population = getPopulation(territory);
                if (population == 0) {
                    // AX;Aland Islands;population;26,200;www.aland.ax
                    myErrors.append(
                            "\n"
                                    + territory
                                    + ";"
                                    + sc.getData("territory", territory)
                                    + ";population;0;reason");
                }
                if (gdp == 0) {
                    myErrors.append(
                            "\n"
                                    + territory
                                    + ";"
                                    + sc.getData("territory", territory)
                                    + ";gdp-ppp;0;reason");
                }
                if (literacy == 0) {
                    myErrors.append(
                            "\n"
                                    + territory
                                    + ";"
                                    + sc.getData("territory", territory)
                                    + ";literacy;0;reason");
                }
            }
            if (myErrors.length() != 0) {
                throw new IllegalArgumentException(
                        "Missing Country values, the following and add to external/other_country_data to fix, changing the 0 to the real value:"
                                + myErrors);
            }
        } catch (IOException e) {
        }
    }
}
