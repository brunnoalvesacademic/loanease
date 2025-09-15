import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * LoanCalculator — Single-file Java program.
 *
 * PURPOSE (plain English):
 *  - Help a person figure out their periodic loan payment, when the loan will be paid off,
 *    and how much interest they will pay.
 *  - Optionally handle extra payments, fees, and escrow so people can see realistic totals.
 *  - Print a detailed amortization schedule (each period's interest, principal, and balance).
 *  - Optionally export the schedule to a CSV file for spreadsheets.
 *
 * HOW IT IS ORGANIZED:
 *  1) Data types (inputs, outputs, one row of the schedule, enums)
 *  2) Core calculation (calculateLoan)
 *  3) Helper methods (rates, dates, rounding fixes, CSV export)
 *  4) A small Command-Line Interface (CLI) so a non-programmer can run it in Replit or locally.
 *
 * This program uses BigDecimal for money to avoid floating-point rounding surprises.
 */
public class LoanCalculator {

    // ===== 1) DATA TYPES =====

    /**
     * All information the calculator needs to run. Optional fields default to ZERO/false.
     * Comments explain the meaning in plain English for non-Java readers.
     */
    static class LoanInput {
        // The amount you borrow
        BigDecimal principal;
        // The annual interest rate as a PERCENT (e.g., enter 5 for 5%)
        BigDecimal aprPercent;
        // How many months the loan is scheduled to last (e.g., 36 months = 3 years)
        int termMonths;
        // How often you pay (monthly, every two weeks, or weekly)
        PaymentFrequency frequency = PaymentFrequency.MONTHLY;
        // The date from which the first due date will be calculated
        LocalDate startDate = LocalDate.now();

        // Optional extras
        BigDecimal extraPerPeriod = BigDecimal.ZERO;  // extra money paid each period
        BigDecimal extraLumpSum = BigDecimal.ZERO;    // one-time extra at first payment

        // Fees (origination, closing). If financed, they are added to principal.
        boolean financeFees = false;
        BigDecimal originationFee = BigDecimal.ZERO;
        BigDecimal closingCosts = BigDecimal.ZERO;

        // Escrow (tax/insurance) added to each payment for real-world totals
        boolean includeEscrow = false;
        BigDecimal escrowPerPeriod = BigDecimal.ZERO;

        // How interest is compounded into a periodic rate
        Compounding compounding = Compounding.NOMINAL_MONTHLY;

        // How to round money (HALF_UP is standard “round to nearest cent”)
        RoundingMode rounding = RoundingMode.HALF_UP;
    }

    /** How often payments happen in one year. */
    enum PaymentFrequency {
        MONTHLY(12), BIWEEKLY(26), WEEKLY(52);
        final int periodsPerYear;
        PaymentFrequency(int ppy) { this.periodsPerYear = ppy; }
    }

    /** How the APR turns into a per-period rate. */
    enum Compounding { NOMINAL_MONTHLY, NOMINAL_DAILY, EFFECTIVE_ANNUAL }

    /** One line of the amortization schedule (what happens in a single period). */
    static class PaymentRow {
        int periodIndex;                 // 1-based period number
        LocalDate dueDate;               // date payment is due
        BigDecimal payment;              // the scheduled base payment (not counting escrow or extra)
        BigDecimal interestPortion;      // how much of this period went to interest
        BigDecimal principalPortion;     // how much reduced the principal
        BigDecimal extraApplied;         // extra money applied beyond the base payment
        BigDecimal escrowApplied;        // escrow amount added if enabled
        BigDecimal endBalance;           // balance after this payment
    }

    /** The main results the user cares about, plus the full schedule and any warnings. */
    static class LoanResult {
        BigDecimal periodicPayment;      // base scheduled amount (including escrow if on)
        LocalDate payoffDate;            // when the loan ends according to the schedule
        BigDecimal totalPaid;            // total money paid over the whole loan
        BigDecimal totalInterest;        // total interest paid
        List<String> warnings = new ArrayList<>();
        List<PaymentRow> schedule = new ArrayList<>();
    }

    /** Optional: compare two scenarios (e.g., with and without extra payments). */
    static class ComparisonResult {
        LoanResult a;
        LoanResult b;
        BigDecimal paymentDiff;
        BigDecimal totalInterestDiff;
        int monthsSaved;
    }

