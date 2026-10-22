package com.example.globalnewsenginev1.gdelt.staging.model;

import java.time.LocalDate;

public record GdeltStageEvent(
        Long globalEventId,
        LocalDate eventDate,
        String actor1Code,
        String actor1Name,
        String actor1CountryCode,
        String actor2Code,
        String actor2Name,
        String actor2CountryCode,
        String eventCode,
        Integer quadClass,
        Double goldsteinScale,
        Double avgTone,
        Integer actionGeoType,
        String actionGeoName,
        String actionGeoCountryCode,
        String actionGeoAdm1Code,
        String actionGeoAdm2Code,
        Double actionGeoLatitude,
        Double actionGeoLongitude,
        String actionGeoFeatureId,
        String sourceUrl
) {
}
