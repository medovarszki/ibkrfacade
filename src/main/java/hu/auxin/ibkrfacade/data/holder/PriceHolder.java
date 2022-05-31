package hu.auxin.ibkrfacade.data.holder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;

import java.io.Serializable;

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
