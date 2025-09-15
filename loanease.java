// All code is in a single file as requested.
// Program is written in English.

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A comprehensive loan amortization calculator that can handle various inputs,
 * generate a full amortization schedule, and compare different "what-if" scenarios.
 */
public class LoanCalculator {

    // =================================================================================
    // Main Method - Entry point of the program
    // =================================================================================

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("          Loan Amortization Calculator");
        System.out.println("=====================================================");
        
        // --- SCENARIO 1: A standard baseline loan ---
        System.out.println("\n--- SCENARIO 1: Standard Monthly Payments ---");
        LoanDetails scenario1_details = new LoanDetails(
            300000.00,    // principal loan amount
            0.065,        // apr / annual interest rate (6.5%)
            30,           // termInYears
            PaymentFrequency.MONTHLY,
            LocalDate.of(2024, 8, 1), // loan start date
            0.00,         // extraPayment per period
            3000.00,      // originationFee
            1500.00,      // closingFee
            4800.00       // annualEscrow (for taxes and insurance)
        );

        try {
            LoanAnalysisResult scenario1_result = calculate(scenario1_details);
            displayResults(scenario1_result, "Scenario 1: Standard Loan");

            // --- SCENARIO 2: Same loan with extra payments ---
            System.out.println("\n\n--- SCENARIO 2: What-if with Extra Monthly Payments ---");
            LoanDetails scenario2_details = new LoanDetails(
                300000.00,
                0.065,
                30,
                PaymentFrequency.MONTHLY,
                LocalDate.of(2024, 8, 1),
                250.00,       // $250 extra per month
                3000.00,
                1500.00,
                4800.00
            );
            LoanAnalysisResult scenario2_result = calculate(scenario2_details);
            displayResults(scenario2_result, "Scenario 2: With $250 Extra/Month");

            // --- COMPARISON SUMMARY ---
            System.out.println("\n\n=====================================================");
            System.out.println("                SCENARIO COMPARISON");
            System.out.println("=====================================================");
            displayComparison(scenario1_result, scenario2_result);

        } catch (IllegalArgumentException e) {
            System.err.println("\n[ERROR] Calculation failed: " + e.getMessage());
        }
    }

    // =================================================================================
    // Core Processing Logic
    // =================================================================================

    /**
     * Performs the main loan calculation based on the provided details.
     * @param details The LoanDetails object containing all input parameters.
     * @return A LoanAnalysisResult object with the complete analysis.
     * @throws IllegalArgumentException for invalid inputs.
     */
    public static LoanAnalysisResult calculate(LoanDetails details) {
        // --- 1. INPUT VALIDATION ---
        if (details.principal() <= 0 || details.annualRate() < 0 || details.termInYears() <= 0) {
            throw new IllegalArgumentException("Loan amount, rate, and term must be positive.");
        }
        Objects.requireNonNull(details.frequency(), "Payment frequency cannot be null.");
        Objects.requireNonNull(details.startDate(), "Start date cannot be null.");

        // --- 2. INITIAL SETUP ---
        double totalFinancedAmount = details.principal() + details.originationFee() + details.closingFee();
        int paymentsPerYear = details.frequency().getPaymentsPerYear();
        double periodicInterestRate = details.annualRate() / paymentsPerYear;
        int totalNumberOfPayments = details.termInYears() * paymentsPerYear;

        // --- 3. CALCULATE PERIODIC PAYMENT (P&I) using the amortization formula ---
        // P = [r * PV] / [1 - (1 + r)^-n]
        double scheduledPrincipalAndInterestPayment;
        if (periodicInterestRate == 0) {
            scheduledPrincipalAndInterestPayment = totalFinancedAmount / totalNumberOfPayments;
        } else {
            double ratePower = Math.pow(1 + periodicInterestRate, totalNumberOfPayments);
            scheduledPrincipalAndInterestPayment = (periodicInterestRate * totalFinancedAmount * ratePower) / (ratePower - 1);
        }

        double periodicEscrowPayment = details.annualEscrow() / paymentsPerYear;
        double totalPeriodicPayment = scheduledPrincipalAndInterestPayment + periodicEscrowPayment + details.extraPayment();

        // --- 4. BUILD AMORTIZATION SCHEDULE ---
        List<AmortizationEntry> schedule = new ArrayList<>();
        double currentBalance = totalFinancedAmount;
        double totalInterestPaid = 0.0;
        LocalDate currentDate = details.startDate();
        int period = 1;

        while (currentBalance > 0.005) { // Use a small threshold to handle floating point inaccuracies
            double interestForPeriod = currentBalance * periodicInterestRate;
            double principalFromPayment = scheduledPrincipalAndInterestPayment - interestForPeriod;
            
            // Guard against negative amortization on a standard schedule
            if (principalFromPayment <= 0 && details.extraPayment() <= 0) {
                // This shouldn't happen with the standard formula but is a good safeguard.
                throw new IllegalStateException("Payment is not enough to cover interest (negative amortization). Increase payment or check inputs.");
            }

            double totalPrincipalPaidThisPeriod = principalFromPayment + details.extraPayment();

            // Handle the final payment which might be smaller
            if (currentBalance < totalPrincipalPaidThisPeriod + interestForPeriod) {
                totalPrincipalPaidThisPeriod = currentBalance;
                // Recalculate the P&I payment for this final period
                scheduledPrincipalAndInterestPayment = totalPrincipalPaidThisPeriod + interestForPeriod;
            }

            currentBalance -= totalPrincipalPaidThisPeriod;
            totalInterestPaid += interestForPeriod;
            
            schedule.add(new AmortizationEntry(
                period,
                currentDate,
                scheduledPrincipalAndInterestPayment + details.extraPayment() + periodicEscrowPayment, // Total payment made
                totalPrincipalPaidThisPeriod,
                interestForPeriod,
                periodicEscrowPayment,
                currentBalance < 0 ? 0 : currentBalance // Ensure balance doesn't show negative
            ));

            // Prepare for next iteration
            period++;
            currentDate = details.frequency().getNextDate(currentDate);

            // Safety break to prevent infinite loops in case of a bug
            if (period > details.termInYears() * paymentsPerYear * 2) {
                 throw new IllegalStateException("Calculation exceeded maximum expected term. Check for negative amortization.");
            }
        }
        
        LocalDate payoffDate = schedule.isEmpty() ? details.startDate() : schedule.get(schedule.size() - 1).paymentDate();
        double totalPaid = totalFinancedAmount + totalInterestPaid;

        return new LoanAnalysisResult(
            details,
            scheduledPrincipalAndInterestPayment,
            periodicEscrowPayment,
            totalPeriodicPayment,
            payoffDate,
            totalInterestPaid,
            totalPaid,
            schedule
        );
    }

    // =================================================================================
    // Output & Display Methods
    // =================================================================================

    /**
     * Displays the full analysis results for a single loan scenario.
     */
    public static void displayResults(LoanAnalysisResult result, String scenarioName) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM yyyy");

        System.out.printf("--- Results for: %s ---\n", scenarioName);
        System.out.println("INPUTS:");
        System.out.printf("  Loan Amount:      $%,12.2f  (Financed: $%,.2f including fees)\n", result.details().principal(), result.details().principal() + result.details().originationFee() + result.details().closingFee());
        System.out.printf("  APR:              %12.3f%%\n", result.details().annualRate() * 100);
        System.out.printf("  Term:             %12d Years\n", result.details().termInYears());
        System.out.printf("  Extra Payment:    $%,12.2f / %s\n", result.details().extraPayment(), result.details().frequency().toString().toLowerCase());
        System.out.println();
        System.out.println("SUMMARY:");
        System.out.printf("  P&I Payment:      $%,12.2f\n", result.scheduledPrincipalAndInterestPayment());
        System.out.printf("  Escrow Payment:   $%,12.2f\n", result.periodicEscrowPayment());
        System.out.printf("  Total Payment:    $%,12.2f (including extra payment and escrow)\n", result.totalPeriodicPayment());
        System.out.printf("  Payoff Date:      %12s\n", result.payoffDate().format(dateFormatter));
        System.out.printf("  Total Interest:   $%,12.2f\n", result.totalInterestPaid());
        System.out.printf("  Total Paid:       $%,12.2f (Principal + Interest + Fees)\n", result.totalPaid());
        System.out.println();

        // Print Amortization Table Header
        System.out.println("AMORTIZATION SCHEDULE:");
        System.out.println("---------------------------------------------------------------------------------------------------------");
        System.out.printf("%-6s | %-12s | %-15s | %-15s | %-15s | %-15s | %-15s\n",
                "Period", "Pay Date", "Principal", "Interest", "Escrow", "Total Payment", "Ending Balance");
        System.out.println("---------------------------------------------------------------------------------------------------------");

        // Print first few and last few entries for brevity
        List<AmortizationEntry> schedule = result.schedule();
        int totalEntries = schedule.size();
        int entriesToShow = 5;
        if (totalEntries <= entriesToShow * 2) {
            for (AmortizationEntry entry : schedule) {
                printScheduleEntry(entry);
            }
        } else {
            for (int i = 0; i < entriesToShow; i++) {
                printScheduleEntry(schedule.get(i));
            }
            System.out.println("...      | ...          | ...             | ...             | ...             | ...             | ...");
            for (int i = totalEntries - entriesToShow; i < totalEntries; i++) {
                printScheduleEntry(schedule.get(i));
            }
        }
        System.out.println("---------------------------------------------------------------------------------------------------------");
    }

    private static void printScheduleEntry(AmortizationEntry entry) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM yyyy");
        System.out.printf("%-6d | %-12s | $%,14.2f | $%,14.2f | $%,14.2f | $%,14.2f | $%,14.2f\n",
                entry.periodNumber(),
                entry.paymentDate().format(dateFormatter),
                entry.principal(),
                entry.interest(),
                entry.escrow(),
                entry.totalPayment(),
                entry.endingBalance());
    }

    /**
     * Displays a summary comparing two loan analysis results.
     */
    public static void displayComparison(LoanAnalysisResult base, LoanAnalysisResult whatIf) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM yyyy");
        long baseMonths = base.schedule().size();
        long whatIfMonths = whatIf.schedule().size();
        long monthsSaved = baseMonths - whatIfMonths;
        double interestSaved = base.totalInterestPaid() - whatIf.totalInterestPaid();

        System.out.printf("%-20s | %-20s | %-20s\n", "Metric", "Scenario 1 (Base)", "Scenario 2 (What-If)");
        System.out.println("--------------------------------------------------------------------");
        System.out.printf("%-20s | %-20s | %-20s\n", "Payoff Date", base.payoffDate().format(dateFormatter), whatIf.payoffDate().format(dateFormatter));
        System.out.printf("%-20s | $%,19.2f | $%,19.2f\n", "Total Interest Paid", base.totalInterestPaid(), whatIf.totalInterestPaid());
        System.out.printf("%-20s | %-20d | %-20d\n", "Total Payments Made", base.schedule().size(), whatIf.schedule().size());
        System.out.println("\nSUMMARY OF SAVINGS:");
        System.out.printf("  By paying extra, you pay off the loan %d years and %d months sooner.\n", monthsSaved / 12, monthsSaved % 12);
        System.out.printf("  You save a total of $%,.2f in interest.\n", interestSaved);
    }
}

