package hu.auxin.ibkrfacade.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.springframework.data.annotation.Id;

import java.io.Serializable;

@Data
public class PriceData implements Serializable {

    @Id
    @JsonIgnore
    private Integer requestId;

    private Double bid;
    private Double ask;
    private Double lastPrice;
    private Double volume;

    public PriceData(int requestId) {
        this.requestId = requestId;
    }

    public PriceData(double bid, double ask) {
        this.bid = bid;
        this.ask = ask;
    }
}
