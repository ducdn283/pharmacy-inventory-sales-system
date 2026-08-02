package com.example.project.dto.response;

import com.example.project.entity.Employeenote;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmployeenoteResponse {

    private static final ZoneId VN_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy HH:mm:ss"
            );

    private Integer id;
    private Integer accountId;

    private Instant date;
    private String dateDisplay;

    private String content;

    public static EmployeenoteResponse from(
            Employeenote employeenote
    ) {
        return new EmployeenoteResponse(
                employeenote.getId(),

                employeenote.getAccountID() != null
                        ? employeenote.getAccountID().getId()
                        : null,

                employeenote.getDate(),
                formatDate(employeenote.getDate()),
                employeenote.getContent()
        );
    }

    private static String formatDate(Instant date) {
        if (date == null) {
            return "-";
        }

        return DATE_TIME_FORMATTER.format(
                date.atZone(VN_ZONE)
        );
    }
}