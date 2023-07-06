package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Types;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Option {

    private String symbol;

    private String expiration;

    private double strike;

    private Types.Right right;
}
