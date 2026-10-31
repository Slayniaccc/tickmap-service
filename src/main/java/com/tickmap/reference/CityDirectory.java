package com.tickmap.reference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Known UK cities, with the region and coordinates the upstream data doesn't include. */
@Component
public class CityDirectory {

    public static final String UNKNOWN_REGION = "Unknown";

    public record City(String name, String region, double latitude, double longitude) {}

    private final Map<String, City> byKey = new LinkedHashMap<>();

    public CityDirectory() {
        add(new City("London", "London", 51.5072, -0.1276));
        add(new City("Birmingham", "West Midlands", 52.4862, -1.8904));
        add(new City("Manchester", "North West", 53.4808, -2.2426));
        add(new City("Liverpool", "North West", 53.4084, -2.9916));
        add(new City("Leeds", "Yorkshire and the Humber", 53.8008, -1.5491));
        add(new City("Sheffield", "Yorkshire and the Humber", 53.3811, -1.4701));
        add(new City("Newcastle", "North East", 54.9783, -1.6178), "newcastle upon tyne");
        add(new City("Nottingham", "East Midlands", 52.9548, -1.1581));
        add(new City("Leicester", "East Midlands", 52.6369, -1.1398));
        add(new City("Bristol", "South West", 51.4545, -2.5879));
        add(new City("Southampton", "South East", 50.9097, -1.4044));
        add(new City("Cardiff", "Wales", 51.4816, -3.1791));
        add(new City("Edinburgh", "Scotland", 55.9533, -3.1883));
        add(new City("Glasgow", "Scotland", 55.8642, -4.2518));
    }

    private void add(City city, String... aliases) {
        byKey.put(key(city.name()), city);
        for (String alias : aliases) {
            byKey.put(key(alias), city);
        }
    }

    // Ignores case, extra whitespace and punctuation.
    public Optional<City> find(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key(rawName)));
    }

    // Case-insensitive; returns the stored spelling, including "Unknown".
    public Optional<String> findRegion(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        String k = key(rawName);
        if (k.equals(key(UNKNOWN_REGION))) {
            return Optional.of(UNKNOWN_REGION);
        }
        return byKey.values().stream().map(City::region).filter(r -> key(r).equals(k)).findFirst();
    }

    public Collection<City> all() {
        // Aliases map to the same City, so de-duplicate.
        return byKey.values().stream().distinct().toList();
    }

    static String key(String raw) {
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
