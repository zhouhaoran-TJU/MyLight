package com.example.lightroomclone.core;

public final class CurveSet {
    public static final int LUMINANCE = 0;
    public static final int RED = 1;
    public static final int GREEN = 2;
    public static final int BLUE = 3;

    public final ToneCurve luminance = new ToneCurve();
    public final ToneCurve red = new ToneCurve();
    public final ToneCurve green = new ToneCurve();
    public final ToneCurve blue = new ToneCurve();

    public CurveSet copy() {
        CurveSet copy = new CurveSet();
        copyCurve(luminance, copy.luminance);
        copyCurve(red, copy.red);
        copyCurve(green, copy.green);
        copyCurve(blue, copy.blue);
        return copy;
    }

    public ToneCurve curveFor(int channel) {
        if (channel == RED) {
            return red;
        }
        if (channel == GREEN) {
            return green;
        }
        if (channel == BLUE) {
            return blue;
        }
        return luminance;
    }

    public void reset() {
        luminance.reset();
        red.reset();
        green.reset();
        blue.reset();
    }

    public void reset(int channel) {
        curveFor(channel).reset();
    }

    private static void copyCurve(ToneCurve source, ToneCurve target) {
        target.replaceWith(source);
    }
}
