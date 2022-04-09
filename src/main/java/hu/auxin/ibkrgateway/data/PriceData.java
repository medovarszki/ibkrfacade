package hu.auxin.ibkrgateway.data;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.io.Serializable;

@RedisHash("pricedata")
public class PriceData implements Serializable {

    @Id
    private Integer streamId;

    private Double bid;
    private Double ask;
    private Double lastPrice;
    private Double volume;

}
