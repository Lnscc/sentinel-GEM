package com.example.globalnewsenginev1.stories.embedding;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryEmbeddingJobTests {

    @Test
    void processesOnlyTheVisibleVersion() {
        StoryEmbeddingService service = mock(StoryEmbeddingService.class);
        StoryEmbeddingService.ProcessingResult full =
                new StoryEmbeddingService.ProcessingResult(100, 100, 0);
        when(service.processIncremental("visible", false, 100)).thenReturn(full);

        new StoryEmbeddingJob(service, "visible", 100, 10).processIncremental();

        verify(service, times(10)).processIncremental("visible", false, 100);
        verify(service, times(0)).processIncremental("visible", true, 100);
    }
}
