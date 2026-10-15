package hu.auxin.ibkrfacade.order;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Order;
import com.ib.client.OrderCancel;
import com.ib.client.OrderState;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.tws.AccountMode;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private static final int AAPL = 265598;
    private static final int CLIENT_ID = 1;

    private TwsConnection connection;
    private EClientSocket client;
    private ContractManagerService contractManagerService;
    private MarketDataService marketDataService;
    private OrderManagerService orderManagerService;
    private InMemoryIdempotencyStore idempotencyStore;
    private final AtomicInteger nextOrderId = new AtomicInteger(100);

    @BeforeEach
    void setUp() {
        connection = mock(TwsConnection.class);
        client = mock(EClientSocket.class);
        when(connection.client()).thenReturn(client);
        when(connection.isReady()).thenReturn(true);
        when(connection.hasFarmConnectivity()).thenReturn(true);
        when(connection.getAccountMode()).thenReturn(AccountMode.PAPER);
        when(connection.getAccounts()).thenReturn(List.of("DU1234567"));
        when(connection.getClientId()).thenReturn(CLIENT_ID);
        when(connection.allocateOrderId()).thenAnswer(invocation -> nextOrderId.getAndIncrement());

        Contract contract = new Contract();
        contract.conid(AAPL);
        contract.symbol("AAPL");
        contract.secType(Types.SecType.STK);
        contract.currency("USD");
        contract.exchange("SMART");
        contractManagerService = mock(ContractManagerService.class);
        when(contractManagerService.getContractHolder(AAPL)).thenReturn(new ContractHolder(contract, new ContractDetails()));

        marketDataService = mock(MarketDataService.class);
        when(marketDataService.awaitQuote(any(), any(), any())).thenReturn(quote(Instant.now()));

        orderManagerService = new OrderManagerService(connection, new TwsRequestRegistry(Duration.ofSeconds(1)));
        idempotencyStore = new InMemoryIdempotencyStore();
    }

    private OrderService service(boolean allowLive) {
        return new OrderService(connection, contractManagerService, marketDataService, orderManagerService,
                idempotencyStore, allowLive, Duration.ofMinutes(15), Duration.ofMillis(10), Duration.ofMillis(100));
    }

    private static PlaceOrderRequest limitBuy(String clientOrderId) {
        return new PlaceOrderRequest(clientOrderId, AAPL, OrderSide.BUY, new BigDecimal("10"), OrderKind.LMT, 150.5,
                null, null, null, null);
    }

    private static MarketPrice quote(Instant time) {
        return MarketPrice.of(AAPL, 150.0, 150.1, null, time, time, null, true, null, null, Instant.now(), Duration.ofMinutes(15));
    }

    // -- placement

    @Test
    void placesOrderWithClientOrderIdInOrderRef() {
        OrderResult result = service(false).place(limitBuy("auxin-1"));

        assertThat(result.created()).isTrue();
        assertThat(result.order().orderId()).isEqualTo(100);
        assertThat(result.order().clientOrderId()).isEqualTo("auxin-1");
        assertThat(result.order().status()).isEqualTo("PendingSubmit");
        assertThat(result.order().duplicate()).isFalse();

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        verify(client).placeOrder(eq(100), any(Contract.class), order.capture());
        assertThat(order.getValue().orderRef()).isEqualTo("auxin-1");
        assertThat(order.getValue().getAction()).isEqualTo("BUY");
        assertThat(order.getValue().getOrderType()).isEqualTo("LMT");
        assertThat(order.getValue().lmtPrice()).isEqualTo(150.5);
        assertThat(order.getValue().totalQuantity().value()).isEqualByComparingTo("10");
        assertThat(order.getValue().getTif()).isEqualTo("DAY");
        assertThat(order.getValue().transmit()).isTrue();
        assertThat(idempotencyStore.reservations.get("auxin-1").orderId()).isEqualTo(100);
    }

    @Test
    void repeatedRequestReturnsTheExistingOrderAndNeverPlacesAgain() {
        OrderService service = service(false);
        OrderResult first = service.place(limitBuy("auxin-2"));
        OrderResult second = service.place(limitBuy("auxin-2"));

        assertThat(second.created()).isFalse();
        assertThat(second.order().duplicate()).isTrue();
        assertThat(second.order().orderId()).isEqualTo(first.order().orderId());
        verify(client, times(1)).placeOrder(anyInt(), any(), any());
    }

    @Test
    void concurrentRequestsWithTheSameClientOrderIdPlaceOneOrder() throws Exception {
        OrderService service = service(false);
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return service.place(limitBuy("auxin-concurrent"));
            }));
        }
        start.countDown();
        List<OrderResult> results = new ArrayList<>();
        for (Future<OrderResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        verify(client, times(1)).placeOrder(anyInt(), any(), any());
        assertThat(results).filteredOn(OrderResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.order().orderId()).containsOnly(100);
    }

    @Test
    void reusingClientOrderIdWithDifferentParametersIsRejected() {
        OrderService service = service(false);
        service.place(limitBuy("auxin-3"));
        PlaceOrderRequest different = new PlaceOrderRequest("auxin-3", AAPL, OrderSide.SELL, new BigDecimal("10"),
                OrderKind.LMT, 150.5, null, null, null, null);

        assertThatThrownBy(() -> service.place(different))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertCode(e, HttpStatus.CONFLICT, "CLIENT_ORDER_ID_REUSED"));
        verify(client, times(1)).placeOrder(anyInt(), any(), any());
    }

    @Test
    void reservedClientOrderIdUnknownToTwsIsNeverSentAgain() {
        // eg. the facade crashed after the reservation, before or while sending the order
        PlaceOrderRequest request = limitBuy("auxin-4");
        idempotencyStore.reservations.put("auxin-4",
                new OrderReservation("auxin-4", 77, request.fingerprint(), Instant.parse("2026-10-14T10:00:00Z")));

        OrderResult result = service(false).place(request);

        assertThat(result.created()).isFalse();
        assertThat(result.order().status()).isEqualTo(OrderRecord.STATUS_UNKNOWN);
        assertThat(result.order().orderId()).isEqualTo(77);
        assertThat(result.order().duplicate()).isTrue();
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void orderKnownFromTwsByOrderRefIsNotPlacedAgain() {
        // eg. Redis was flushed, but the open orders reported by TWS contain the orderRef
        Order existing = new Order();
        existing.orderId(55);
        existing.clientId(CLIENT_ID);
        existing.permId(999L);
        existing.orderRef("auxin-5");
        existing.totalQuantity(Decimal.get(10));
        OrderState state = mock(OrderState.class);
        when(state.getStatus()).thenReturn("Submitted");
        Contract contract = new Contract();
        contract.conid(AAPL);
        orderManagerService.onOpenOrder(55, contract, existing, state);

        OrderResult result = service(false).place(limitBuy("auxin-5"));

        assertThat(result.created()).isFalse();
        assertThat(result.order().orderId()).isEqualTo(55);
        assertThat(result.order().status()).isEqualTo("Submitted");
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void statusCallbacksAreReflectedInTheResponse() {
        doAnswer(invocation -> {
            orderManagerService.onOrderStatus(invocation.getArgument(0), 12345L, "Submitted", Decimal.ZERO,
                    Decimal.get(10), 0);
            return null;
        }).when(client).placeOrder(anyInt(), any(), any());

        OrderResponse order = service(false).place(limitBuy("auxin-6")).order();

        assertThat(order.status()).isEqualTo("Submitted");
        assertThat(order.permId()).isEqualTo(12345L);
        assertThat(order.active()).isTrue();
    }

    @Test
    void rejectionByTwsIsReported() {
        doAnswer(invocation -> {
            orderManagerService.onOrderError(invocation.getArgument(0), 201, "Order rejected - reason: margin", false);
            return null;
        }).when(client).placeOrder(anyInt(), any(), any());

        OrderResponse order = service(false).place(limitBuy("auxin-7")).order();

        assertThat(order.status()).isEqualTo(OrderRecord.STATUS_REJECTED);
        assertThat(order.errorCode()).isEqualTo(201);
        assertThat(order.errorMessage()).contains("margin");
        assertThat(order.active()).isFalse();
    }

    // -- safety checks

    @Test
    void liveAccountIsRejectedByDefault() {
        when(connection.getAccountMode()).thenReturn(AccountMode.LIVE);

        assertThatThrownBy(() -> service(false).place(limitBuy("auxin-8")))
                .satisfies(e -> assertCode(e, HttpStatus.FORBIDDEN, "LIVE_TRADING_DISABLED"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
        assertThat(idempotencyStore.reservations).isEmpty();
    }

    @Test
    void liveAccountIsAllowedWhenEnabled() {
        when(connection.getAccountMode()).thenReturn(AccountMode.LIVE);

        assertThat(service(true).place(limitBuy("auxin-9")).created()).isTrue();
        verify(client, times(1)).placeOrder(anyInt(), any(), any());
    }

    @Test
    void unknownAccountModeIsRejected() {
        when(connection.getAccountMode()).thenReturn(AccountMode.UNKNOWN);

        assertThatThrownBy(() -> service(true).place(limitBuy("auxin-10")))
                .satisfies(e -> assertCode(e, HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_MODE_UNKNOWN"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void notReadyConnectionIsRejected() {
        when(connection.isReady()).thenReturn(false);

        assertThatThrownBy(() -> service(false).place(limitBuy("auxin-11")))
                .satisfies(e -> assertCode(e, HttpStatus.SERVICE_UNAVAILABLE, "TWS_NOT_CONNECTED"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void missingMarketPriceIsRejectedAndTheClientOrderIdStaysUsable() {
        when(marketDataService.awaitQuote(any(), any(), any()))
                .thenReturn(MarketPrice.of(AAPL, null, null, null, null, null, null, true, "354: Not subscribed", null,
                        Instant.now(), Duration.ofMinutes(15)));
        OrderService service = service(false);

        assertThatThrownBy(() -> service.place(limitBuy("auxin-12")))
                .satisfies(e -> assertCode(e, HttpStatus.UNPROCESSABLE_CONTENT, "MARKET_DATA_UNAVAILABLE"))
                .hasMessageContaining("354");
        verify(client, never()).placeOrder(anyInt(), any(), any());
        assertThat(idempotencyStore.reservations).isEmpty();

        // when the market data arrives, the same client order id can be used
        when(marketDataService.awaitQuote(any(), any(), any())).thenReturn(quote(Instant.now()));
        assertThat(service.place(limitBuy("auxin-12")).created()).isTrue();
    }

    @Test
    void staleMarketPriceIsRejected() {
        when(marketDataService.awaitQuote(any(), any(), any())).thenReturn(quote(Instant.now().minusSeconds(3600)));

        assertThatThrownBy(() -> service(false).place(limitBuy("auxin-13")))
                .satisfies(e -> assertCode(e, HttpStatus.UNPROCESSABLE_CONTENT, "MARKET_DATA_UNAVAILABLE"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void unknownContractIsRejectedWith404() {
        when(contractManagerService.getContractHolder(1))
                .thenThrow(ApiException.notFound("CONTRACT_NOT_FOUND", "No contract found for conid 1"));
        PlaceOrderRequest request = new PlaceOrderRequest("auxin-14", 1, OrderSide.BUY, BigDecimal.ONE, OrderKind.LMT,
                1.0, null, null, null, null);

        assertThatThrownBy(() -> service(false).place(request))
                .satisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void unavailableIdempotencyStoreBlocksTheOrder() {
        idempotencyStore.down = true;

        assertThatThrownBy(() -> service(false).place(limitBuy("auxin-15")))
                .satisfies(e -> assertCode(e, HttpStatus.SERVICE_UNAVAILABLE, "IDEMPOTENCY_STORE_UNAVAILABLE"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    @Test
    void unknownAccountIsRejected() {
        PlaceOrderRequest request = new PlaceOrderRequest("auxin-16", AAPL, OrderSide.BUY, BigDecimal.ONE,
                OrderKind.LMT, 1.0, null, null, null, "U999");

        assertThatThrownBy(() -> service(false).place(request))
                .satisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "UNKNOWN_ACCOUNT"));
    }

    @Test
    void pricesMustMatchTheOrderType() {
        OrderService service = service(false);
        assertThatThrownBy(() -> service.place(new PlaceOrderRequest("v-1", AAPL, OrderSide.BUY, BigDecimal.ONE,
                OrderKind.LMT, null, null, null, null, null)))
                .satisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "LIMIT_PRICE_REQUIRED"));
        assertThatThrownBy(() -> service.place(new PlaceOrderRequest("v-2", AAPL, OrderSide.BUY, BigDecimal.ONE,
                OrderKind.MKT, 10.0, null, null, null, null)))
                .satisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "UNEXPECTED_LIMIT_PRICE"));
        assertThatThrownBy(() -> service.place(new PlaceOrderRequest("v-3", AAPL, OrderSide.SELL, BigDecimal.ONE,
                OrderKind.STP_LMT, 10.0, null, null, null, null)))
                .satisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "STOP_PRICE_REQUIRED"));
        verify(client, never()).placeOrder(anyInt(), any(), any());
    }

    // -- queries and cancel

    @Test
    void ordersCanBeQueriedByOrderIdAndClientOrderId() {
        OrderService service = service(false);
        service.place(limitBuy("auxin-17"));

        assertThat(service.getByOrderId(100).clientOrderId()).isEqualTo("auxin-17");
        assertThat(service.getByClientOrderId("auxin-17").orderId()).isEqualTo(100);
        assertThatThrownBy(() -> service.getByOrderId(1))
                .satisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
        assertThatThrownBy(() -> service.getByClientOrderId("nope"))
                .satisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
    }

    @Test
    void cancelSendsTheCancellationForActiveOrdersOnly() {
        OrderService service = service(false);
        service.place(limitBuy("auxin-18"));

        doAnswer(invocation -> {
            orderManagerService.onOrderStatus(100, 1L, "Cancelled", Decimal.ZERO, Decimal.get(10), 0);
            return null;
        }).when(client).cancelOrder(eq(100), any(OrderCancel.class));

        assertThat(service.cancel(100).status()).isEqualTo("Cancelled");
        verify(client).cancelOrder(eq(100), any(OrderCancel.class));

        assertThatThrownBy(() -> service.cancel(100))
                .satisfies(e -> assertCode(e, HttpStatus.CONFLICT, "ORDER_NOT_ACTIVE"));
        assertThatThrownBy(() -> service.cancel(12345))
                .satisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
    }

    private static void assertCode(Throwable e, HttpStatus status, String code) {
        assertThat(e).isInstanceOf(ApiException.class);
        assertThat(((ApiException) e).getStatus()).isEqualTo(status);
        assertThat(((ApiException) e).getCode()).isEqualTo(code);
    }
}
