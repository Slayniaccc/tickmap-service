package com.tickmap.sighting;

import java.time.Instant;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * sourceId is the upstream id (null for user submissions). fingerprint is a hash of the
 * normalised time, city and species, so the same sighting can't be stored twice under different ids.
 */
@Entity
@Table(name = "sightings", indexes = {
        @Index(name = "ix_sightings_sighted_at", columnList = "sightedAt"),
        @Index(name = "ix_sightings_city_sighted_at", columnList = "city, sightedAt"),
        @Index(name = "ix_sightings_region_sighted_at", columnList = "region, sightedAt"),
        @Index(name = "ix_sightings_species", columnList = "species")
})
public class Sighting {

    public enum Source { UPSTREAM, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(unique = true, length = 64)
    private String sourceId;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(nullable = false)
    private LocalDateTime sightedAt;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String region;

    private Double latitude;
    private Double longitude;

    @Column(nullable = false, length = 100)
    private String species;

    @Column(length = 150)
    private String latinName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Sighting() {
    }

    public Sighting(String sourceId, Source source) {
        this.sourceId = sourceId;
        this.source = source;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Returns true if the content changed. */
    public boolean applyContent(NormalizedSighting n) {
        boolean changed = !n.fingerprint().equals(fingerprint);
        this.fingerprint = n.fingerprint();
        this.sightedAt = n.sightedAt();
        this.city = n.city();
        this.region = n.region();
        this.latitude = n.latitude();
        this.longitude = n.longitude();
        this.species = n.species();
        this.latinName = n.latinName();
        if (changed) {
            this.updatedAt = Instant.now();
        }
        return changed;
    }

    public Long getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getFingerprint() { return fingerprint; }
    public LocalDateTime getSightedAt() { return sightedAt; }
    public String getCity() { return city; }
    public String getRegion() { return region; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getSpecies() { return species; }
    public String getLatinName() { return latinName; }
    public Source getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
