-- 일별 집계 (업무 명세 8장): 한국 날짜·상품별 접수·승인·거절·취소·만료 건수
-- 같은 날짜를 다시 집계하면 그 날짜 줄을 지우고 새로 씀

CREATE TABLE daily_application_stats (
    -- 한국 날짜 (Asia/Seoul)
    stat_date     DATE         NOT NULL,
    product_code  VARCHAR(30)  NOT NULL,
    received      INT          NOT NULL,
    approved      INT          NOT NULL,
    rejected      INT          NOT NULL,
    cancelled     INT          NOT NULL,
    expired       INT          NOT NULL,
    -- 집계한 시각 (UTC)
    aggregated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (stat_date, product_code)
);
