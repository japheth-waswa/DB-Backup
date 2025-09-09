package com.zurimate.appbackup.utils;

public enum CloudType {
    BACK_BLAZE(CloudTypeStrategyConstants.BACK_BLAZE_STRATEGY);

    private final String strategyName;

    CloudType(String strategyName) {
        this.strategyName = strategyName;
    }

    @Override
    public String toString() {
        return this.strategyName;
    }
}
