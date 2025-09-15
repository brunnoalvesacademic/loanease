import java.util.Locale;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Locale.setDefault(Locale.US); // ensures dot as decimal separator
        Scanner sc = new Scanner(System.in);

        System.out.println("=== Loan Calculator (PRICE / SAC) ===");
        double principal = readDouble(sc, "Loan amount (e.g., 10000.00): ");
        double annualRatePercent = readDouble(sc, "ANNUAL interest rate in % (e.g., 18.5): ");
        int months = readInt(sc, "Term in months (e.g., 24): ");

        System.out.print("Method [PRICE/SAC] (ENTER = PRICE): ");
        String method = sc.nextLine().trim();
        if (method.isEmpty()) method = "PRICE";
        method = method.toUpperCase();

        System.out.print("Print amortization table? [y/N]: ");
        boolean printTable = sc.nextLine().trim().equalsIgnoreCase("y");

        if (!method.equals("PRICE") && !method.equals("SAC")) {
            System.out.println("Invalid method. Use PRICE or SAC.");
            return;
        }

        double monthlyRate = annualRatePercent / 100.0 / 12.0;

        if (method.equals("PRICE")) {
            runPrice(principal, monthlyRate, months, printTable);
        } else {
            runSac(principal, monthlyRate, months, printTable);
        }
    }

    // -------- PRICE (fixed payment) ----------
    private static void runPrice(double principal, double monthlyRate, int months, boolean printTable) {
        double payment;
        if (approxEqual(monthlyRate, 0.0)) {
            payment = principal / months;
        } else {
            payment = principal * monthlyRate / (1.0 - Math.pow(1.0 + monthlyRate, -months));
        }

        double balance = principal;
        double totalPaid = 0.0;
        double totalInterest = 0.0;

        if (printTable) {
            System.out.println("\nMon |   Payment  | Interest |  Principal  |  Balance");
        }

        for (int m = 1; m <= months; m++) {
            double interest = balance * monthlyRate;
            double principalPaid = payment - interest;

            // fix rounding on the last month
            if (m == months) {
                principalPaid = balance;
                payment = interest + principalPaid;
            }

            balance -= principalPaid;
            totalPaid += payment;
            totalInterest += interest;

            if (printTable) {
                System.out.printf("%3d | %10.2f | %8.2f | %11.2f | %9.2f%n",
                        m, payment, interest, principalPaid, Math.max(balance, 0.0));
            }
        }

        System.out.println("\n=== Summary (PRICE) ===");
        System.out.printf("Monthly payment: %.2f%n", payment);
        System.out.printf("Total paid:      %.2f%n", totalPaid);
        System.out.printf("Total interest:  %.2f%n", totalInterest);
    }

    // -------- SAC (fixed principal) ----------
    private static void runSac(double principal, double monthlyRate, int months, boolean printTable) {
        double fixedPrincipal = principal / months;

        double balance = principal;
        double totalPaid = 0.0;
        double totalInterest = 0.0;

        if (printTable) {
            System.out.println("\nMon |   Payment  | Interest |  Principal  |  Balance");
        }

        for (int m = 1; m <= months; m++) {
            double interest = balance * monthlyRate;
            double payment = fixedPrincipal + interest;

            // adjust tiny residuals on the last month
            if (m == months) {
                double adjust = balance - fixedPrincipal;
                if (Math.abs(adjust) > 0.005) {
                    fixedPrincipal += adjust;
                    payment = fixedPrincipal + interest;
                }
            }

            balance -= fixedPrincipal;
            totalPaid += payment;
            totalInterest += interest;

            if (printTable) {
                System.out.printf("%3d | %10.2f | %8.2f | %11.2f | %9.2f%n",
                        m, payment, interest, fixedPrincipal, Math.max(balance, 0.0));
            }
        }

        // in SAC, first payment is the largest, last is the smallest
        double firstPayment = (principal * monthlyRate) + (principal / months);
        double lastPayment = ((principal / months) * monthlyRate) + (principal / months);

        System.out.println("\n=== Summary (SAC) ===");
        System.out.printf("1st payment:     %.2f%n", firstPayment);
        System.out.printf("Last payment:    %.2f%n", lastPayment);
        System.out.printf("Total paid:      %.2f%n", totalPaid);
        System.out.printf("Total interest:  %.2f%n", totalInterest);
    }

    // ---------- utils ----------
    private static boolean approxEqual(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    private static double readDouble(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim().replace(",", ".");
            try {
                return Double.parseDouble(s);
            } catch (Exception e) {
                System.out.println("Invalid number. Try again.");
            }
        }
    }

    private static int readInt(Scanner sc, String prompt) {
        while (true) {
            System.out.print(prompt);
            String s = sc.nextLine().trim();
            try {
                int v = Integer.parseInt(s);
                if (v <= 0) throw new RuntimeException();
                return v;
            } catch (Exception e) {
                System.out.println("Invalid integer. Try again.");
            }
        }
    }
}
