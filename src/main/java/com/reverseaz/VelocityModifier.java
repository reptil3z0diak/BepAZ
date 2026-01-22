package com.reverseaz;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stockage thread-safe des multiplicateurs de vélocité
 */
public class VelocityModifier {

    private final AtomicReference<Double> multiplierX = new AtomicReference<>(1.0);
    private final AtomicReference<Double> multiplierY = new AtomicReference<>(1.0);
    private final AtomicReference<Double> multiplierZ = new AtomicReference<>(1.0);

    public void setMultiplierX(double value) {
        multiplierX.set(value);
    }

    public void setMultiplierY(double value) {
        multiplierY.set(value);
    }

    public void setMultiplierZ(double value) {
        multiplierZ.set(value);
    }

    public void setHorizontalMultiplier(double value) {
        multiplierX.set(value);
        multiplierZ.set(value);
    }

    public void setMultipliers(double x, double y, double z) {
        multiplierX.set(x);
        multiplierY.set(y);
        multiplierZ.set(z);
    }

    public void reset() {
        multiplierX.set(1.0);
        multiplierY.set(1.0);
        multiplierZ.set(1.0);
    }

    public double getMultiplierX() {
        return multiplierX.get();
    }

    public double getMultiplierY() {
        return multiplierY.get();
    }

    public double getMultiplierZ() {
        return multiplierZ.get();
    }

    /**
     * Modifie les valeurs de vélocité selon les multiplicateurs
     * 
     * @param velX vélocité X originale (short format MC)
     * @param velY vélocité Y originale
     * @param velZ vélocité Z originale
     * @return tableau [newVelX, newVelY, newVelZ]
     */
    public short[] modifyVelocity(short velX, short velY, short velZ) {
        short newX = clampShort((int) (velX * multiplierX.get()));
        short newY = clampShort((int) (velY * multiplierY.get()));
        short newZ = clampShort((int) (velZ * multiplierZ.get()));
        return new short[] { newX, newY, newZ };
    }

    private short clampShort(int value) {
        if (value > Short.MAX_VALUE)
            return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE)
            return Short.MIN_VALUE;
        return (short) value;
    }
}
