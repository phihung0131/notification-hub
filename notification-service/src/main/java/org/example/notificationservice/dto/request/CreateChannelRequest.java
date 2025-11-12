package org.example.notificationservice.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateChannelRequest {
    @NotNull(message = "Name cannot be null")
    private String name;

    @NotNull(message = "Code cannot be null")
    private String code;
}
