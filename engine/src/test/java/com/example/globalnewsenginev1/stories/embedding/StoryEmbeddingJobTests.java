package com.example.globalnewsenginev1.stories.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryEmbeddingJobTests {

    @Test
    void reservesOneBatchForNonVisibleVersions() {
        StoryEmbeddingService service = mock(StoryEmbeddingService.class);
        StoryEmbeddingService.ProcessingResult full =
                new StoryEmbeddingService.ProcessingResult(100, 100, 0);
        when(service.processIncremental("visible", false, 100)).thenReturn(full);
        when(service.processIncremental("visible", true, 100)).thenReturn(full);

        new StoryEmbeddingJob(service, "visible", 100, 10).processIncremental();

        verify(service, times(9)).processIncremental("visible", false, 100);
        verify(service).processIncremental("visible", true, 100);
    }

    @Test
    void requiresCapacityForVisibleAndNonVisibleVersions() {
        assertThatThrownBy(() -> new StoryEmbeddingJob(mock(StoryEmbeddingService.class),
                "visible", 100, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
