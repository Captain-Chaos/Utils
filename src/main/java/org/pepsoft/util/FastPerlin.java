package org.pepsoft.util;

public class FastPerlin {
    private final short[] permPair = new short[256];

    public void setSeed(long seed) {
        UnsafeRandom r = new UnsafeRandom(seed);
        byte[] permutation = new byte[256];
        for(int i = 0; i < 256; ++i) {
            permutation[i] = (byte)i;
        }

        for(int i = 0; i < 256; ++i) {
            int j = r.nextInt(256 - i);
            byte b = permutation[255-i];
            permutation[255-i] = permutation[j];
            permutation[j] = b;
        }

        for (int i = 0; i < 256; i++) {
            this.permPair[i] = (short) ((permutation[i]&0xFF)|((permutation[(i+1)&0xFF]&0xFF)<<8));
        }
    }

    private static float fade(float v) {
        return v*v*v*Math.fma(v, Math.fma(v, 6f, -15f), 10f);
    }

    private int getPair(int idx) {
        return this.permPair[idx&0xFF]&0xFFFF;
    }

    public float sampleResult(double X) {
        int bx = (int) Math.floor(X);

        //TODO: check this is right
        float lx = (float) (X-Math.floor(X));

        int x = this.getPair(bx);
        int x0y = this.getPair(x);
        int x1y = this.getPair(x>>8);
        int x0y0z = this.getPair(x0y);
        int x1y0z = this.getPair(x1y);

        float px = fade(lx);



        float r000 = grad(x0y0z, lx);
        float l00  = r000;

        float l0   = l00;

        float r100 = grad(x1y0z, lx-1.0f);
        float l10  = r100;

        float l1   = l10;

        return lerp(px, l0, l1);
    }

    public float sampleResult(double X, double Y) {
        int bx = (int) Math.floor(X);
        int by = (int) Math.floor(Y);

        //TODO: check this is right
        float lx = (float) (X-Math.floor(X));
        float ly = (float) (Y-Math.floor(Y));

        int x = this.getPair(bx);
        int x0y = this.getPair(x+by);
        int x1y = this.getPair((x>>8)+by);
        int x0y0z = this.getPair(x0y);
        int x0y1z = this.getPair(x0y>>8);
        int x1y0z = this.getPair(x1y);
        int x1y1z = this.getPair(x1y>>8);

        float px = fade(lx);
        float py = fade(ly);



        float r000 = grad(x0y0z, lx, ly);
        float l00  = r000;

        float r010 = grad(x0y1z, lx, ly-1.0f);
        float l01  = r010;
        float l0   = lerp(py, l00, l01);

        float r100 = grad(x1y0z, lx-1.0f, ly);
        float l10  = r100;

        float r110 = grad(x1y1z, lx-1.0f, ly-1.0f);
        float l11  = r110;
        float l1   = lerp(py, l10, l11);

        return lerp(px, l0, l1);
    }

    public float sampleResult(double X, double Y, double Z) {
        int bx = (int) Math.floor(X);
        int by = (int) Math.floor(Y);
        int bz = (int) Math.floor(Z);

        //TODO: check this is right
        float lx = (float) (X-Math.floor(X));
        float ly = (float) (Y-Math.floor(Y));
        float lz = (float) (Z-Math.floor(Z));

        int x = this.getPair(bx);
        int x0y = this.getPair(x+by);
        int x1y = this.getPair((x>>8)+by);
        int x0y0z = this.getPair(x0y+bz);
        int x0y1z = this.getPair((x0y>>8)+bz);
        int x1y0z = this.getPair(x1y+bz);
        int x1y1z = this.getPair((x1y>>8)+bz);

        float px = fade(lx);
        float py = fade(ly);
        float pz = fade(lz);



        float r000 = grad(x0y0z, lx, ly, lz);
        float r001 = grad(x0y0z>>8, lx, ly, lz-1.0f);
        float l00  = lerp(pz, r000, r001);

        float r010 = grad(x0y1z, lx, ly-1.0f, lz);
        float r011 = grad(x0y1z>>8, lx, ly-1.0f, lz-1.0f);
        float l01  = lerp(pz, r010, r011);
        float l0   = lerp(py, l00, l01);

        float r100 = grad(x1y0z, lx-1.0f, ly, lz);
        float r101 = grad(x1y0z>>8, lx-1.0f, ly, lz-1.0f);
        float l10  = lerp(pz, r100, r101);

        float r110 = grad(x1y1z, lx-1.0f, ly-1.0f, lz);
        float r111 = grad(x1y1z>>8, lx-1.0f, ly-1.0f, lz-1.0f);
        float l11  = lerp(pz, r110, r111);
        float l1   = lerp(py, l10, l11);

        return lerp(px, l0, l1);
    }
    private static float grad(int v, float x, float y, float z) {
        v = (v&15)*3;
        return Math.fma(x, LUT2[v], Math.fma(y, LUT2[v+1], z*LUT2[v+2]));
    }

    private static float grad(int v, float x) {
        v = (v&15)*3;
        return x*LUT2[v];
    }

    private static float grad(int v, float x, float y) {
        v = (v&15)*3;
        return Math.fma(x, LUT2[v], y*LUT2[v+1]);
    }

    private static float lerp(float progress, float a, float b) {
        return Math.fma(b-a, progress, a);
    }

    private static final float[] LUT2 = {
            1, 1, 0,
            -1, 1, 0,
            1, -1, 0,
            -1, -1, 0,
            1, 0, 1,
            -1, 0, 1,
            1, 0, -1,
            -1, 0, -1,
            0, 1, 1,
            0, -1, 1,
            0, 1, -1,
            0, -1, -1,
            1, 1, 0,
            0, -1, 1,
            -1, 1, 0,
            0, -1, -1
    };
}
