import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ShamirSecretSharing {

    // Class representing a 2D point (x, y) = (share number, share value)
    static class Point {
        BigInteger x, y;
        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }

    // Class for precise fractional arithmetic with BigInteger support
    static class Fraction {
        BigInteger num, den;

        // Constructor with numerator and denominator, reduces the fraction and ensures consistent sign
        Fraction(BigInteger num, BigInteger den) {
            if (den.equals(BigInteger.ZERO)) {
                throw new ArithmeticException("Division by zero in fraction");
            }
            BigInteger gcd = num.gcd(den).abs();
            this.num = num.divide(gcd);
            this.den = den.divide(gcd);
            if (this.den.signum() < 0) {
                this.num = this.num.negate();
                this.den = this.den.negate();
            }
        }

        // Constructor from an integer (denominator = 1)
        Fraction(BigInteger num) {
            this(num, BigInteger.ONE);
        }

        Fraction add(Fraction f) {
            return new Fraction(this.num.multiply(f.den).add(f.num.multiply(this.den)), this.den.multiply(f.den));
        }

        Fraction subtract(Fraction f) {
            return new Fraction(this.num.multiply(f.den).subtract(f.num.multiply(this.den)), this.den.multiply(f.den));
        }

        Fraction multiply(Fraction f) {
            return new Fraction(this.num.multiply(f.num), this.den.multiply(f.den));
        }

        Fraction divide(Fraction f) {
            if (f.isZero()) {
                throw new ArithmeticException("Division by zero fraction");
            }
            return new Fraction(this.num.multiply(f.den), this.den.multiply(f.num));
        }

        boolean isZero() {
            return num.equals(BigInteger.ZERO);
        }

        BigInteger toBigInt() {
            return num.divide(den);
        }

        public String toString() {
            return num + "/" + den;
        }
    }

    public static void main(String[] args) {
        String[] files = {"testcase1.json", "testcase2.json"};
        boolean allSuccessful = true;

        for (String file : files) {
            try {
                System.out.println("Processing: " + file);

                // Check file existence
                if (!Files.exists(Paths.get(file))) {
                    System.err.println("Error: File '" + file + "' not found");
                    allSuccessful = false;
                    continue;
                }

                // Read JSON content
                String json = Files.readString(Paths.get(file));

                // Reconstruct secret from JSON
                BigInteger secret = extractSecretFromJson(json);
                System.out.println("Secret: " + secret);
                System.out.println();

            } catch (IOException e) {
                System.err.println("Error reading file '" + file + "': " + e.getMessage());
                allSuccessful = false;
            } catch (Exception e) {
                System.err.println("Error processing file '" + file + "': " + e.getMessage());
                allSuccessful = false;
            }
        }

        if (!allSuccessful) {
            System.err.println("Some files could not be processed successfully");
            System.exit(1);
        }
    }

    // Extract and reconstruct the secret from JSON input
    static BigInteger extractSecretFromJson(String json) {
        int k = extractK(json);
        List<Point> points = extractPoints(json);

        if (points.size() < k) {
            throw new RuntimeException("Insufficient valid points to reconstruct secret");
        }

        // Try all combinations of k points to tolerate invalid shares
        Map<BigInteger, Integer> secretFreq = new HashMap<>();
        List<List<Point>> subsets = generateCombinations(points, k);

        for (List<Point> subset : subsets) {
            try {
                BigInteger secret = solve(subset);
                secretFreq.put(secret, secretFreq.getOrDefault(secret, 0) + 1);
            } catch (Exception e) {
                // Skip subsets that result in failure (bad shares)
            }
        }

        if (secretFreq.isEmpty()) {
            throw new RuntimeException("No valid combinations could reconstruct the secret");
        }

        // Return the most frequently reconstructed secret (majority vote)
        return Collections.max(secretFreq.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    // Extract value of k from JSON using regex
    static int extractK(String json) {
        Pattern kPattern = Pattern.compile("\"k\"\\s*:\\s*(\\d+)");
        Matcher kMatcher = kPattern.matcher(json);
        if (kMatcher.find()) {
            int k = Integer.parseInt(kMatcher.group(1));
            if (k <= 0) throw new RuntimeException("k must be positive");
            return k;
        }
        throw new RuntimeException("Could not find 'k' in JSON");
    }

    // Extract all valid points from JSON
    static List<Point> extractPoints(String json) {
        List<Point> points = new ArrayList<>();
        Pattern entryPattern = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{\\s*\"base\"\\s*:\\s*\"(\\d+)\",\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = entryPattern.matcher(json);

        while (matcher.find()) {
            try {
                BigInteger x = new BigInteger(matcher.group(1));
                int base = Integer.parseInt(matcher.group(2));
                String value = matcher.group(3);

                if (base < 2 || base > 36 || value == null || value.trim().isEmpty()) continue;

                BigInteger y = new BigInteger(value.toLowerCase(), base);
                points.add(new Point(x, y));
            } catch (Exception e) {
                // Skip malformed or invalid points
            }
        }

        return points;
    }

    // Reconstruct the secret using Gaussian Elimination
    static BigInteger solve(List<Point> points) {
        int k = points.size();
        Fraction[][] mat = new Fraction[k][k + 1];

        // Build augmented matrix for solving k equations with k unknowns (Lagrange basis coefficients)
        for (int i = 0; i < k; i++) {
            BigInteger x = points.get(i).x;
            for (int j = 0; j < k; j++) {
                mat[i][j] = new Fraction(x.pow(k - j - 1));
            }
            mat[i][k] = new Fraction(points.get(i).y);
        }

        // Forward Elimination with partial pivoting
        for (int pivot = 0; pivot < k; pivot++) {
            int maxRow = pivot;
            for (int i = pivot + 1; i < k; i++) {
                if (mat[i][pivot].num.abs().compareTo(mat[maxRow][pivot].num.abs()) > 0) {
                    maxRow = i;
                }
            }
            Fraction[] tmp = mat[pivot];
            mat[pivot] = mat[maxRow];
            mat[maxRow] = tmp;

            if (mat[pivot][pivot].isZero()) {
                throw new RuntimeException("Matrix is singular - no unique solution");
            }

            for (int i = pivot + 1; i < k; i++) {
                if (!mat[i][pivot].isZero()) {
                    Fraction factor = mat[i][pivot].divide(mat[pivot][pivot]);
                    for (int j = pivot; j <= k; j++) {
                        mat[i][j] = mat[i][j].subtract(factor.multiply(mat[pivot][j]));
                    }
                }
            }
        }

        // Back substitution to solve for coefficients
        Fraction[] coeff = new Fraction[k];
        for (int i = k - 1; i >= 0; i--) {
            coeff[i] = mat[i][k];
            for (int j = i + 1; j < k; j++) {
                coeff[i] = coeff[i].subtract(mat[i][j].multiply(coeff[j]));
            }
            coeff[i] = coeff[i].divide(mat[i][i]);
        }

        // Constant term of the polynomial (i.e., the secret) is the last coefficient
        return coeff[k - 1].toBigInt();
    }

    // Generate all combinations of k points from the list
    static List<List<Point>> generateCombinations(List<Point> points, int k) {
        List<List<Point>> result = new ArrayList<>();
        backtrack(points, k, 0, new ArrayList<>(), result);
        return result;
    }

    // Backtracking helper to generate combinations
    static void backtrack(List<Point> points, int k, int start, List<Point> temp, List<List<Point>> result) {
        if (temp.size() == k) {
            result.add(new ArrayList<>(temp));
            return;
        }

        for (int i = start; i < points.size(); i++) {
            temp.add(points.get(i));
            backtrack(points, k, i + 1, temp, result);
            temp.remove(temp.size() - 1);
        }
    }
}
