package org.pepsoft.util;

public class UnsafeRandom {
    private long seed;
    public UnsafeRandom(long seed) {
        this.setSeed(seed);
    }

    public void setSeed(long seed) {
        this.seed = seed^MUL;
    }

    private long next() {
        return this.seed = this.seed*MUL+ADD;
    }

    private int next(int bits) {
        return (int) ((this.next()&MSK)>>(48-bits));
    }

    public int nextInt(int bound) {
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0)  // i.e., bound is a power of 2
            r = (int)((bound * (long)r) >> 31);
        else { // reject over-represented candidates
            for (int u = r;
                 u - (r = u % bound) + m < 0;
                 u = next(31))
                ;
        }
        return r;
    }

    private static final long MUL = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MSK = (1L << 48) - 1;

}
