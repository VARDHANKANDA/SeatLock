package com.example.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSeatsRequest(
        @NotEmpty
        @Size(max = 500)
        List<@NotBlank
             @Size(max = 20)
             @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "may only contain letters, digits and hyphens") String> seatNumbers) {
}
