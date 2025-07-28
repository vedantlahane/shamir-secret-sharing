import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ShamirSecretSharing {

    static class Point {
        BigInteger x, y;
        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }

    static class Fraction {
        BigInteger num, den;

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
            if (den.equals(BigInteger.ZERO)) {
                throw new ArithmeticException("Cannot convert fraction with zero denominator to BigInteger");
            }
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
                
                // Check if file exists
                if (!Files.exists(Paths.get(file))) {
                    System.err.println("Error: File '" + file + "' not found");
                    allSuccessful = false;
                    continue;
                }

                String json = Files.readString(Paths.get(file));
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

    static BigInteger extractSecretFromJson(String json) {
        try {
            int k = extractK(json);
            List<Point> points = extractPoints(json);

            if (points.isEmpty()) {
                throw new RuntimeException("No valid points found in JSON");
            }

            if (points.size() < k) {
                throw new RuntimeException("Insufficient points: need " + k + " points, but only found " + points.size());
            }

            // Sort by x and take first k points
            points.sort(Comparator.comparing(p -> p.x));
            return solve(points.subList(0, k));
            
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number format in JSON: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract secret from JSON: " + e.getMessage());
        }
    }

    static int extractK(String json) {
        try {
            Pattern kPattern = Pattern.compile("\"k\"\\s*:\\s*(\\d+)");
            Matcher kMatcher = kPattern.matcher(json);
            if (kMatcher.find()) {
                int k = Integer.parseInt(kMatcher.group(1));
                if (k <= 0) {
                    throw new RuntimeException("Invalid k value: k must be positive, got " + k);
                }
                return k;
            } else {
                throw new RuntimeException("Could not find 'k' value in JSON");
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid k value format: " + e.getMessage());
        }
    }

    static List<Point> extractPoints(String json) {
        List<Point> points = new ArrayList<>();

        try {
            // Pattern to find entries like: "2": { "base": "10", "value": "123" }
            Pattern entryPattern = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{\\s*\"base\"\\s*:\\s*\"(\\d+)\",\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = entryPattern.matcher(json);

            while (matcher.find()) {
                try {
                    BigInteger x = new BigInteger(matcher.group(1));
                    int base = Integer.parseInt(matcher.group(2));
                    String value = matcher.group(3);

                    // Validate base
                    if (base < 2 || base > 36) {
                        System.err.println("Warning: Skipping point with invalid base " + base + " (must be 2-36)");
                        continue;
                    }

                    // Validate and convert value
                    if (value == null || value.trim().isEmpty()) {
                        System.err.println("Warning: Skipping point with empty value");
                        continue;
                    }

                    BigInteger y = new BigInteger(value.toLowerCase(), base);
                    points.add(new Point(x, y));
                    
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Skipping invalid point due to number format error: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("Warning: Skipping point due to error: " + e.getMessage());
                }
            }

            return points;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract points from JSON: " + e.getMessage());
        }
    }

    static BigInteger solve(List<Point> points) {
        int k = points.size();
        
        if (k == 0) {
            throw new RuntimeException("Cannot solve with zero points");
        }

        try {
            Fraction[][] mat = new Fraction[k][k + 1];

            // Build augmented matrix
            for (int i = 0; i < k; i++) {
                BigInteger x = points.get(i).x;
                for (int j = 0; j < k; j++) {
                    try {
                        mat[i][j] = new Fraction(x.pow(k - j - 1));
                    } catch (ArithmeticException e) {
                        throw new RuntimeException("Arithmetic error building matrix at position [" + i + "," + j + "]: " + e.getMessage());
                    }
                }
                mat[i][k] = new Fraction(points.get(i).y);
            }

            // Gaussian elimination with partial pivoting
            for (int pivot = 0; pivot < k; pivot++) {
                
                // Find best pivot
                int maxRow = pivot;
                for (int i = pivot + 1; i < k; i++) {
                    if (mat[i][pivot].num.abs().compareTo(mat[maxRow][pivot].num.abs()) > 0) {
                        maxRow = i;
                    }
                }
                
                // Swap rows
                if (maxRow != pivot) {
                    Fraction[] tmp = mat[pivot]; 
                    mat[pivot] = mat[maxRow]; 
                    mat[maxRow] = tmp;
                }

                // Check for singular matrix
                if (mat[pivot][pivot].isZero()) {
                    throw new RuntimeException("Matrix is singular - no unique solution exists (pivot " + pivot + " is zero)");
                }

                // Eliminate below pivot
                for (int i = pivot + 1; i < k; i++) {
                    if (!mat[i][pivot].isZero()) {
                        try {
                            Fraction factor = mat[i][pivot].divide(mat[pivot][pivot]);
                            for (int j = pivot; j <= k; j++) {
                                mat[i][j] = mat[i][j].subtract(factor.multiply(mat[pivot][j]));
                            }
                        } catch (ArithmeticException e) {
                            throw new RuntimeException("Arithmetic error during elimination at row " + i + ", pivot " + pivot + ": " + e.getMessage());
                        }
                    }
                }
            }

            // Back substitution
            Fraction[] coeff = new Fraction[k];
            for (int i = k - 1; i >= 0; i--) {
                try {
                    coeff[i] = mat[i][k];
                    for (int j = i + 1; j < k; j++) {
                        coeff[i] = coeff[i].subtract(mat[i][j].multiply(coeff[j]));
                    }
                    
                    if (mat[i][i].isZero()) {
                        throw new RuntimeException("Division by zero during back substitution at position " + i);
                    }
                    
                    coeff[i] = coeff[i].divide(mat[i][i]);
                } catch (ArithmeticException e) {
                    throw new RuntimeException("Arithmetic error during back substitution at position " + i + ": " + e.getMessage());
                }
            }

            // Return constant term (secret)
            return coeff[k - 1].toBigInt();
            
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw e;
            }
            throw new RuntimeException("Unexpected error during Gaussian elimination: " + e.getMessage());
        }
    }
}
