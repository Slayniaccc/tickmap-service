package com.tickmap.sighting;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

@Service
public class SightingService {

    private final SightingRepository sightings;
    private final SightingNormalizer normalizer;

    public SightingService(SightingRepository sightings, SightingNormalizer normalizer) {
        this.sightings = sightings;
        this.normalizer = normalizer;
    }

    public Page<Sighting> search(SightingFilter filter, Pageable pageable) {
        return sightings.findAll(filter.toSpecification(), pageable);
    }

    public Optional<Sighting> find(Long id) {
        return sightings.findById(id);
    }

    public Sighting create(LocalDateTime date, String location, String species, String latinName) {
        NormalizedSighting n = switch (normalizer.normalize(null, date, location, species, latinName)) {
            case NormalizationResult.Accepted a -> a.sighting();
            case NormalizationResult.Rejected r -> {
                ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, r.detail());
                pd.setTitle("Invalid sighting");
                pd.setProperty("reason", r.reason());
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST, pd, null);
            }
        };
        sightings.findByFingerprint(n.fingerprint()).ifPresent(existing -> {
            throw duplicate(existing.getId());
        });
        Sighting s = new Sighting(null, Sighting.Source.USER);
        s.applyContent(n);
        try {
            return sightings.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent insert of the same sighting.
            throw duplicate(sightings.findByFingerprint(n.fingerprint()).map(Sighting::getId).orElse(null));
        }
    }

    private static ErrorResponseException duplicate(Long existingId) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This sighting (same time, city and species) is already recorded");
        pd.setTitle("Duplicate sighting");
        pd.setProperty("existingId", existingId);
        return new ErrorResponseException(HttpStatus.CONFLICT, pd, null);
    }
}
