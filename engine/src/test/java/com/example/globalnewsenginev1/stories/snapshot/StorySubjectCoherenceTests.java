package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorySubjectCoherenceTests {

    @Test
    void keepsHyphenatedGuideSubjectsIntactAcrossTypography() {
        String spaced = "Elden Ring Guide: Moon Touched Armor Location";

        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon-Touched Armor Location", spaced)).isTrue();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon‑Touched Armor Location", spaced)).isTrue();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon–Touched Armor Location", spaced)).isTrue();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon—Touched Armor Location", spaced)).isTrue();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide – Moon Touched Armor Location", spaced)).isTrue();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Sun-Touched Armor Location", spaced)).isFalse();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon–Touched Armor Location",
                "Elden Ring Guide: Sun–Touched Armor Location")).isFalse();
        assertThat(StorySubjectCoherence.compatible(
                "Elden Ring Guide: Moon—Touched Armor Location",
                "Elden Ring Guide: Sun—Touched Armor Location")).isFalse();
    }
}
