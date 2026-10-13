package co.edu.corhuila.amarantashop.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Monday to Saturday, 08:00-12:00 and 14:00-18:00, in Bogota time (spec DP-4). The start is inclusive. */
class ShopHoursTest {

    private final ShopHours hours = new ShopHours();

    @ParameterizedTest(name = "{0} Bogota -> open={1}")
    @CsvSource({
            "2026-10-12T08:00:00-05:00, true",   // Monday, opening
            "2026-10-12T11:59:00-05:00, true",   // last minute of the morning
            "2026-10-12T12:00:00-05:00, false",  // lunch starts
            "2026-10-12T13:59:00-05:00, false",
            "2026-10-12T14:00:00-05:00, true",   // afternoon opening
            "2026-10-12T17:59:00-05:00, true",
            "2026-10-12T18:00:00-05:00, false",  // closing
            "2026-10-12T07:59:00-05:00, false",
            "2026-10-17T10:00:00-05:00, true",   // Saturday is a working day
            "2026-10-18T10:00:00-05:00, false",  // Sunday, all day
    })
    void isOpenOnlyInsideTheTwoWindows(String at, boolean open) {
        assertThat(hours.isOpenAt(OffsetDateTime.parse(at).toInstant())).isEqualTo(open);
    }
}
