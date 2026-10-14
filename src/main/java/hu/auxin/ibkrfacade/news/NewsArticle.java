package hu.auxin.ibkrfacade.news;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record NewsArticle(
        @JsonProperty("provider_code") String providerCode,
        @JsonProperty("article_id") String articleId,
        @JsonProperty("article_type") @Schema(description = "text, or binary (base64 encoded PDF)") String articleType,
        String text) {
}