// =================================================================================
// Data Structures (Records, Enums) - Modern and concise way to model data
// =================================================================================

/**
 * Represents the inputs for a loan calculation.
 * Using a record for an immutable data carrier.
 */
record LoanDetails(
    double principal,
    double annualRate,
    int termInYears,
    PaymentFrequency frequency,
    LocalDate startDate,
    double extraPayment,
    double originationFee,
    double closingFee,
    double annualEscrow
) {}

/**
 * Represents the full output of a loan analysis.
 */
record LoanAnalysisResult(
    LoanDetails details,
    double scheduledPrincipalAndInterestPayment,
    double periodicEscrowPayment,
    double totalPeriodicPayment,
    LocalDate payoffDate,
    double totalInterestPaid,
    double totalPaid,
    List<AmortizationEntry> schedule
) {}

/**
 * Represents a single row in the amortization schedule.
 */
record AmortizationEntry(
    int periodNumber,
    LocalDate paymentDate,
    double totalPayment,
    double principal,
    double interest,
    double escrow,
    double endingBalance
) {}

/**
 * Defines the frequency of payments.
 */
enum PaymentFrequency {
    MONTHLY(12),
    BIWEEKLY(26);

    private final int paymentsPerYear;

    PaymentFrequency(int paymentsPerYear) {
        this.paymentsPerYear = paymentsPerYear;
    }

    public int getPaymentsPerYear() {
        return paymentsPerYear;
    }

    public LocalDate getNextDate(LocalDate currentDate) {
        return switch (this) {
            case MONTHLY -> currentDate.plusMonths(1);
            case BIWEEKLY -> currentDate.plusWeeks(2);
        };
    }
}