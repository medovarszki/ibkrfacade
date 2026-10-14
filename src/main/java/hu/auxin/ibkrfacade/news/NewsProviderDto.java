package hu.auxin.ibkrfacade.news;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NewsProviderDto(@JsonProperty("provider_code") String providerCode,
                              @JsonProperty("provider_name") String providerName) {
}
