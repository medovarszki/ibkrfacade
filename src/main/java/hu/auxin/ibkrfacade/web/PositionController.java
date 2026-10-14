package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@Tag(name = "Account")
@RestController
public class PositionController {

    private final PositionManagerService positionManagerService;

    PositionController(PositionManagerService positionManagerService) {
        this.positionManagerService = positionManagerService;
    }

    @Operation(summary = "Returns the open positions with average cost, market value and P&L.")
    @GetMapping("/positions")
    Collection<PositionHolder> getAllPositions() {
        return positionManagerService.getAllPositions();
    }
}
