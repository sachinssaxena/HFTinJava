package com.hft.test;

import com.hft.core.model.Side;
import com.hft.orderbook.OrderBookL3;

public class OrderBookReplaceBugTest {
    public static void main(String[] args) {
        OrderBookL3 book = new OrderBookL3(1024);
        // Add a BUY order at index 0
        book.addOrder(1L, Side.BUY, 100, 1_400_000L);
        // Add a SELL order at index 1
        long orderId = 999L;
        book.addOrder(orderId, Side.SELL, 500, 1_550_000L);

        System.out.println("Initial Best Ask Price: " + book.getBestAskPrice());
        System.out.println("Initial Best Bid Price: " + book.getBestBidPrice());

        // Replace order with new shares and price
        book.replaceOrder(orderId, 1000L, 600, 1_560_000L);

        System.out.println("After Replace - Best Ask Price: " + book.getBestAskPrice());
        System.out.println("After Replace - Best Bid Price: " + book.getBestBidPrice());

        if (book.getBestBidPrice() == 1_560_000L) {
            System.err.println("BUG CONFIRMED: SELL order was erroneously turned into a BUY order!");
            System.exit(1);
        } else {
            System.out.println("SUCCESS: SELL order preserved side.");
        }
    }
}
