package com.example.globalnewsenginev1.stories.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

final class CountryNames {
    // GDELT uses FIPS codes (GM = Germany, not Gambia).
    // Source: https://data.gdeltproject.org/api/v2/guides/LOOKUP-COUNTRIES.TXT
    // German labels; current FIPS corrections: https://download.geonames.org/export/dump/countryInfo.txt
    private static final Map<String, String> NAMES = load();

    static String name(String code) {
        return NAMES.getOrDefault(code, "Unbekanntes Land (" + code + ")");
    }

    private static Map<String, String> load() {
        try (var reader = new BufferedReader(new InputStreamReader(
                CountryNames.class.getResourceAsStream("/stories/gdelt-countries.tsv"), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).map(line -> line.split("\t", 2))
                    .collect(Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1]));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load GDELT country names", exception);
        }
    }
}
