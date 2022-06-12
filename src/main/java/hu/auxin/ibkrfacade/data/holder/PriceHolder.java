package hu.auxin.ibkrfacade.data.holder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.annotation.Id;

import java.io.Serializable;

@Schema(description = "Represents a bid/ask pair.")
@Data
public class PriceHolder implements Serializable {

    @Id
    @JsonIgnore
    private Integer requestId;

    private Double bid;
    private Double ask;

    public PriceHolder(double bid, double ask) {
        this.bid = bid;
        this.ask = ask;
    }
}
