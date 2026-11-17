/**
 * Facade: the single entry point that hides the five airline subsystems
 * (seat inventory, baggage pricing, currency conversion, payment, ticketing)
 * behind one high-level operation.
 */
public class FlightBookingFacade {

    // Encapsulated subsystem references
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private final PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        this.seatSystem = new SeatInventorySystem();
        this.baggageEngine = new BaggagePricingEngine();
        this.currencyConverter = new CurrencyConverter();
        this.paymentProcessor = new PaymentProcessor();
        this.ticketIssuer = new TicketIssuer();
    }

    /**
     * Phase 1 signature, kept for backward compatibility.
     * Delegates to the 9-parameter version with the default "STANDARD" tier.
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    /**
     * Single entry point for booking a complete flight itinerary.
     * Coordinates seat locking, baggage pricing, currency conversion,
     * payment execution, rollback on failure, and ticket issuance.
     *
     * Loyalty rules (Phase 2):
     *  - SILVER / GOLD: baggage engine is skipped, baggage fee = 0.0
     *  - SILVER: promo code "SILVER-FREE-BAG" is applied before charging
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber,
                             String loyaltyTier) {
        String tier = (loyaltyTier == null) ? "STANDARD" : loyaltyTier.trim().toUpperCase();
        boolean isSilver = "SILVER".equals(tier);
        boolean isGold = "GOLD".equals(tier);

        String lockToken = null;
        try {
            // 1: Connect to seat server and lock the requested seat.
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // 2: Compute baggage fee in USD (skipped for SILVER/GOLD) and add to base fare.
            double baggageFeeUSD = 0.0;
            if (!isSilver && !isGold) {
                baggageFeeUSD = baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            }
            double totalUSD = baseFareUSD + baggageFeeUSD;

            // 3: Convert total USD amount into target currency.
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // 4: Initialize payment gateway, apply promo for SILVER, charge the card.
            paymentProcessor.initializeGateway();
            if (isSilver) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
            }
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);

            // 5: Generate and return the final e-ticket code.
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);

        } catch (RuntimeException ex) {
            // 6: Roll back the seat lock if one was acquired.
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            System.out.println("[FlightBookingFacade] Booking failed for " + passengerName
                    + ": " + ex.getMessage());
            return "BOOKING_FAILED: " + ex.getMessage();

        } finally {
            // 7: Always disconnect from the seat server.
            seatSystem.disconnect();
        }
    }
}
