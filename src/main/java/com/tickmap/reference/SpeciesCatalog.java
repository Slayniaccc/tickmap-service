package com.tickmap.reference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Known species (common name to Latin name), as they appear in the upstream data. */
@Component
public class SpeciesCatalog {

    public record Species(String commonName, String latinName) {}

    private final Map<String, Species> byCommon = new LinkedHashMap<>();
    private final Map<String, Species> byLatin = new LinkedHashMap<>();

    public SpeciesCatalog() {
        add(new Species("Passerine tick", "Dermacentor frontalis"));
        add(new Species("Fox/badger tick", "Ixodes canisuga"));
        add(new Species("Southern rodent tick", "Ixodes acuminatus"));
        add(new Species("Marsh tick", "Ixodes apronophorus"));
        add(new Species("Tree-hole tick", "Ixodes arboricola"));
    }

    private void add(Species s) {
        byCommon.put(key(s.commonName()), s);
        byLatin.put(key(s.latinName()), s);
    }

    public Optional<Species> findByCommonName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byCommon.get(key(name)));
    }

    public Optional<Species> findByLatinName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byLatin.get(key(name)));
    }

    public Collection<Species> all() {
        return byCommon.values();
    }

    private static String key(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
