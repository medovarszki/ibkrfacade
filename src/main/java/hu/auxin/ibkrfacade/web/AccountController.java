package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.account.AccountService;
import hu.auxin.ibkrfacade.account.AccountSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account")
@RestController
public class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "Account summary: NAV (NetLiquidation), cash, available funds, buying power, margin "
            + "requirements, base currency and the time of the query.")
    @GetMapping("/account/summary")
    AccountSummary getSummary(@Parameter(description = "Account id, defaults to the first managed account")
                              @RequestParam(required = false) String account) {
        return accountService.getSummary(account);
    }
}
