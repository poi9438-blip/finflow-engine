package com.finflow.domain;

// 주택 보유 (HomeFin·RiskWatch와 같은 값)
public enum HouseOwnership {
    // 무주택
    NONE,
    // 무주택 (전월세 거주)
    RENT,
    // 주택 보유
    OWN
}
