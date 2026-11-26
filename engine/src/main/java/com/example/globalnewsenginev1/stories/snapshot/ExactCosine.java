package com.example.globalnewsenginev1.stories.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;

final class ExactCosine {

    private ExactCosine() {
    }

    static float[] decode(byte[] vectorBytes, int dimension, String expectedHash) {
        if (vectorBytes == null || vectorBytes.length != dimension * Float.BYTES) {
            throw new InvalidVectorException("VECTOR_BYTE_LENGTH",
                    "Expected %d vector bytes but received %s"
                            .formatted(dimension * Float.BYTES,
                                    vectorBytes == null ? "null" : vectorBytes.length));
        }
        String actualHash = StorySnapshotCanonicalizer.sha256(vectorBytes);
        if (!actualHash.equals(expectedHash)) {
            throw new InvalidVectorException("VECTOR_HASH",
                    "Vector hash does not match the immutable artifact hash");
        }
        float[] values = new float[dimension];
        ByteBuffer buffer = ByteBuffer.wrap(vectorBytes);
        double squaredNorm = 0;
        for (int index = 0; index < dimension; index++) {
            float value = buffer.getFloat();
            if (!Float.isFinite(value)) {
                throw new InvalidVectorException("NON_FINITE_VECTOR",
                        "Vector contains NaN or Infinity");
            }
            values[index] = value;
            squaredNorm += (double) value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (!Double.isFinite(norm) || norm == 0) {
            throw new InvalidVectorException("ZERO_NORM",
                    "Vector norm must be finite and positive");
        }
        return values;
    }

    static BigDecimal similarity(float[] left, float[] right) {
        return score(left, right).quantized();
    }

    static Score score(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new InvalidVectorException("DIMENSION_MISMATCH",
                    "Vector dimensions differ");
        }
        double dot = 0;
        double leftSquaredNorm = 0;
        double rightSquaredNorm = 0;
        for (int index = 0; index < left.length; index++) {
            double leftValue = left[index];
            double rightValue = right[index];
            dot += leftValue * rightValue;
            leftSquaredNorm += leftValue * leftValue;
            rightSquaredNorm += rightValue * rightValue;
        }
        double cosine = cosine(dot, Math.sqrt(leftSquaredNorm) * Math.sqrt(rightSquaredNorm));
        return new Score(cosine, quantize(cosine));
    }

    static double norm(float[] vector) {
        double squaredNorm = 0;
        for (double value : vector) squaredNorm += value * value;
        return Math.sqrt(squaredNorm);
    }

    static double cosine(float[] left, float[] right, double leftNorm, double rightNorm) {
        if (left.length != right.length) {
            throw new InvalidVectorException("DIMENSION_MISMATCH", "Vector dimensions differ");
        }
        double dot = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
        }
        return cosine(dot, leftNorm * rightNorm);
    }

    /** Independent accumulators preserve each pair's scalar summation order. */
    static void cosines(float[] left, float[][] right, double leftNorm,
                        double[] rightNorms, double[] scores, int count) {
        if (count != 4) {
            for (int index = 0; index < count; index++) {
                scores[index] = cosine(left, right[index], leftNorm, rightNorms[index]);
            }
            return;
        }
        float[] first = right[0], second = right[1], third = right[2], fourth = right[3];
        if (left.length != first.length || left.length != second.length
                || left.length != third.length || left.length != fourth.length) {
            throw new InvalidVectorException("DIMENSION_MISMATCH", "Vector dimensions differ");
        }
        double firstDot = 0, secondDot = 0, thirdDot = 0, fourthDot = 0;
        for (int index = 0; index < left.length; index++) {
            double value = left[index];
            firstDot += value * first[index];
            secondDot += value * second[index];
            thirdDot += value * third[index];
            fourthDot += value * fourth[index];
        }
        scores[0] = cosine(firstDot, leftNorm * rightNorms[0]);
        scores[1] = cosine(secondDot, leftNorm * rightNorms[1]);
        scores[2] = cosine(thirdDot, leftNorm * rightNorms[2]);
        scores[3] = cosine(fourthDot, leftNorm * rightNorms[3]);
    }

    private static double cosine(double dot, double denominator) {
        if (!Double.isFinite(denominator) || denominator == 0) {
            throw new InvalidVectorException("ZERO_NORM",
                    "Vector norm must be finite and positive");
        }
        return Math.max(-1, Math.min(1, dot / denominator));
    }

    static BigDecimal quantize(double cosine) {
        return BigDecimal.valueOf(cosine).setScale(6, RoundingMode.HALF_UP);
    }

    static long micros(double cosine) {
        double scaled = Math.abs(cosine) * 1_000_000;
        long integral = (long) scaled;
        double fraction = scaled - integral;
        // Match the decimal HALF_UP rule when binary multiplication could round across the tie.
        if (Math.abs(fraction - 0.5) <= Math.ulp(scaled)) {
            return quantize(cosine).unscaledValue().longValueExact();
        }
        long rounded = fraction < 0.5 ? integral : integral + 1;
        return cosine < 0 ? -rounded : rounded;
    }

    record Score(double exact, BigDecimal quantized) {
    }

    static final class InvalidVectorException extends RuntimeException {
        private final String code;

        InvalidVectorException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
