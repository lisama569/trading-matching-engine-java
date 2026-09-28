package com.github.lisama569.tradingmatchingenginejava.web;

import com.github.lisama569.tradingmatchingenginejava.common.TradingException;
import com.github.lisama569.tradingmatchingenginejava.model.Account;
import com.github.lisama569.tradingmatchingenginejava.model.Order;
import com.github.lisama569.tradingmatchingenginejava.model.Position;
import com.github.lisama569.tradingmatchingenginejava.model.RejectCode;
import com.github.lisama569.tradingmatchingenginejava.model.Trade;
import com.github.lisama569.tradingmatchingenginejava.service.AccountService;
import com.github.lisama569.tradingmatchingenginejava.service.MarketDataService;
import com.github.lisama569.tradingmatchingenginejava.service.OrderService;
import com.github.lisama569.tradingmatchingenginejava.web.dto.AccountView;
import com.github.lisama569.tradingmatchingenginejava.web.dto.BookView;
import com.github.lisama569.tradingmatchingenginejava.web.dto.CancelOrderRequest;
import com.github.lisama569.tradingmatchingenginejava.web.dto.OrderResponse;
import com.github.lisama569.tradingmatchingenginejava.web.dto.PlaceOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TradingController {

    private static final Logger log = LoggerFactory.getLogger(TradingController.class);

    private final OrderService orderService;
    private final AccountService accountService;
    private final MarketDataService marketDataService;

    public TradingController(OrderService orderService, AccountService accountService,
                             MarketDataService marketDataService) {
        this.orderService = orderService;
        this.accountService = accountService;
        this.marketDataService = marketDataService;
    }

    @PostMapping("/orders")
    public OrderResponse placeOrder(@RequestBody PlaceOrderRequest req) {
        requireNonNull(req.getAccountId(), "accountId");
        requireNonNull(req.getSymbol(), "symbol");
        requireNonNull(req.getSide(), "side");
        requireNonNull(req.getType(), "type");
        requireNonNull(req.getQty(), "qty");

        OrderService.OrderResult result = orderService.submit(
                req.getAccountId(), req.getSymbol(), req.getSide(), req.getType(),
                req.getPrice(), req.getQty());

        List<OrderResponse.TradeView> views = new ArrayList<>();
        for (Trade t : result.trades()) {
            long counterparty = t.takerOrderId() == result.order().getId()
                    ? t.makerOrderId() : t.takerOrderId();
            views.add(new OrderResponse.TradeView(t.tradeId(), t.price(), t.qty(),
                    counterparty, t.timestampMillis()));
        }
        return OrderResponse.of(result.order(), views);
    }

    @PostMapping("/orders/cancel")
    public OrderResponse cancel(@RequestBody CancelOrderRequest req) {
        requireNonNull(req.getAccountId(), "accountId");
        requireNonNull(req.getOrderId(), "orderId");
        Order order = orderService.cancel(req.getAccountId(), req.getOrderId());
        return OrderResponse.of(order, List.of());
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@PathVariable long orderId) {
        Order order = orderService.getOrder(orderId);
        return OrderResponse.of(order, List.of());
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView getAccount(@PathVariable long accountId) {
        return accountService.withAccount(accountId, this::toAccountView);
    }

    @GetMapping("/books/{symbol}")
    public BookView getBook(@PathVariable String symbol,
                            @RequestParam(required = false) Integer depth) {
        return marketDataService.getBook(symbol, depth);
    }

    @GetMapping("/trades/{symbol}")
    public List<OrderResponse.TradeView> getTrades(@PathVariable String symbol,
                                                   @RequestParam(required = false) Integer limit) {
        List<OrderResponse.TradeView> views = new ArrayList<>();
        for (Trade t : marketDataService.getRecentTrades(symbol, limit)) {
            views.add(new OrderResponse.TradeView(t.tradeId(), t.price(), t.qty(),
                    t.makerOrderId(), t.timestampMillis()));
        }
        return views;
    }

    private AccountView toAccountView(Account account) {
        AccountView view = new AccountView();
        view.setAccountId(account.getId());
        view.setName(account.getName());
        view.setCash(account.getCash());
        view.setFrozenCash(account.getFrozenCash());
        view.setAvailableCash(account.availableCash());
        List<AccountView.PositionView> positions = new ArrayList<>();
        for (Position p : account.snapshotPositions().values()) {
            positions.add(new AccountView.PositionView(p.getSymbol(), p.getQty(),
                    p.getFrozenQty(), p.available()));
        }
        view.setPositions(positions);
        return view;
    }

    private void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new TradingException(RejectCode.VALIDATION_FAILED, "缺少必填字段: " + field);
        }
    }
}
