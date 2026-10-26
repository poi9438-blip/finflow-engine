package com.finflow.dto.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// 신청 접수 요청 (업무 명세 3.1, 형식이 틀리면 400 + 필드 이름)
public record CreateApplicationRequest(
                @NotBlank(message = "Product code is required") @Pattern(regexp = "DIDIMDOL|NEWBORN_DIDIMDOL|BOGEUMJARI", message = "Product code must be one of DIDIMDOL, NEWBORN_DIDIMDOL, BOGEUMJARI") String productCode,

                // HomeFin 사용자 id (homefin:{숫자})
                @NotBlank(message = "Applicant ref is required") @Size(max = 64, message = "Applicant ref must be at most 64 characters") @Pattern(regexp = "homefin:[1-9][0-9]{0,18}", message = "Applicant ref must be homefin:{user id}") String applicantRef,

                @NotNull(message = "Requested amount is required") @Min(value = 1, message = "Requested amount must be at least 1") @Max(value = 1_000_000_000L, message = "Requested amount must be at most 1000000000") Long requestedAmount,

                @NotNull(message = "Term months is required") @Min(value = 12, message = "Term months must be at least 12") @Max(value = 600, message = "Term months must be at most 600") Integer termMonths,

                @NotNull(message = "House price is required") @Min(value = 1, message = "House price must be at least 1") Long housePrice,

                @NotNull(message = "Household annual income is required") @Min(value = 0, message = "Household annual income must be 0 or more") Long householdAnnualIncome,

                @NotNull(message = "DTI is required") @DecimalMin(value = "0", message = "DTI must be 0 or more") @DecimalMax(value = "9.9999", message = "DTI must be at most 9.9999") @Digits(integer = 1, fraction = 4, message = "DTI must have at most 4 decimal places") BigDecimal dti,

                @NotNull(message = "First home is required") Boolean firstHome,
                @NotNull(message = "Newlywed is required") Boolean newlywed,
                @NotNull(message = "Newborn within 2 years is required") Boolean newbornWithin2Years,

                @NotNull(message = "Child count is required") @Min(value = 0, message = "Child count must be 0 or more") @Max(value = 20, message = "Child count must be at most 20") Integer childCount,

                @Size(max = 20, message = "Region must be at most 20 characters") String region,

                @Positive(message = "Simulation ref must be positive") Long simulationRef) {
}
