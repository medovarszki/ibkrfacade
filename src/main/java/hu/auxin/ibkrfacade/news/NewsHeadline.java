package hu.auxin.ibkrfacade.news;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record NewsHeadline(
        @Schema(description = "Time of the news as reported by TWS (UTC)", example = "2026-10-14 13:30:00.0") String time,
        @JsonProperty("provider_code") String providerCode,
        @JsonProperty("article_id") String articleId,
        String headline) {
}