    // ===== 2) CORE CALCULATION =====

    /**
     * Calculates payment, totals, and the full amortization schedule.
     * Steps in plain English:
     *  - Validate the inputs (no negatives, etc.).
     *  - Convert APR to the right per-period rate (monthly/biweekly/weekly) using selected compounding.
     *  - Compute the base payment using the standard amortization formula, or simple division for 0% APR.
     *  - Loop each period: compute interest, principal applied, apply extras/escrow, reduce balance.
     *  - Stop when the balance hits zero (allowing for tiny rounding differences) and record totals.
     */
    static LoanResult calculateLoan(LoanInput in) {
        // --- basic checks (friendly to non-programmers) ---
        if (in == null) throw new IllegalArgumentException("Input cannot be null");
        if (in.principal == null || in.principal.compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Principal must be > 0");
        if (in.aprPercent == null || in.aprPercent.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("APR% cannot be negative");
        if (in.termMonths <= 0) throw new IllegalArgumentException("Term (months) must be > 0");

        LoanResult out = new LoanResult();

        // fees may be financed (added to principal) or paid up-front
        BigDecimal fees = safe(in.originationFee).add(safe(in.closingCosts));
        BigDecimal pv = in.financeFees ? in.principal.add(fees) : in.principal;

        int ppy = in.frequency.periodsPerYear; // periods per year
        // Convert term months into number of payment periods
        int n = (int) Math.ceil(in.termMonths * (ppy / 12.0));

        // Convert APR% to a per-period decimal rate
        BigDecimal r = periodicRate(in.aprPercent, ppy, in.compounding);

        // Escrow + Extras (may be zero)
        BigDecimal escrow = in.includeEscrow ? safe(in.escrowPerPeriod) : BigDecimal.ZERO;
        BigDecimal extraEach = safe(in.extraPerPeriod);
        BigDecimal lump = safe(in.extraLumpSum);

        // Compute the base scheduled payment (without escrow or extras)
        BigDecimal basePayment;
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            basePayment = pv.divide(new BigDecimal(n), 2, in.rounding);
        } else {
            // P = r*PV / (1 - (1+r)^-n)
            MathContext mc = new MathContext(20, RoundingMode.HALF_UP);
            BigDecimal onePlusR = BigDecimal.ONE.add(r, mc);
            BigDecimal denom = BigDecimal.ONE.subtract(pow(onePlusR, -n, mc), mc);
            BigDecimal num = r.multiply(pv, mc);
            basePayment = num.divide(denom, 10, in.rounding).setScale(2, in.rounding);

            // Negative amortization guard: base payment must exceed first-period interest
            BigDecimal firstInterestRounded = pv.multiply(r, mc).setScale(2, in.rounding);
            if (basePayment.compareTo(firstInterestRounded) <= 0) {
                out.warnings.add("Payment is too low and would cause the balance to grow. Minimum adjusted.");
                basePayment = firstInterestRounded.add(new BigDecimal("0.01")).setScale(2, in.rounding);
            }
        }

        // Build the schedule by simulating period-by-period
        List<PaymentRow> schedule = new ArrayList<>();
        BigDecimal balance = pv;
        LocalDate due = firstDueDate(in.startDate, in.frequency);
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;

        // epsilon ~ half-cent to decide when the loan is effectively finished
        BigDecimal epsilon = new BigDecimal("0.005");

        int p = 0;
        while (balance.compareTo(epsilon) > 0 && p < n) {
            p++;
            MathContext mc = new MathContext(20, RoundingMode.HALF_UP);
            // Interest for this period (high precision), then round to cents for display/totals
            BigDecimal interest = balance.multiply(r, mc).setScale(2, in.rounding);
            BigDecimal principalDue = basePayment.subtract(interest).max(BigDecimal.ZERO);

            // Extra payment: for simplicity, apply lump sum at the first payment only
            BigDecimal extra = extraEach;
            if (p == 1) extra = extra.add(lump);

            // Cap principal so we never pay below zero balance
            BigDecimal maxPrincipal = balance;
            BigDecimal principalApplied = principalDue.add(extra).min(maxPrincipal);

            // Actual cash out this period = base + escrow + extra
            BigDecimal actualPayment = interest.add(principalApplied).add(escrow);
            totalPaid = totalPaid.add(actualPayment);
            totalInterest = totalInterest.add(interest);

            balance = balance.subtract(principalApplied);

            // Record the row for the user
            PaymentRow row = new PaymentRow();
            row.periodIndex = p;
            row.dueDate = due;
            row.payment = basePayment;                             // base only
            row.interestPortion = interest;
            row.principalPortion = principalApplied.subtract(extra).setScale(2, in.rounding);
            row.extraApplied = extra.setScale(2, in.rounding);
            row.escrowApplied = escrow.setScale(2, in.rounding);
            row.endBalance = balance.max(BigDecimal.ZERO).setScale(2, in.rounding);
            schedule.add(row);

            if (balance.compareTo(epsilon) > 0) {
                due = nextDueDate(due, in.frequency);
            }
        }

        // Clean up tiny last-cent leftovers by adjusting the very last row
        adjustLastRowForPennies(schedule, in.rounding);
        if (!schedule.isEmpty()) balance = schedule.get(schedule.size() - 1).endBalance;
        LocalDate payoffDate = schedule.isEmpty() ? in.startDate : schedule.get(schedule.size() - 1).dueDate;

        // If fees were not financed, they are paid up-front and should count toward total paid
        if (!in.financeFees) totalPaid = totalPaid.add(fees);

        // Package the result for the caller
        out.periodicPayment = basePayment.add(escrow).setScale(2, in.rounding);
        out.payoffDate = payoffDate;
        out.totalPaid = totalPaid.setScale(2, in.rounding);
        out.totalInterest = totalInterest.setScale(2, in.rounding);
        out.schedule = schedule;
        return out;
    }

    // ===== 3) HELPERS =====

    /** Treat null money as zero to avoid crashes when optional fields are omitted. */
    static BigDecimal safe(BigDecimal x) { return x == null ? BigDecimal.ZERO : x; }

    /**
     * Convert an APR% into a per-period decimal rate depending on compounding.
     * Example: 6% APR monthly -> 0.06 / 12 = 0.005 per month.
     */
    static BigDecimal periodicRate(BigDecimal aprPercent, int periodsPerYear, Compounding c) {
        if (aprPercent == null) return BigDecimal.ZERO;
        BigDecimal apr = aprPercent.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP);
        if (apr.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        MathContext mc = new MathContext(20, RoundingMode.HALF_UP);
        switch (c) {
            case NOMINAL_MONTHLY: {
                // Interpret APR as nominal with 12 periods, then convert to chosen frequency
                BigDecimal monthly = apr.divide(new BigDecimal("12"), 20, RoundingMode.HALF_UP);
                BigDecimal factor = new BigDecimal("12").divide(new BigDecimal(periodsPerYear), 20, RoundingMode.HALF_UP);
                return monthly.multiply(factor, mc);
            }
            case NOMINAL_DAILY: {
                // APR divided by 365 per day, then compounded into chosen frequency
                BigDecimal daily = apr.divide(new BigDecimal("365"), 20, RoundingMode.HALF_UP);
                BigDecimal onePlusDaily = BigDecimal.ONE.add(daily, mc);
                // Use double exponent to avoid BigDecimal exponent mismatch in pow()
                double exp = 365.0 / periodsPerYear;
                BigDecimal effPer = pow(onePlusDaily, exp, mc).subtract(BigDecimal.ONE, mc);
                return effPer;
            }
            case EFFECTIVE_ANNUAL: {
                // Convert effective annual rate directly to per-period: (1+apr)^(1/ppy)-1
                BigDecimal onePlusApr = BigDecimal.ONE.add(apr, mc);
                return pow(onePlusApr, 1.0 / periodsPerYear, mc).subtract(BigDecimal.ONE, mc);
            }
            default:
                return apr.divide(new BigDecimal(periodsPerYear), 20, RoundingMode.HALF_UP);
        }
    }

    /** BigDecimal power for integer n (can handle negative n). */
    static BigDecimal pow(BigDecimal base, int n, MathContext mc) {
        if (n == 0) return BigDecimal.ONE;
        boolean neg = n < 0;
        int exp = Math.abs(n);
        BigDecimal result = BigDecimal.ONE;
        BigDecimal b = base;
        while (exp > 0) {
            if ((exp & 1) == 1) result = result.multiply(b, mc);
            b = b.multiply(b, mc);
            exp >>= 1;
        }
        if (neg) {
            return BigDecimal.ONE.divide(result, mc);
        }
        return result;
    }

    /** BigDecimal power for fractional exponents using double approximation in exponent (sufficient for rates). */
    static BigDecimal pow(BigDecimal base, double exponent, MathContext mc) {
        // Uses Math.pow on double for exponent and converts back; acceptable for financial rate calculations.
        double bd = base.doubleValue();
        double val = Math.pow(bd, exponent);
        return new BigDecimal(val, mc);
    }

    /** First due date from a start date (next period). */
    static LocalDate firstDueDate(LocalDate start, PaymentFrequency f) {
        switch (f) {
            case MONTHLY: return start.plusMonths(1);
            case BIWEEKLY: return start.plusWeeks(2);
            case WEEKLY: return start.plusWeeks(1);
            default: return start.plusMonths(1);
        }
    }

    /** Next due date given a previous due date and frequency. */
    static LocalDate nextDueDate(LocalDate prev, PaymentFrequency f) {
        switch (f) {
            case MONTHLY: return prev.plusMonths(1);
            case BIWEEKLY: return prev.plusWeeks(2);
            case WEEKLY: return prev.plusWeeks(1);
            default: return prev.plusMonths(1);
        }
    }

    /** Fix tiny rounding leftovers by adjusting the last schedule row by a penny if needed. */
    static void adjustLastRowForPennies(List<PaymentRow> schedule, RoundingMode rounding) {
        if (schedule == null || schedule.isEmpty()) return;
        PaymentRow last = schedule.get(schedule.size() - 1);
        // If the balance is within 1 cent, fold it into principal/payment and set to zero
        if (last.endBalance.abs().compareTo(new BigDecimal("0.01")) <= 0) {
            BigDecimal delta = last.endBalance; // may be +0.01 or -0.01
            last.principalPortion = last.principalPortion.add(delta).setScale(2, rounding);
            last.payment = last.payment.add(delta).setScale(2, rounding);
            last.endBalance = BigDecimal.ZERO.setScale(2, rounding);
        }
    }

    /** Optional helper: compare two loan setups. */
    static ComparisonResult compare(LoanInput a, LoanInput b) {
        LoanResult ra = calculateLoan(a);
        LoanResult rb = calculateLoan(b);
        ComparisonResult cr = new ComparisonResult();
        cr.a = ra; cr.b = rb;
        cr.paymentDiff = ra.periodicPayment.subtract(rb.periodicPayment).setScale(2, RoundingMode.HALF_UP);
        cr.totalInterestDiff = ra.totalInterest.subtract(rb.totalInterest).setScale(2, RoundingMode.HALF_UP);
        cr.monthsSaved = ra.schedule.size() - rb.schedule.size();
        return cr;
    }

    /** Write the schedule to a CSV file that Excel/Sheets can open. */
    static void exportScheduleCSV(List<PaymentRow> schedule, Path path) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("Period,Date,Payment,Interest,Principal,Extra,Escrow,Balance\n");
            DateTimeFormatter fmt = DateTimeFormatter.ISO_DATE;
            for (PaymentRow r : schedule) {
                w.write(String.format(Locale.US,
                        "%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                        r.periodIndex,
                        r.dueDate.format(fmt),
                        r.payment.doubleValue(),
                        r.interestPortion.doubleValue(),
                        r.principalPortion.doubleValue(),
                        r.extraApplied.doubleValue(),
                        r.escrowApplied.doubleValue(),
                        r.endBalance.doubleValue()));
            }
        }
    }

    // ===== 4) SIMPLE COMMAND-LINE INTERFACE (so anyone can run it) =====

    /**
     * The CLI guides the user to enter inputs and prints results in readable text.
     *
     * HOW TO USE (Replit or local):
     *  1) Run the program. 2) Answer prompts (press Enter to accept defaults).
     *  3) See payment, totals, and (optionally) export the schedule to CSV.
     */
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println("=== Loan Calculator ===");

        LoanInput in = new LoanInput();
        in.principal = readMoney(sc, "Principal amount (e.g., 10000): ");
        in.aprPercent = readMoney(sc, "APR percent (e.g., 5 for 5%): ");
        in.termMonths = readInt(sc, "Term in months (e.g., 36): ");

        System.out.print("Payment frequency [1=Monthly, 2=Biweekly, 3=Weekly] (default 1): ");
        String f = sc.nextLine().trim();
        if (f.equals("2")) in.frequency = PaymentFrequency.BIWEEKLY;
        else if (f.equals("3")) in.frequency = PaymentFrequency.WEEKLY;
        else in.frequency = PaymentFrequency.MONTHLY;

        in.startDate = readDate(sc, "Start date YYYY-MM-DD (default today): ", LocalDate.now());

        // Optional extras
        in.extraPerPeriod = readMoneyOptional(sc, "Extra per period (optional, default 0): ");
        in.extraLumpSum = readMoneyOptional(sc, "One-time extra at first payment (optional, default 0): ");

        // Fees
        System.out.print("Finance fees into the loan? [y/N]: ");
        in.financeFees = sc.nextLine().trim().toLowerCase(Locale.ROOT).startsWith("y");
        in.originationFee = readMoneyOptional(sc, "Origination fee (optional, default 0): ");
        in.closingCosts = readMoneyOptional(sc, "Closing costs (optional, default 0): ");

        // Escrow
        System.out.print("Include escrow per period? [y/N]: ");
        in.includeEscrow = sc.nextLine().trim().toLowerCase(Locale.ROOT).startsWith("y");
        if (in.includeEscrow) {
            in.escrowPerPeriod = readMoneyOptional(sc, "Escrow amount per period: ");
        }

        // Compounding choice
        System.out.print("Compounding [1=Nominal Monthly, 2=Nominal Daily, 3=Effective Annual] (default 1): ");
        String c = sc.nextLine().trim();
        if (c.equals("2")) in.compounding = Compounding.NOMINAL_DAILY;
        else if (c.equals("3")) in.compounding = Compounding.EFFECTIVE_ANNUAL;
        else in.compounding = Compounding.NOMINAL_MONTHLY;

        // Calculate
        LoanResult res = calculateLoan(in);

        // Print summary
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf(Locale.US, "Base periodic payment (incl. escrow if any): $%.2f\n", res.periodicPayment.doubleValue());
        System.out.println("Payoff date: " + res.payoffDate);
        System.out.printf(Locale.US, "Total interest paid: $%.2f\n", res.totalInterest.doubleValue());
        System.out.printf(Locale.US, "Total paid: $%.2f\n", res.totalPaid.doubleValue());
        if (!res.warnings.isEmpty()) {
            System.out.println("Warnings:");
            for (String w : res.warnings) System.out.println(" - " + w);
        }

        // Offer export
        System.out.print("Export full amortization schedule to CSV? [y/N]: ");
        if (sc.nextLine().trim().toLowerCase(Locale.ROOT).startsWith("y")) {
            try {
                Path out = Path.of("amortization.csv");
                exportScheduleCSV(res.schedule, out);
                System.out.println("Saved: " + out.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("Could not write CSV: " + e.getMessage());
            }
        }

        System.out.println("Done. Thank you!");
    }

    // ===== INPUT HELPERS (robust, friendly to non-programmers) =====

    static BigDecimal readMoney(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            if (s.matches("^[+-]?\\d+(\\.\\d{1,2})?$")) {
                return new BigDecimal(s);
            }
            System.out.println("Please enter a numeric amount (e.g., 10000 or 10000.50).");
        }
    }

    static BigDecimal readMoneyOptional(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            if (s.isEmpty()) return BigDecimal.ZERO;
            if (s.matches("^[+-]?\\d+(\\.\\d{1,2})?$")) {
                return new BigDecimal(s);
            }
            System.out.println("Please enter a numeric amount or press Enter to skip.");
        }
    }

    static int readInt(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            try {
                int v = Integer.parseInt(s);
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
            System.out.println("Please enter a whole number greater than 0.");
        }
    }

    static LocalDate readDate(Scanner sc, String prompt, LocalDate def) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            if (s.isEmpty()) return def;
            if (!s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                System.out.println("Invalid date. Use YYYY-MM-DD (e.g., 2025-09-14).");
                continue;
            }
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date. Use YYYY-MM-DD (e.g., 2025-09-14).");
            }
        }
    }
}
