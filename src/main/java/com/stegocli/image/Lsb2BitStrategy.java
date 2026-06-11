package com.stegocli.image;

public final class Lsb2BitStrategy extends AbstractLsbStrategy {

    @Override
    public int id() {
        return 2;
    }

    @Override
    public int bitsPerChannel() {
        return 2;
    }

    @Override
    public String toString() {
        return "lsb2";
    }

}
