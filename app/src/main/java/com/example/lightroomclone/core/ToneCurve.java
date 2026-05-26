package com.example.lightroomclone.core;

import java.util.ArrayList;
import java.util.List;

public final class ToneCurve {
    private static final int MAX_POINTS = 12;
    private static final int MIN_X_SPACING = 4;

    private final List<Integer> x = new ArrayList<>();
    private final List<Integer> y = new ArrayList<>();

    public ToneCurve() {
        reset();
    }

    public ToneCurve copy() {
        ToneCurve copy = new ToneCurve();
        copy.replaceWith(this);
        return copy;
    }

    public void reset() {
        x.clear();
        y.clear();
        x.add(0);
        y.add(0);
        x.add(255);
        y.add(255);
    }

    public void setFixedPoints(int[] values) {
        int[] fixedX = {0, 64, 128, 192, 255};
        x.clear();
        y.clear();
        for (int i = 0; i < values.length && i < fixedX.length; i++) {
            x.add(fixedX[i]);
            y.add(clamp(values[i]));
        }
    }

    public void replaceWith(ToneCurve source) {
        x.clear();
        y.clear();
        for (int i = 0; i < source.pointCount(); i++) {
            x.add(source.getX(i));
            y.add(source.getY(i));
        }
    }

    public int addPoint(int valueX, int valueY) {
        if (x.size() >= MAX_POINTS) {
            return nearestPoint(valueX, valueY);
        }
        int insertAt = 1;
        while (insertAt < x.size() && x.get(insertAt) < valueX) {
            insertAt++;
        }
        int leftBound = x.get(insertAt - 1) + MIN_X_SPACING;
        int rightBound = x.get(insertAt) - MIN_X_SPACING;
        if (leftBound > rightBound) {
            return nearestPoint(valueX, valueY);
        }
        int nextX = clamp(valueX, leftBound, rightBound);
        x.add(insertAt, nextX);
        y.add(insertAt, clamp(valueY));
        return insertAt;
    }

    public void setPoint(int index, int value) {
        setPoint(index, getX(index), value);
    }

    public void setPoint(int index, int valueX, int valueY) {
        if (index < 0 || index >= y.size()) {
            throw new IllegalArgumentException("Invalid point index " + index);
        }
        int nextX = valueX;
        if (index == 0) {
            nextX = 0;
        } else if (index == x.size() - 1) {
            nextX = 255;
        } else {
            nextX = clamp(valueX, x.get(index - 1) + MIN_X_SPACING, x.get(index + 1) - MIN_X_SPACING);
        }
        x.set(index, nextX);
        y.set(index, clamp(valueY));
    }

    public int getPoint(int index) {
        return getY(index);
    }

    public int getX(int index) {
        return x.get(index);
    }

    public int getY(int index) {
        return y.get(index);
    }

    public int pointCount() {
        return y.size();
    }

    public int nearestPoint(int valueX, int valueY) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < x.size(); i++) {
            int dx = x.get(i) - valueX;
            int dy = y.get(i) - valueY;
            int distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    public int map(int value) {
        int input = clamp(value);
        if (input <= x.get(0)) {
            return y.get(0);
        }
        for (int i = 1; i < x.size(); i++) {
            if (input <= x.get(i)) {
                return interpolateSmooth(i - 1, i, input);
            }
        }
        return y.get(y.size() - 1);
    }

    private int interpolateSmooth(int leftIndex, int rightIndex, int input) {
        int x0 = x.get(leftIndex);
        int x1 = x.get(rightIndex);
        float t = (input - x0) / (float) Math.max(1, x1 - x0);
        float m0 = slopeAt(leftIndex);
        float m1 = slopeAt(rightIndex);
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2f * t3 - 3f * t2 + 1f;
        float h10 = t3 - 2f * t2 + t;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;
        float dx = x1 - x0;
        float result = h00 * y.get(leftIndex) + h10 * dx * m0
                + h01 * y.get(rightIndex) + h11 * dx * m1;
        return clamp(Math.round(result));
    }

    private float slopeAt(int index) {
        if (index == 0) {
            return segmentSlope(0, 1);
        }
        if (index == x.size() - 1) {
            return segmentSlope(index - 1, index);
        }
        return (y.get(index + 1) - y.get(index - 1))
                / (float) Math.max(1, x.get(index + 1) - x.get(index - 1));
    }

    private float segmentSlope(int left, int right) {
        return (y.get(right) - y.get(left)) / (float) Math.max(1, x.get(right) - x.get(left));
    }

    private static int clamp(int value) {
        return clamp(value, 0, 255);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
