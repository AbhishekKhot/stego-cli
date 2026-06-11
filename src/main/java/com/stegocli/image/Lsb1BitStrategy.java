package com.stegocli.image;

public final class Lsb1BitStrategy extends AbstractLsbStrategy {

    @Override
    public int id() {
        return 1;
    }

    @Override
    public int bitsPerChannel() {
        return 1;
    }

    @Override
    public String toString() {
        return "lsb1";
    }

}